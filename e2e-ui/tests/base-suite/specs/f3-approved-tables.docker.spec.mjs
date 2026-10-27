// lane qa-f3-approved-tables (2026-10-22) - E16-F3 "approved tables": a tenant's DB table is usable by a DB route
// only after a tenant approver or admin approved the request (the requester may approve their own: single-user
// workflow, V2_4_1420), and nothing of it crosses tenants.
// Merged on main as c95fa254 (feature commits 8036f74e, ca502edd, 98b8a891, 980d1c8a, d0cf84ec); the codes asserted
// here are read from ApprovedTableAdminController / ApprovedTableService / DbTablePublishListener / DbTestConnection
// (see lib/approved-tables.mjs) - none is invented. Real API only, through the real console proxy; the only UI
// drive is the mapping designer (support/seed.mjs drawMapping), exactly as the seed draws it. Never a psql row.
// Two logins per tenant (lib/tenants.mjs): A = "default" (TENANT_ADMIN, the author) + "approver"; B = bs-b-admin +
// bs-b-approver. A red is reported, never retried (retries: 0).
//
// Scenarios (serial; one booted app):
//   1 setup A     DB system + approved DB target contract + a flow to it, drawn, submitted, approved
//   2 publish     refused 400 "not approved ... f3-db-a.f3_orders" with NO request; again while PENDING_APPROVAL
//   3 (retired)   was: the requester approving their own request -> 403; inverted as test 11 (own approval is 200)
//   4 tenant B    cannot list / request / approve A's table (404); A's list carries nothing of B
//   5 approve     a different approver approves -> APPROVED (decidedBy != requestedBy); re-approve 409 NOT_PENDING_APPROVAL
//   6 publish     the same publish now passes (table approved + column check against the approved contract)
//   7 tenant B    A's APPROVED table does not make B's same-named table usable; B cannot revoke A's row (404)
//   8 revoke      approver revokes -> REVOKED; second revoke 409 NOT_APPROVED
//   9 test-conn   a DB system on an off-list port is refused FAILED/DESTINATION_REFUSED, nothing granted is reported
//  10 no-cred     B's system has no stored credential: test-connection is FAILED / NO_CREDENTIAL
//  11 self        the requester approves their OWN table (single-user workflow): 200 APPROVED, decidedBy == requestedBy,
//                 and the flow to that table, refused before, now publishes
//  12 narrow      A's stored credential + narrow login: test-connection SUCCESS reporting INSERT, SELECT and nothing wider
// F3 (row 523, ADR-H-IPASS-03): the approve step dials the target's REAL Postgres with the restricted account
// (DbAccountWidth), so the DB systems here point at the boot's target-database sidecar (boot-backend.mjs targetDb), not at
// WireMock: tenant A and B each get their own narrow login (INSERT and SELECT on the two tables), A's password goes into the
// vault through the credentials API, B's is deliberately left out (scenario 10). Scenario 9 keeps a system on the WireMock port.
// Not covered (no API seam without a real target DB): the send itself (DbTargetCall) refusing an unapproved table.

import { expect, test } from '@playwright/test';
import { exceptionSettings, requireException } from '../lib/settings.mjs';
import { FLOW, SOURCE_CONTRACT, TENANTS } from '../lib/tenants.mjs';
import {
  DB_SELF_TABLE, DB_TABLE, DB_TARGET_COLUMNS, REFUSAL, STATUS, approvedTablesPath, dbSystemBody, decidedRowProblems, leaksOf, publishRefusalProblems,
  refusalOf, requestedRowProblems
} from '../lib/approved-tables.mjs';
import { inBrowser, must } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { readRun } from '../support/run-file.mjs';
import { drawMapping } from '../support/seed.mjs';
import { createTargetAccount, targetDbPort } from '../support/target-db.mjs';

test.describe.configure({ mode: 'serial' });

const SCHEMA = 'https://json-schema.org/draft/2020-12/schema';
const DB_CONTRACT = Object.freeze({ role: 'TARGET_REQUEST', versionLabel: '1.0' });
const dbDocument = {
  $schema: SCHEMA, type: 'object', required: Object.keys(DB_TARGET_COLUMNS),
  properties: Object.fromEntries(Object.entries(DB_TARGET_COLUMNS).map(([name, type]) => [name, { type }]))
};
const DB_SYSTEM = Object.freeze({ A: 'f3-db-a', B: 'f3-db-b', self: 'f3-db-self' });
const FLOW_CODE = Object.freeze({ A: 'f3-orders-db-a', B: 'f3-orders-db-b', self: 'f3-orders-db-self' });

