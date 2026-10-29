// W5 (iPaaS Console plan): the mock transport, tested against the RAW wire shapes the real
// backend answers (the same shapes the api/* modules cast). The stub for globalThis.fetch is
// installed BEFORE the dynamic import because mock.ts binds `originalFetch` at module load -
// the pass-through check only proves a path was unhandled when the "real fetch" is this stub.
// One stub, one installMockApi(); resetMockState() between tests. No screen code, no React.

import test from 'node:test';
import assert from 'node:assert/strict';

/* The stub must own globalThis.fetch before the import: originalFetch binds at load. */
globalThis.fetch = async () => new Response('upstream answer', { status: 507 });

const { installMockApi, mockState, resetMockState } = await import('../../src/api/mock.ts');

installMockApi();

/* The helper mirrors what openapi-fetch v0.13 does: a Request for reads (method on the object,
   init undefined) and the init object for writes (method + JSON body there). */
const get = (path) => fetch(new Request(`http://api.local${path}`), undefined);
const send = (method, path, body) =>
  fetch(new Request(`http://api.local${path}`), { method, body: JSON.stringify(body ?? {}) });

test('off-switch paths pass through to the real fetch (the stub answers 507)', async () => {
  const res = await get('/api/v1/unknown');
  assert.equal(res.status, 507);
  assert.equal(await res.text(), 'upstream answer');
});

test('flows list answers the RAW array, detail by id, 404 for unknown', async () => {
  const list = await (await get('/api/v1/admin/flows')).json();
  assert.ok(Array.isArray(list));
  assert.deepEqual(
    list.map((f) => f.code),
    ['ORD-INTAKE', 'INV-SYNC', 'PAY-WEB']
  );
  const detail = await (await get('/api/v1/admin/flows/00000001-1111-4111-8111-000000000001')).json();
  assert.equal(detail.code, 'ORD-INTAKE');
  assert.equal(detail.status, 'PUBLISHED');
  const missing = await get('/api/v1/admin/flows/nope');
  assert.equal(missing.status, 404);
  assert.deepEqual(await missing.json(), { error: 'not found' });
});

test('POST /flows stores a row and answers {id}; the body arrives via init (the v0.13 write path)', async () => {
  const res = await send('POST', '/api/v1/admin/flows', { name: 'Test flow', sourceSystem: 'web-shop' });
  assert.equal(res.status, 200);
  const { id } = await res.json();
  assert.ok(mockState.flows.some((f) => f.id === id && f.name === 'Test flow'));
  assert.equal(mockState.flows.length, 4);
});

test('queue sorts by flow code then day and refuses ?denied=1 with NO_BOUND_USER', async () => {
  const rows = await (await get('/api/v1/admin/queue/summary')).json();
  assert.deepEqual(
    rows.map((r) => `${r.flowCode} ${r.day}`),
    ['INV-SYNC 2026-10-27', 'ORD-INTAKE 2026-10-27', 'ORD-INTAKE 2026-10-28']
  );
  const denied = await get('/api/v1/admin/queue/summary?denied=1');
  assert.equal(denied.status, 403);
  assert.deepEqual(await denied.json(), { refusal: 'NO_BOUND_USER' });
});

test('failures list filters and refuses ?denied=1 with ROLE_NOT_GRANTED', async () => {
  const rows = await (await get('/api/v1/admin/messages/failures?date=2026-10-28')).json();
  assert.deepEqual(
    rows.map((r) => r.trackingId),
    ['trk-0001', 'trk-0002']
  );
  const denied = await get('/api/v1/admin/messages/failures?denied=1');
  assert.equal(denied.status, 403);
  assert.deepEqual(await denied.json(), { refusal: 'ROLE_NOT_GRANTED' });
});

test('rule-sets: GET filters by flow, POST rejects empty content (T-121), POST valid stores', async () => {
  const flow1 = '00000001-1111-4111-8111-000000000001';
  const mine = await (await get(`/api/v1/admin/flows/${flow1}/rule-sets`)).json();
  assert.equal(mine.length, 1);
  const other = await (await get('/api/v1/admin/flows/other/rule-sets')).json();
  assert.deepEqual(other, []);

  const bad = await send('POST', `/api/v1/admin/flows/${flow1}/rule-sets`, { name: 'x' });
  assert.equal(bad.status, 400);
  assert.deepEqual(await bad.json(), {
    error: 'RULE_TABLE_INVALID',
    cell: { row: 0, column: 0 },
    reason: 'the table carries no cells'
  });

  const good = await send('POST', `/api/v1/admin/flows/${flow1}/rule-sets`, {
    name: 'routing-2',
    content: { rules: [{ when: 'country = NL', then: 'erp' }] }
  });
  assert.equal(good.status, 200);
  const stored = await good.json();
  assert.equal(stored.flowId, flow1);
  assert.equal(mockState.ruleSets.length, 2);
});

test('contracts: {contracts} envelope; import rejects bad JSON and stores a DRAFT version (T-160)', async () => {
  const env = await (await get('/api/v1/admin/contracts')).json();
  assert.ok(Array.isArray(env.contracts));
  assert.equal(env.contracts.length, 1);

  const bad = await send('POST', '/api/v1/admin/contracts/versions/import', { document: '{oops' });
  assert.equal(bad.status, 400);
  assert.deepEqual(await bad.json(), { error: 'the document is not valid JSON' });

  const good = await send('POST', '/api/v1/admin/contracts/versions/import', {
    system: 'web-shop',
    role: 'SOURCE_REQUEST',
    versionLabel: '1.2',
    document: '{"type":"object"}'
  });
  assert.equal(good.status, 200);
  const version = await good.json();
  assert.equal(version.status, 'DRAFT');
  assert.equal(version.versionLabel, '1.2');
  assert.equal(mockState.contracts[0].versions.length, 3);
});

