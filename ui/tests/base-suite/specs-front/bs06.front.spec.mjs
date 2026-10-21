// lane qa-release (2026-10-22) BS-06 live - the intake variants against the BOOTED app (the container tests
// IntakeCommitContainerTest / IntakeEndpointShapeTest prove them in isolation): 202 + tracking id only with
// the message, outbox and INTAKE timing rows committed together; every refusal persists nothing.
// No dev-only exception, no local target: the front half stops at the intake commit.
//
//   BS-06.1 accepted: 202, a UUID tracking id, and the message + outbox aggregate + INTAKE timing rows exist
//   BS-06.2 refusals: off-pin 422, missing version / idempotency header 400, malformed 400, wrong or no
//           Content-Type 415, unknown interface 404, no token 401 - 0 new message rows after each
//   BS-06.3 size: over 1 MB -> 413 PAYLOAD_OVER_LIMIT (limit named, nothing persisted)
//   BS-06.4 size: exactly 1 MB -> 202 (the cap is a strict ">"); last, because the biggest body is the one
//           most likely to trouble the relay

import { expect, test } from '@playwright/test';
import { exactBytesBody, uuidShaped } from '../lib/front-checks.mjs';
import { intakeTimingSql, messageRowsSql, outboxAggregatesSql } from '../lib/direct-sql.mjs';
import { BS_STORAGE_STATE } from '../lib/suite-config.mjs';
import { FLOW, SOURCE_CONTRACT, TENANTS, orderRecord } from '../lib/tenants.mjs';
import { postRecord, sourceToken } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { readRows } from '../support/db.mjs';
import { rawIntake } from '../support/front-api.mjs';
import { addTracking, readRun } from '../support/run-file.mjs';
import { rotatedSecret } from '../support/seed.mjs';

test.describe.configure({ mode: 'serial' });

const LIMIT = 1_048_576;
const code = () => bsEnv('tenantA');
const messageCount = () => readRows(messageRowsSql(code())).length;
const ids = (sql) => readRows(sql).map(([id]) => id);

let token;
test.beforeAll(async ({ browser }) => {
  const context = await browser.newContext({ storageState: BS_STORAGE_STATE });
  const page = await context.newPage();
  await page.goto(`${bsEnv('consoleUrl')}/`);
  const { clientId, secretValue } = await rotatedSecret(page, TENANTS.A, readRun().seed.A.credentialId);
  token = await sourceToken(clientId, secretValue);
  await context.close();
});

test('BS-06.1: accepted - 202 with a tracking id, and message, outbox and INTAKE timing rows exist for it', async () => {
  const before = messageCount();
  const answer = await postRecord(token, FLOW.code, SOURCE_CONTRACT.versionLabel, orderRecord(TENANTS.A, 601));
  expect(answer.status, JSON.stringify(answer.data)).toBe(202);
  const trackingId = answer.data.trackingId;
  expect(uuidShaped(trackingId), `tracking id ${trackingId}`).toBe(true);
  addTracking('A', [trackingId]);
  expect(messageCount()).toBe(before + 1);
  expect(ids(messageRowsSql(code())), 'the message row').toContain(trackingId);
  expect(ids(outboxAggregatesSql(code())), 'the outbox row for the same message').toContain(trackingId);
  expect(ids(intakeTimingSql(code())), 'the INTAKE timing row for the same message').toContain(trackingId);
});

test('BS-06.2: refusals persist nothing - off-pin 422, headers 400, malformed 400, content type 415, unknown 404, no token 401', async () => {
  const good = JSON.stringify(orderRecord(TENANTS.A, 602));
  const cases = [
    ['off-pin contract version 9.9', 422, () => rawIntake(token, FLOW.code, { version: '9.9', body: good })],
    ['missing X-Source-Contract-Version', 400, () => rawIntake(token, FLOW.code, { version: null, body: good })],
    ['missing Idempotency-Key', 400, () => rawIntake(token, FLOW.code, { idempotencyKey: null, body: good })],
    ['malformed JSON', 400, () => rawIntake(token, FLOW.code, { body: '{not json' })],
    ['wrong Content-Type (text/plain)', 415, () => rawIntake(token, FLOW.code, { contentType: 'text/plain', body: good })],
    ['no Content-Type', 415, () => rawIntake(token, FLOW.code, { contentType: null, body: good })],
    ['unknown interface', 404, () => rawIntake(token, 'no-such-interface', { body: good })],
    ['no token', 401, () => rawIntake(null, FLOW.code, { body: good })],
    ['a token that is not one', 401, () => rawIntake('not-a-token', FLOW.code, { body: good })]
  ];
  const before = messageCount();
  const problems = [];
  for (const [what, status, send] of cases) {
    const answer = await send();
    if (answer.status !== status) problems.push(`${what}: expected ${status}, got ${answer.status} ${JSON.stringify(answer.data).slice(0, 160)}`);
    if (answer.data?.trackingId) problems.push(`${what}: a refusal carried a tracking id`);
  }
  expect(problems, 'every refusal has its status and no tracking id').toEqual([]);
  expect(messageCount(), 'nothing persisted by any refusal').toBe(before);
});

test('BS-06.3: a body over 1 MB is refused 413 PAYLOAD_OVER_LIMIT, the limit named, nothing persisted', async () => {
  const before = messageCount();
  const answer = await rawIntake(token, FLOW.code, { body: exactBytesBody(orderRecord(TENANTS.A, 603), LIMIT + 1) });
  expect(answer.status, JSON.stringify(answer.data).slice(0, 200)).toBe(413);
  expect(answer.data.code).toBe('PAYLOAD_OVER_LIMIT');
  expect(answer.data.limitBytes).toBe(LIMIT);
  expect(messageCount()).toBe(before);
});

test('BS-06.4: a body of exactly 1 MB is accepted 202 (the cap is a strict ">")', async () => {
  const before = messageCount();
  const answer = await rawIntake(token, FLOW.code, { body: exactBytesBody(orderRecord(TENANTS.A, 604), LIMIT) });
  expect(answer.status, JSON.stringify(answer.data).slice(0, 200)).toBe(202);
  expect(uuidShaped(answer.data.trackingId)).toBe(true);
  addTracking('A', [answer.data.trackingId]);
  expect(messageCount()).toBe(before + 1);
});