/** Per-tenant facts the scenarios share (ids only). */
const ctx = { A: {}, B: {}, self: {} };
let exception;

const api = (page, tenant, who, method, apiPath, body) => inBrowser(page, method, apiPath, body, tenant[who]);
const open = (page) => page.goto(`${bsEnv('consoleUrl')}/#/`);

/** One tenant's DB system + approved DB target contract + a flow to it (drawn, submitted, approved; NOT published). */
async function setupDbFlow(page, tenant, { storeCredential, variant = tenant.key, table = DB_TABLE, login = createTargetAccount(tenant.key.toLowerCase()) }) {
  await open(page);
  const k = tenant.key;
  const as = (who, method, apiPath, body, what) => api(page, tenant, who, method, apiPath, body).then((a) => must(a, `${k}: ${what}`));
  // the tenant's own narrow login on the target database (INSERT, SELECT only); its password never leaves this worker's memory
  const system = await as('admin', 'POST', '/api/v1/admin/systems',
    dbSystemBody(DB_SYSTEM[variant], `F3 DB ${variant}`, exception, { port: targetDbPort(), account: login.account, table }), 'DB system');
  if (storeCredential) {
    const endpoint = await as('admin', 'GET', `/api/v1/admin/systems/${system.id}/endpoint-auth`, undefined, 'read the DB system endpoint');
    await as('admin', 'PUT', `/api/v1/admin/target-endpoints/${endpoint.endpointId}/credentials`, { fields: { accountPassword: login.password } },
      'store the restricted account password in the vault');
  }
  const version = await as('admin', 'POST', '/api/v1/admin/contracts/versions/import', {
    system: DB_SYSTEM[variant], role: DB_CONTRACT.role, versionLabel: DB_CONTRACT.versionLabel, format: 'JSON_SCHEMA', document: dbDocument
  }, 'DB target contract');
  await as('admin', 'POST', `/api/v1/admin/contracts/versions/${version.id}/submit`, { changeNote: 'f3 DB target contract' }, 'submit contract');
  await as('approver', 'POST', `/api/v1/admin/contracts/versions/${version.id}/approve`, undefined, 'approve contract');

  const flowBody = { code: FLOW_CODE[variant], name: FLOW_CODE[variant], sourceSystem: SOURCE_CONTRACT.system, destinations: [DB_SYSTEM[variant]], businessKeyRule: FLOW.businessKeyRule };
  const flow = await as('admin', 'POST', '/api/v1/admin/flows', flowBody, 'flow to the DB system');
  const routes = (await as('admin', 'GET', `/api/v1/admin/flows/${flow.id}/routes`, undefined, 'routes')).routes ?? [];
  expect(routes, `${k}: one target, one route`).toHaveLength(1);
  const sourceVersion = readRun().seed[k].versionIds.SOURCE_REQUEST;
  await drawMapping(page, tenant, routes[0].id, { SOURCE_REQUEST: sourceVersion, TARGET_REQUEST: version.id });
  await open(page);
  await as('admin', 'PUT', `/api/v1/admin/flows/${flow.id}`, flowBody, 'draft save');
  await as('admin', 'POST', `/api/v1/admin/flows/${flow.id}/submit`, { changeNote: 'f3: flow to a DB target' }, 'submit flow');
  await as('approver', 'POST', `/api/v1/admin/flows/${flow.id}/approve`, undefined, 'approve flow');
  return { systemId: system.id, flowId: flow.id, contractVersionId: version.id, login };
}

const publish = (page, tenant) => api(page, tenant, 'admin', 'POST', `/api/v1/admin/flows/${ctx[tenant.key].flowId}/publish`);
const listOf = async (page, tenant, who, systemId) => must(await api(page, tenant, who, 'GET', approvedTablesPath(systemId)), `${tenant.key}: list approved tables`);

test.beforeAll(() => {
  exception = requireException(exceptionSettings(process.env));
});

test('1 setup: tenant A registers a DB system, its approved contract and a flow to it', async ({ page }) => {
  ctx.A = await setupDbFlow(page, TENANTS.A, { storeCredential: true });
  const stored = must(await api(page, TENANTS.A, 'admin', 'GET', `/api/v1/admin/systems/${ctx.A.systemId}`), 'read the DB system');
  expect(stored.type).toBe('DB');
  expect(stored.settings, 'the approval list is not a setting any more (F3)').not.toHaveProperty('approvedTables');
  expect(await listOf(page, TENANTS.A, 'admin', ctx.A.systemId), 'nothing requested yet').toEqual([]);
});

