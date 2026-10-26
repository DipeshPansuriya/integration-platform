// lane qa-release (2026-10-22) SC-07 (cross-tenant denial, end to end) - the part that needs no delivery and no
// dev-only exception (E15 scenario plan row 327, SC-07 steps 1-3): tenant B's login and tenant B's source
// credential against tenant A's rows. 0 rows of A reach B, refusals are 403/404, and B's attempts change nothing
// of A's (its message count, its credential). Positive controls first, so a 404 means "not yours", not "not there".
//
//   SC-07.1 B's admin reads A's flow routes -> 404, no A marker / id in the answer; A's own read -> 200
//   SC-07.2 B's admin reads A's message timeline -> 404; A's own read -> 200 (positive control)
//   SC-07.3 B's failures list and queue summary carry nothing of A (ids, markers)
//   SC-07.4 B's admin cannot rotate A's source credential; A's secret still works after the attempt
//   SC-07.5 B's source posting A's customerId plus forged tenant headers is REFUSED 403 and nothing lands in
//           either tenant. Reading agreed with Dipesh (row 375, run 8 finding 2): the tenant-isolation-safe
//           answer is the refusal - the intake does not accept a customerId the credential is not bound to into
//           the caller's own flow (the earlier TBD(T106) hypothesis of 202 into B's flow is withdrawn).

import { expect, test } from '@playwright/test';
import { foreignIdHits } from '../lib/front-checks.mjs';
import { messageRowsSql } from '../lib/direct-sql.mjs';
import { BS_STORAGE_STATE } from '../lib/suite-config.mjs';
import { FLOW, SOURCE_CONTRACT, TENANTS, forgedRecord, orderRecord } from '../lib/tenants.mjs';
import { inBrowser, must, postRecord, sourceToken } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { readRows } from '../support/db.mjs';
import { rawIntake } from '../support/front-api.mjs';
import { addTracking, readRun } from '../support/run-file.mjs';
import { intakeCode, rotatedSecret } from '../support/seed.mjs';

test.describe.configure({ mode: 'serial' });

const REFUSED = [403, 404];
const codeOf = (tenant) => bsEnv(tenant.key === 'A' ? 'tenantA' : 'tenantB');
const countOf = (tenant) => readRows(messageRowsSql(codeOf(tenant))).length;
const idsA = () => {
  const { seed } = readRun();
  return [seed.A.flowId, seed.A.routeId, seed.A.mappingId, seed.A.sourceSystemId, seed.A.targetSystemId, seed.A.credentialId, seed.A.clientId];
};

let context;
let page;
let aTrackingId;
let aSecret;
test.beforeAll(async ({ browser }) => {
  context = await browser.newContext({ storageState: BS_STORAGE_STATE });
  page = await context.newPage();
  await page.goto(`${bsEnv('consoleUrl')}/`);
  const { seed } = readRun();
  aSecret = await rotatedSecret(page, TENANTS.A, seed.A.credentialId);
  const answer = await postRecord(await sourceToken(aSecret.clientId, aSecret.secretValue), intakeCode(TENANTS.A), SOURCE_CONTRACT.versionLabel, orderRecord(TENANTS.A, 701));
  expect(answer.status, JSON.stringify(answer.data)).toBe(202);
  aTrackingId = answer.data.trackingId;
  addTracking('A', [aTrackingId]);
});
test.afterAll(async () => context.close());

test("SC-07.1: tenant B's admin cannot read tenant A's flow; tenant A's admin can", async () => {
  const { seed } = readRun();
  const own = must(await inBrowser(page, 'GET', `/api/v1/admin/flows/${seed.A.flowId}/routes`, undefined, TENANTS.A.admin), 'A reads its own routes');
  expect(own.routes, "A's own flow has its route").toHaveLength(1);
  const foreign = await inBrowser(page, 'GET', `/api/v1/admin/flows/${seed.A.flowId}/routes`, undefined, TENANTS.B.admin);
  expect(foreign.status, JSON.stringify(foreign.data)).toBe(404);
  expect(foreignIdHits(JSON.stringify(foreign.data), TENANTS.B, idsA()), "nothing of A's in B's answer").toEqual([]);
});

test("SC-07.2: tenant B's admin cannot read tenant A's message timeline; tenant A's admin can", async () => {
  const path = `/api/v1/admin/messages/${aTrackingId}/timeline`;
  const own = await inBrowser(page, 'GET', path, undefined, TENANTS.A.admin);
  expect(own.status, JSON.stringify(own.data)).toBe(200);
  expect(own.data.trackingId).toBe(aTrackingId);
  const foreign = await inBrowser(page, 'GET', path, undefined, TENANTS.B.admin);
  expect(foreign.status, JSON.stringify(foreign.data)).toBe(404);
  expect(foreignIdHits(JSON.stringify(foreign.data), TENANTS.B, [...idsA(), aTrackingId]), "nothing of A's in B's answer").toEqual([]);
});

test("SC-07.3: tenant B's failures list and queue summary carry nothing of tenant A", async () => {
  for (const path of ['/api/v1/admin/messages/failures', '/api/v1/admin/queue/summary']) {
    const answer = await inBrowser(page, 'GET', path, undefined, TENANTS.B.admin);
    expect(answer.status, `${path}: ${JSON.stringify(answer.data)}`).toBe(200);
    expect(foreignIdHits(JSON.stringify(answer.data), TENANTS.B, [...idsA(), aTrackingId]), `${path}: nothing of A's`).toEqual([]);
  }
});

test("SC-07.4: tenant B's admin cannot rotate tenant A's source credential - A's secret still works", async () => {
  const { seed } = readRun();
  const attempt = await inBrowser(page, 'POST', `/api/v1/admin/source-credentials/${seed.A.credentialId}/rotate`, undefined, TENANTS.B.admin);
  expect(REFUSED, `B rotating A's credential answered ${attempt.status} ${JSON.stringify(attempt.data)}`).toContain(attempt.status);
  expect(foreignIdHits(JSON.stringify(attempt.data), TENANTS.B, idsA())).toEqual([]);
  const token = await sourceToken(aSecret.clientId, aSecret.secretValue); // throws when the secret was rotated away
  expect(token.length).toBeGreaterThan(20);
});

test("SC-07.5: tenant B's source posting tenant A's customerId with forged tenant headers is refused 403 and nothing lands in either tenant", async () => {
  const { seed } = readRun();
  const b = await rotatedSecret(page, TENANTS.B, seed.B.credentialId);
  const bToken = await sourceToken(b.clientId, b.secretValue);
  const beforeA = countOf(TENANTS.A);
  const beforeB = countOf(TENANTS.B);
  const forged = { 'X-Tenant-Id': codeOf(TENANTS.A), 'X-Tenant': codeOf(TENANTS.A), 'X-Tenant-Code': codeOf(TENANTS.A) };
  const answer = await rawIntake(bToken, intakeCode(TENANTS.B), { body: JSON.stringify(forgedRecord(701)), extraHeaders: forged });
  expect(answer.status, JSON.stringify(answer.data)).toBe(403);
  expect(answer.data?.trackingId, 'a refusal carries no tracking id').toBeUndefined();
  expect(foreignIdHits(JSON.stringify(answer.data ?? ''), TENANTS.B, idsA()), "the refusal leaks nothing of A").toEqual([]);
  expect(countOf(TENANTS.A), "A's message count did not move").toBe(beforeA);
  expect(countOf(TENANTS.B), "B's message count did not move").toBe(beforeB);
});
