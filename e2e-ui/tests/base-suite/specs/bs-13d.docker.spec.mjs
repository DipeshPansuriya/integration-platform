// BS-13d (plan section 3, release-suite-base-20261022.md) - the replay leg: tenant B cannot replay or retry
// tenant A's delivery. B's admin POSTs the replay and the retry of a REAL delivery of tenant A (a row the
// pipeline wrote, never a seeded one): both answer 404 - byte-identical in status AND body to an id that never
// existed - and nothing of A's delivery changes (state, attempts, events, audit events). The read legs of 13d are
// specs-front/sc07.front.spec.mjs SC-07.2 (timeline 404) and SC-07.3 (failures + queue carry nothing of A).
// T202 (karan, 2026-10-23 sign-off, item 3): (1) the delivery is DEAD_LETTERED (A's stub answers 403), the one state
// in which replay/retry would act, so a leak would change something; (2) the cross-tenant answer is compared with the
// unknown-id answer in status and body; (3) a positive control: A's own admin on the same delivery gets a non-404
// (200 or 409) while an unknown id still gets 404 from A's admin, so a wrong route cannot pass the case vacuously.
// Never run against production; never a psql-seeded row (unit/direct-sql.test.mjs scans this file).

import { expect, test } from '@playwright/test';
import { attemptRowsSql, auditRowsSql, deliveryOfMessageSql, eventTypesSql } from '../lib/direct-sql.mjs';
import { fill, nameOf, waitsOn } from '../lib/pending-names.mjs';
import { changedKeys } from '../lib/replay.mjs';
import { SOURCE_CONTRACT, TENANTS, orderRecord } from '../lib/tenants.mjs';
import { eventually, inBrowser, postRecord, setStub, sourceToken } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { readRows } from '../support/db.mjs';
import { addTracking, readRun } from '../support/run-file.mjs';
import { intakeCode, rotatedSecret } from '../support/seed.mjs';

const WAITS = waitsOn('T106', 'T111');
test.fixme(WAITS !== null, WAITS ?? '');

const PIPELINE_MS = 90000;
const A = TENANTS.A;
const B = TENANTS.B;
const code = () => bsEnv('tenantA');
const UNKNOWN_DELIVERY = '00000000-0000-4000-8000-000000000013';

let stubAt403 = false;
test.afterAll(async () => {
  if (stubAt403) await setStub(A.stubPath, 200); // never leave tenant A's target refusing for BS-12
});

const deliveryOf = (messageId) => {
  const rows = readRows(deliveryOfMessageSql(code(), messageId));
  return rows.length === 1 ? { id: rows[0][0], state: rows[0][1] } : null; // single target: one delivery per message
};
const snapshot = (messageId, deliveryId) => ({
  state: deliveryOf(messageId)?.state,
  attempts: readRows(attemptRowsSql(code(), deliveryId)),
  events: readRows(eventTypesSql(code(), deliveryId)).map(([type]) => type),
  audits: readRows(auditRowsSql(code())).map(([id]) => id)
});

test("BS-13d: tenant B's admin replaying or retrying tenant A's delivery gets 404 and nothing of the delivery changes", async ({ page }) => {
  test.setTimeout(4 * 60 * 1000);
  const [method, replayPath] = nameOf('replayApi').split(' ');
  const retryPath = replayPath.replace(/\/replay$/, '/retry'); // DeliveryOperatorEndpointRoles.java:21-22, the retry beside the replay
  await setStub(A.stubPath, 403); // the delivery dead-letters: the state in which replay and retry would act
  stubAt403 = true;
  await page.goto(`${bsEnv('consoleUrl')}/`);
  const { credentialId } = readRun().seed.A;
  const { clientId, secretValue } = await rotatedSecret(page, A, credentialId);
  const answer = await postRecord(await sourceToken(clientId, secretValue), intakeCode(A), SOURCE_CONTRACT.versionLabel, orderRecord(A, 13));
  expect(answer.status, JSON.stringify(answer.data)).toBe(202);
  const messageId = answer.data.trackingId;
  addTracking('A', [messageId]);

  // a delivery that has dead-lettered, so a later difference can only come from tenant B's call
  const settle = () => eventually(() => {
    const d = deliveryOf(messageId);
    return d && (d.state === 'DELIVERED' || d.state === 'DEAD_LETTERED') ? d : null;
  }, `the delivery of ${messageId} settled`, { timeoutMs: PIPELINE_MS });
  const delivery = await eventually(() => {
    const d = deliveryOf(messageId);
    return d?.state === 'DEAD_LETTERED' ? d : null;
  }, `the delivery of ${messageId} dead-lettered`, { timeoutMs: PIPELINE_MS });

  const before = snapshot(messageId, delivery.id);
  expect(before.state, 'precondition: the delivery is DEAD_LETTERED, so a leak to tenant B would change it').toBe('DEAD_LETTERED');
  for (const [what, path] of [['replay', replayPath], ['retry', retryPath]]) {
    const forged = await inBrowser(page, method, fill(path, { deliveryId: delivery.id }), undefined, B.admin);
    expect(forged.status, `B's ${what} of A's delivery: ${JSON.stringify(forged.data)}`).toBe(404);
    const unknown = await inBrowser(page, method, fill(path, { deliveryId: UNKNOWN_DELIVERY }), undefined, B.admin);
    expect(forged.status, `${what}: A's delivery answers like an id that does not exist (status)`).toBe(unknown.status);
    expect(forged.data, `${what}: A's delivery answers like an id that does not exist (body)`).toEqual(unknown.data);
  }
  expect(changedKeys(before, snapshot(messageId, delivery.id)), "state, attempts, events and audit events of A's delivery unchanged").toEqual([]);

  // positive control, last because it acts on the delivery: the routes exist and see A's delivery as A's own admin
  for (const [what, path] of [['replay', replayPath], ['retry', retryPath]]) {
    const own = await inBrowser(page, method, fill(path, { deliveryId: delivery.id }), undefined, A.admin);
    expect([200, 409], `control: A's own admin's ${what} of A's delivery is not a 404 (answer: ${JSON.stringify(own.data)})`).toContain(own.status);
    const ownUnknown = await inBrowser(page, method, fill(path, { deliveryId: UNKNOWN_DELIVERY }), undefined, A.admin);
    expect(ownUnknown.status, `control: A's admin on an id that does not exist gets the 404`).toBe(404);
  }
  await settle(); // leave nothing in flight for BS-12
});