test('2 a DB route cannot publish until its table is approved: refused with no request, and while PENDING_APPROVAL', async ({ page }) => {
  await open(page);
  const noRequest = await publish(page, TENANTS.A);
  expect(publishRefusalProblems(noRequest, DB_SYSTEM.A), JSON.stringify(noRequest)).toEqual([]);

  const unknownName = await api(page, TENANTS.A, 'admin', 'POST', approvedTablesPath(ctx.A.systemId), { table: 'f3 orders; drop table x' });
  expect(unknownName.status, 'a table name that is not a plain identifier').toBe(400);
  const notDb = await api(page, TENANTS.A, 'admin', 'POST', approvedTablesPath(readRun().seed.A.targetSystemId), { table: DB_TABLE });
  expect(notDb.status, 'approved tables belong to a DB system; the seeded API target is not one').toBe(400);

  const requested = await api(page, TENANTS.A, 'admin', 'POST', approvedTablesPath(ctx.A.systemId), { table: DB_TABLE });
  expect(requested.status, JSON.stringify(requested.data)).toBe(201);
  expect(requestedRowProblems(requested.data, ctx.A.systemId)).toEqual([]);
  ctx.A.rowId = requested.data.id;
  ctx.A.requestedBy = requested.data.requestedBy;

  const duplicate = await api(page, TENANTS.A, 'admin', 'POST', approvedTablesPath(ctx.A.systemId), { table: DB_TABLE.toUpperCase() });
  expect(duplicate.status, 'a live request exists for the table, whatever the letter case').toBe(409);

  const pending = await publish(page, TENANTS.A);
  expect(publishRefusalProblems(pending, DB_SYSTEM.A), `still refused while ${STATUS.pending}: ${JSON.stringify(pending)}`).toEqual([]);
});

test('4 tenant B cannot list, request or approve tenant A\'s table; A\'s list carries nothing of B', async ({ page }) => {
  ctx.B = await setupDbFlow(page, TENANTS.B, { storeCredential: false }); // B's vault stays empty: scenario 10
  const bRequest = await api(page, TENANTS.B, 'admin', 'POST', approvedTablesPath(ctx.B.systemId), { table: DB_TABLE });
  expect(bRequest.status, JSON.stringify(bRequest.data)).toBe(201);
  ctx.B.rowId = bRequest.data.id;

  const seesA = await api(page, TENANTS.B, 'approver', 'GET', approvedTablesPath(ctx.A.systemId));
  expect(seesA.status, 'A\'s system is not visible to B').toBe(404);
  expect(JSON.stringify(seesA.data)).not.toContain(DB_SYSTEM.A);
  const requestsOnA = await api(page, TENANTS.B, 'admin', 'POST', approvedTablesPath(ctx.A.systemId), { table: 'b_wants_a_system' });
  expect(requestsOnA.status).toBe(404);
  const approvesA = await api(page, TENANTS.B, 'approver', 'POST', approvedTablesPath(ctx.A.systemId, ctx.A.rowId, 'approve'));
  expect(approvesA.status, 'B\'s approver cannot approve A\'s row by A\'s system id').toBe(404);
  const approvesViaOwnSystem = await api(page, TENANTS.B, 'approver', 'POST', approvedTablesPath(ctx.B.systemId, ctx.A.rowId, 'approve'));
  expect(approvesViaOwnSystem.status, 'nor by pairing A\'s row id with B\'s own system id').toBe(404);

  const aList = await listOf(page, TENANTS.A, 'approver', ctx.A.systemId);
  expect(aList.map((row) => row.id)).toEqual([ctx.A.rowId]);
  expect(aList[0].status, 'A\'s row did not move').toBe(STATUS.pending);
  expect(leaksOf(aList, [ctx.B.systemId, ctx.B.rowId, DB_SYSTEM.B]), 'nothing of tenant B in A\'s table list').toEqual([]);
  const bList = await listOf(page, TENANTS.B, 'approver', ctx.B.systemId);
  expect(bList.map((row) => row.id)).toEqual([ctx.B.rowId]);
  expect(leaksOf(bList, [ctx.A.systemId, ctx.A.rowId, DB_SYSTEM.A]), 'nothing of tenant A in B\'s table list').toEqual([]);
});