test('contract version lifecycle: approve before submit refuses, submit then approve works', async () => {
  const id = '00000212-1111-4111-8111-000000000212'; // the 1.1 DRAFT fixture version
  const early = await send('POST', `/api/v1/admin/contracts/versions/${id}/approve`, {});
  assert.equal(early.status, 409);
  assert.deepEqual(await early.json(), { refusal: 'NOT_PENDING_APPROVAL' });
  const submitted = await (await send('POST', `/api/v1/admin/contracts/versions/${id}/submit`, {})).json();
  assert.equal(submitted.status, 'PENDING_APPROVAL');
  const approved = await (await send('POST', `/api/v1/admin/contracts/versions/${id}/approve`, {})).json();
  assert.equal(approved.status, 'APPROVED');
});

test('systems: duplicate code refuses 409, test-connection answers the named 429', async () => {
  const dup = await send('POST', '/api/v1/admin/systems', { code: 'web-shop', name: 'Web shop again' });
  assert.equal(dup.status, 409);
  assert.deepEqual(await dup.json(), { error: 'a system named web-shop is already registered' });

  const fresh = await (await send('POST', '/api/v1/admin/systems', { code: 'new', name: 'New one' })).json();
  assert.ok(mockState.systems.some((s) => s.id === fresh.id));

  const limited = await send('POST', '/api/v1/admin/systems/00000001/test-connection', {});
  assert.equal(limited.status, 429);
  assert.deepEqual(await limited.json(), {
    refusal: 'TEST_CONNECTION_RATE_LIMITED',
    error: 'the last attempt is still cooling down'
  });
});

test('timeline: unknown trackingId is 404, trk-0001 answers the fixture (RETRY_WAIT is a DeliveryState)', async () => {
  const missing = await get('/api/v1/admin/messages/trk-9999/timeline');
  assert.equal(missing.status, 404);
  const timeline = await (await get('/api/v1/admin/messages/trk-0001/timeline')).json();
  assert.equal(timeline.trackingId, 'trk-0001');
  assert.equal(timeline.deliveries.length, 1);
  assert.equal(timeline.deliveries[0].state, 'RETRY_WAIT');
});

test('operator replay/retry answer the RAW OperatorResult and resolve the failure row', async () => {
  const deliveryId = '00000301-1111-4111-8111-000000000301';
  const replay = await (await send('POST', `/api/v1/admin/messages/deliveries/${deliveryId}/replay`, {})).json();
  assert.deepEqual(replay, { outcome: 'ACCEPTED', deliveryId, state: 'PENDING' });
  assert.equal(mockState.failures.find((f) => f.deliveryId === deliveryId)?.failingStage, 'RESOLVED');

  const retry = await (await send('POST', `/api/v1/admin/messages/deliveries/${deliveryId}/retry`, {})).json();
  assert.deepEqual(retry, { outcome: 'ACCEPTED', deliveryId, state: 'RETRY_WAIT' });

  const unknown = await send('POST', '/api/v1/admin/messages/deliveries/nope/retry', {});
  assert.equal(unknown.status, 404);
});

test('GET /api/v1/me answers the roles array; mapping 404 for unknown route, saved answer otherwise', async () => {
  assert.deepEqual(await (await get('/api/v1/me')).json(), ['flow-admin']);

  const unknown = await get('/api/v1/admin/routes/nope/mapping');
  assert.equal(unknown.status, 404);
  const saved = await (await get('/api/v1/admin/routes/00000001-1111-4111-8111-000000000001/mapping')).json();
  assert.equal(saved.routeId, '00000001-1111-4111-8111-000000000001');
  assert.deepEqual(saved.fieldMappings, []);
});

test('activity paginates and the report answers its rows', async () => {
  const page = await (await get('/api/v1/activity?page=1&size=1')).json();
  assert.equal(page.page, 1);
  assert.equal(page.size, 1);
  assert.equal(page.hasMore, false);
  const report = await (await get('/api/v1/reports/delivery')).json();
  assert.equal(report.length, 1);
  assert.equal(report[0].successes, 9);
});

test('resetMockState() restores every mutated fixture', async () => {
  const flow1 = '00000001-1111-4111-8111-000000000001';
  await send('POST', '/api/v1/admin/flows', { name: 'scratch' });
  await send('POST', `/api/v1/admin/contracts/versions/00000212-1111-4111-8111-000000000212/submit`, {});
  await send('POST', '/api/v1/admin/messages/deliveries/00000301-1111-4111-8111-000000000301/replay', {});
  resetMockState();
  assert.equal(mockState.flows.length, 3);
  assert.equal(mockState.contracts[0].versions[1].status, 'DRAFT');
  assert.equal(mockState.failures[0].failingStage, 'DELIVER');
  assert.ok(mockState.ruleSets.some((r) => r.flowId === flow1));
  assert.deepEqual(Object.keys(mockState.mappings), [flow1]);
  assert.deepEqual(mockState.routes, [flow1]);
});