test('5 a different tenant approver approves: APPROVED, decided by someone else; a second approve is 409 NOT_PENDING_APPROVAL', async ({ page }) => {
  await open(page);
  const approved = await api(page, TENANTS.A, 'approver', 'POST', approvedTablesPath(ctx.A.systemId, ctx.A.rowId, 'approve'));
  expect(approved.status, JSON.stringify(approved.data)).toBe(200);
  expect(decidedRowProblems(approved.data, STATUS.approved)).toEqual([]);
  expect(approved.data.requestedBy, 'the requester is unchanged').toBe(ctx.A.requestedBy);
  expect(approved.data.targetCurrent, 'the approval is bound to the system\'s current target').toBe(true);

  const again = await api(page, TENANTS.A, 'approver', 'POST', approvedTablesPath(ctx.A.systemId, ctx.A.rowId, 'approve'));
  expect(again.status).toBe(409);
  expect(refusalOf(again)).toBe(REFUSAL.notPending);
});

test('6 with the table approved the same publish passes (publish-time table + column check)', async ({ page }) => {
  await open(page);
  const published = await publish(page, TENANTS.A);
  const interfaceOf = must(published, 'A: publish the flow to the approved DB table');
  expect(String(interfaceOf.code ?? ''), 'the publish answer carries the generated interface code').toMatch(/^flow-/);
});

test('7 tenant A\'s APPROVED table does not make tenant B\'s same-named table usable, and B cannot revoke it', async ({ page }) => {
  await open(page);
  const bPublish = await publish(page, TENANTS.B);
  expect(publishRefusalProblems(bPublish, DB_SYSTEM.B), `B's ${DB_TABLE} is its own request, still pending: ${JSON.stringify(bPublish)}`).toEqual([]);

  const revoke = await api(page, TENANTS.B, 'approver', 'POST', approvedTablesPath(ctx.A.systemId, ctx.A.rowId, 'revoke'));
  expect(revoke.status, 'B\'s approver cannot revoke A\'s approval').toBe(404);
  const aList = await listOf(page, TENANTS.A, 'admin', ctx.A.systemId);
  expect(aList.map((row) => [row.id, row.status])).toEqual([[ctx.A.rowId, STATUS.approved]]);
  const bList = await listOf(page, TENANTS.B, 'admin', ctx.B.systemId);
  expect(bList.map((row) => [row.id, row.status]), 'B\'s own row is still pending').toEqual([[ctx.B.rowId, STATUS.pending]]);
  expect(leaksOf(bList, [ctx.A.systemId, ctx.A.rowId])).toEqual([]);
});

test('8 the approver revokes: REVOKED; a second revoke is 409 NOT_APPROVED', async ({ page }) => {
  await open(page);
  const revoked = await api(page, TENANTS.A, 'approver', 'POST', approvedTablesPath(ctx.A.systemId, ctx.A.rowId, 'revoke'));
  expect(revoked.status, JSON.stringify(revoked.data)).toBe(200);
  expect(revoked.data).toMatchObject({ id: ctx.A.rowId, status: STATUS.revoked });
  const again = await api(page, TENANTS.A, 'approver', 'POST', approvedTablesPath(ctx.A.systemId, ctx.A.rowId, 'revoke'));
  expect(again.status).toBe(409);
  expect(refusalOf(again)).toBe(REFUSAL.notApproved);
  const afterApprove = await api(page, TENANTS.A, 'approver', 'POST', approvedTablesPath(ctx.A.systemId, ctx.A.rowId, 'approve'));
  expect(afterApprove.status, 'a revoked row is never re-approved: a new request is needed').toBe(409);
  expect(refusalOf(afterApprove)).toBe(REFUSAL.notPending); // ApprovedTableService.decide: any non-"APPROVED" state answers NOT_PENDING_APPROVAL on APPROVE
});

test('9 test-connection to a DB system on a port off the DB allow-list is refused FAILED / DESTINATION_REFUSED with no grants reported', async ({ page }) => {
  await open(page);
  // this DB system is on the exception host and the exception (WireMock) port - not on platform.config.destinations.db-allowed-ports (exactly the target port)
  const offList = must(await api(page, TENANTS.A, 'admin', 'POST', '/api/v1/admin/systems', dbSystemBody('f3-db-offlist', 'F3 DB off the allow-list', exception)), 'A: DB system on the WireMock port');
  const probe = must(await api(page, TENANTS.A, 'admin', 'POST', `/api/v1/admin/systems/${offList.id}/test-connection`), 'A: test-connection');
  expect(probe).toMatchObject({ systemId: offList.id, type: 'DB', result: 'FAILED', reason: 'DESTINATION_REFUSED' });
  expect(probe.detail, 'a refused probe reports no grants').toBeNull();
});

// 10 The privilege probe's own refusals sit behind the port allow-list and the destination guard. B's DB system is on the
// target port (allowed) and B's vault holds no password: the probe stops before it dials (DbPrivilegeProbe NO_CREDENTIAL).
test('10 the privilege probe refuses a DB system with no stored password: FAILED / NO_CREDENTIAL, nothing granted is reported', async ({ page }) => {
  await open(page);
  const probe = must(await api(page, TENANTS.B, 'admin', 'POST', `/api/v1/admin/systems/${ctx.B.systemId}/test-connection`), 'B: test-connection');
  expect(probe).toMatchObject({ systemId: ctx.B.systemId, type: 'DB', result: 'FAILED', reason: 'NO_CREDENTIAL' });
  expect(probe.detail, 'a refused probe reports no grants').toBeNull();
});

// 11 Single-user workflow (V2_4_1420): the tenant admin who requested a table approves it THEMSELVES - 200 APPROVED,
// decidedBy == requestedBy (was 403 REQUESTER_IS_APPROVER) - and the flow to that table, refused before the approval,
// publishes. Its own DB system / flow / table, so tests 4-8 keep asserting A's first list exactly.
test('11 the requester approves their own request: 200 APPROVED, decided by the requester; a second approve is 409; the flow to the table publishes', async ({ page }) => {
  ctx.self = await setupDbFlow(page, TENANTS.A, { storeCredential: true, variant: 'self', table: DB_SELF_TABLE, login: ctx.A.login });
  const selfPublish = () => api(page, TENANTS.A, 'admin', 'POST', `/api/v1/admin/flows/${ctx.self.flowId}/publish`);
  const refused = await selfPublish();
  expect(publishRefusalProblems(refused, DB_SYSTEM.self, DB_SELF_TABLE), `refused before any request: ${JSON.stringify(refused)}`).toEqual([]);

  const requested = await api(page, TENANTS.A, 'admin', 'POST', approvedTablesPath(ctx.self.systemId), { table: DB_SELF_TABLE });
  expect(requested.status, JSON.stringify(requested.data)).toBe(201);
  const pending = await selfPublish();
  expect(publishRefusalProblems(pending, DB_SYSTEM.self, DB_SELF_TABLE), `still refused while ${STATUS.pending}: ${JSON.stringify(pending)}`).toEqual([]);

  const own = await api(page, TENANTS.A, 'admin', 'POST', approvedTablesPath(ctx.self.systemId, requested.data.id, 'approve'));
  expect(own.status, JSON.stringify(own.data)).toBe(200);
  expect(own.data).toMatchObject({ id: requested.data.id, status: STATUS.approved });
  expect(own.data.decidedBy, 'decided by the same person who requested it').toBe(own.data.requestedBy);
  const again = await api(page, TENANTS.A, 'admin', 'POST', approvedTablesPath(ctx.self.systemId, requested.data.id, 'approve'));
  expect(again.status).toBe(409);
  expect(refusalOf(again)).toBe(REFUSAL.notPending);

  const interfaceOf = must(await selfPublish(), 'A: publish the flow to the self-approved DB table');
  expect(String(interfaceOf.code ?? ''), 'the publish answer carries the generated interface code').toMatch(/^flow-/);
});

// 12 With the password in the vault and the narrow login on the target, the probe dials the real database and reports the
// account's grants on the table: exactly INSERT and SELECT (the width the approval step accepts).
test('12 the stored password reaches the narrow login on the target database: test-connection SUCCESS, grants INSERT and SELECT only', async ({ page }) => {
  await open(page);
  const probe = must(await api(page, TENANTS.A, 'admin', 'POST', `/api/v1/admin/systems/${ctx.A.systemId}/test-connection`), 'A: test-connection');
  expect(probe).toMatchObject({ systemId: ctx.A.systemId, type: 'DB', result: 'SUCCESS' });
  expect(probe.detail).toContain('INSERT');
  expect(probe.detail).toContain('SELECT');
  expect(probe.detail, 'the account is narrow').not.toContain('WIDE ACCOUNT');
});
