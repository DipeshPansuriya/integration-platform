// T214: the showcase flow NESTED-APPOINTMENT (two array levels flattened to one record per container) is registered by
// the API-driven seed, and registering it twice changes nothing. A stateful fake of the admin API stands in for the backend.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { NESTED, SAMPLE, seedNestedAppointment } from '../../../scripts/release1/seed-nested-appointment.mjs';

const fixture = (name) => readFileSync(new URL(`../../../scripts/release1/nested-appointment/${name}`, import.meta.url), 'utf8').trim();

/**
 * A tiny admin API: systems, contract versions (DRAFT -> PENDING_APPROVAL -> APPROVED), flows (DRAFT -> PUBLISHED) and routes live
 * in memory; every call is recorded. `dieAt` (a path suffix) makes the first call to it throw, like a run that died mid-way.
 */
function fakeAdmin({ dieAt } = {}) {
  const calls = [];
  const state = { systems: [], flows: [], contracts: [], imports: 0, dieAt };
  const version = (id) => state.contracts.flatMap((c) => c.versions).find((v) => v.id === id);
  const call = async (login, method, p, body) => {
    if (state.dieAt && p.endsWith(state.dieAt)) { state.dieAt = undefined; throw new Error('the run died here'); }
    calls.push({ login, method, p, body });
    if (p === '/api/v1/admin/systems' && method === 'GET') return { status: 200, data: state.systems };
    if (p === '/api/v1/admin/systems' && method === 'POST') { const s = { id: `sys-${body.code}`, code: body.code }; state.systems.push(s); return { status: 201, data: s }; }
    if (p === '/api/v1/admin/flows' && method === 'GET') return { status: 200, data: state.flows };
    if (p === '/api/v1/admin/flows' && method === 'POST') { const f = { id: 'flow-1', code: body.code, name: body.name, status: 'DRAFT' }; state.flows.push(f); return { status: 201, data: { id: f.id } }; }
    if (p === '/api/v1/admin/contracts' && method === 'GET') return { status: 200, data: { contracts: state.contracts } };
    if (p === '/api/v1/admin/contracts/versions/import') {
      state.imports += 1;
      const v = { id: `cv-${body.system}-${body.versionLabel}`, versionLabel: body.versionLabel, status: 'DRAFT' };
      state.contracts.push({ system: body.system, role: body.role, versions: [v] });
      return { status: 201, data: { id: v.id } };
    }
    if (/\/contracts\/versions\/[^/]+\/submit$/.test(p)) { version(p.split('/').at(-2)).status = 'PENDING_APPROVAL'; return { status: 200, data: {} }; }
    if (/\/contracts\/versions\/[^/]+\/approve$/.test(p)) { version(p.split('/').at(-2)).status = 'APPROVED'; return { status: 200, data: {} }; }
    if (p === '/api/v1/admin/flows/flow-1/routes') return { status: 200, data: { routes: [{ id: 'route-1', targetSystem: NESTED.target.system, mappingId: 'm-1' }] } };
    if (p.endsWith('/test')) return { status: 200, data: { status: 'OK' } };
    if (p === '/api/v1/admin/flows/flow-1/publish') { state.flows[0].status = 'PUBLISHED'; return { status: 200, data: {} }; }
    if (p === '/api/v1/admin/flows/flow-1' && method === 'GET') return { status: 200, data: { code: 'flow-gen' } };
    if (p.endsWith('/source-credentials')) return { status: 201, data: { clientId: 'cid', secret: 'sec' } };
    return { status: 200, data: {} };
  };
  return { call, calls, state };
}
const args = (call) => ({ call, customerId: 'CUST-A', apiBase: 'http://wiremock:8089/nested-target/', sourceBase: 'http://wiremock:8089/nested-source/' });
const writes = (calls) => calls.filter((c) => c.method !== 'GET');

test('fixtures: the seed carries the sample and the expression files, nothing retyped', () => {
  assert.deepEqual(SAMPLE, JSON.parse(fixture('sample.json')));
  assert.equal(NESTED.target.expression, fixture('expression.jsonata'));
  assert.equal(SAMPLE.VehicleList[0].ContainerList.length, 1, 'two array levels in the sample');
});

test('contracts: the source describes both array levels, the target one flat record of 20 fields', () => {
  const vehicle = NESTED.source.document.properties.VehicleList;
  assert.equal(vehicle.type, 'array');
  assert.equal(vehicle.items.properties.ContainerList.type, 'array');
  assert.equal(vehicle.items.properties.ContainerList.items.properties.NoOfPackages.type, 'number');
  assert.equal(vehicle.items.properties.DriverPortId.type, 'string', 'DriverPortId travels as a string');
  assert.equal(Object.keys(NESTED.target.document.properties).length, 20);
  assert.equal(NESTED.target.document.properties.appointmentDate.format, 'date-time');
  assert.equal(NESTED.target.document.properties.containerNo.type, 'string');
  assert.equal(NESTED.target.document.properties.transactionId.type, 'number');
});

test('contracts: only the keys that always exist are required - sparse input is valid, the target needs just appointmentId and containerNo', () => {
  const { source, target } = NESTED;
  const container = source.document.properties.VehicleList.items.properties.ContainerList.items;
  assert.deepEqual(source.document.required, ['AppointmentId', 'VehicleList']);
  assert.equal(source.document.properties.VehicleList.items.required, undefined, 'a vehicle may have no container list or slot');
  assert.deepEqual(container.required, ['ContainerNo']);
  assert.deepEqual(target.document.required, ['appointmentId', 'containerNo']);
  // every link's source and target field exists in its contract
  for (const [, to] of target.links) assert.ok(to in target.document.properties, to);
  // the always-required source keys are linked to the always-required target keys
  const linked = Object.fromEntries(target.links);
  assert.equal(linked.AppointmentId, 'appointmentId');
  assert.equal(linked['VehicleList.ContainerList.ContainerNo'], 'containerNo');
});

test('seed: a source and an API target, four-eyes on both contracts and the flow, the expression bound, a passing sample run before publish', async () => {
  const { call, calls } = fakeAdmin();
  const seeded = await seedNestedAppointment(args(call));
  assert.equal(seeded.skipped, false);
  assert.equal(seeded.resumed, false);
  assert.equal(seeded.interfaceCode, 'flow-gen');
  assert.deepEqual(seeded.credential, { clientId: 'cid', secret: 'sec' });
  const approvals = calls.filter((c) => c.p.endsWith('/approve'));
  assert.equal(approvals.length, 3, 'two contracts and the flow');
  assert.ok(approvals.every((c) => c.login === 'approver'));
  assert.ok(calls.filter((c) => /submit|publish|import/.test(c.p)).every((c) => c.login === 'default'));
  const target = calls.find((c) => c.method === 'POST' && c.body?.code === NESTED.target.system);
  assert.deepEqual([target.body.type, target.body.direction, target.body.settings], ['API', 'OUTBOUND', { baseUrl: 'http://wiremock:8089/nested-target/' }]);
  const mapping = calls.find((c) => c.p === '/api/v1/admin/routes/route-1/mapping');
  assert.equal(mapping.body.compiledExpression, fixture('expression.jsonata'));
  assert.equal(mapping.body.sourceContractVersionId, 'cv-nested-source-1.0');
  assert.equal(mapping.body.targetContractVersionId, 'cv-nested-target-1.0');
  const sample = calls.find((c) => c.p === '/api/v1/admin/mappings/m-1/test');
  assert.deepEqual(sample.body.sample, SAMPLE);
  assert.ok(calls.indexOf(sample) < calls.findIndex((c) => c.p.endsWith('/publish')), 'the sample run passes before publish');
});

test('seed: running it twice registers the flow once - the second run writes nothing', async () => {
  const { call, calls, state } = fakeAdmin();
  await seedNestedAppointment(args(call));
  const afterFirst = calls.length;
  const second = await seedNestedAppointment(args(call));
  assert.equal(second.skipped, true);
  assert.equal(second.flowId, 'flow-1');
  assert.equal(second.credential, null, 'a credential secret is shown once, never re-issued by a re-run');
  assert.deepEqual(writes(calls.slice(afterFirst)), []);
  assert.equal(state.flows.length, 1);
  assert.equal(state.systems.length, 2);
  assert.equal(state.imports, 2);
});

test('seed: systems that already exist are reused, not created again, when the flow is missing', async () => {
  const { call, calls, state } = fakeAdmin();
  state.systems.push({ id: 'sys-nested-source', code: NESTED.source.system }, { id: 'sys-nested-target', code: NESTED.target.system });
  await seedNestedAppointment(args(call));
  assert.equal(calls.filter((c) => c.method === 'POST' && c.p === '/api/v1/admin/systems').length, 0);
  assert.equal(state.systems.length, 2);
  assert.equal(state.flows.length, 1);
});

test('seed: a draft flow left by a run that died is RESUMED to published, never reported skipped', async () => {
  const admin = fakeAdmin({ dieAt: '/flows/flow-1/submit' });
  await assert.rejects(seedNestedAppointment(args(admin.call)), /the run died here/);
  assert.equal(admin.state.flows[0].status, 'DRAFT');
  const resumed = await seedNestedAppointment(args(admin.call));
  assert.equal(resumed.skipped, false);
  assert.equal(resumed.resumed, true);
  assert.equal(admin.state.flows[0].status, 'PUBLISHED');
  assert.equal(admin.state.flows.length, 1, 'no second flow');
  assert.equal(admin.state.imports, 2, 'the approved contracts are reused, not imported again');
  assert.deepEqual(resumed.credential, { clientId: 'cid', secret: 'sec' }, 'the credential the dead run never issued');
  const third = await seedNestedAppointment(args(admin.call));
  assert.equal(third.skipped, true);
});

test('seed: a run that died before the flow existed (contracts imported, one still a draft) resumes without importing again', async () => {
  const admin = fakeAdmin();
  const real = admin.call;
  let died = false;
  const call = async (login, method, p, body) => { if (!died && method === 'POST' && p === '/api/v1/admin/flows') { died = true; throw new Error('the run died here'); } return real(login, method, p, body); };
  await assert.rejects(seedNestedAppointment(args(call)), /the run died here/);
  assert.equal(admin.state.imports, 2);
  assert.equal(admin.state.flows.length, 0);
  const again = await seedNestedAppointment(args(call));
  assert.equal(again.skipped, false);
  assert.equal(admin.state.imports, 2, 'no second import of contract 1.0');
  assert.equal(admin.state.flows[0].status, 'PUBLISHED');
});

test('seed: a contract left pending approval is approved, a draft one is submitted and approved', async () => {
  const { call, calls, state } = fakeAdmin();
  state.contracts.push({ system: NESTED.source.system, role: 'SOURCE_REQUEST', versions: [{ id: 'v-src', versionLabel: '1.0', status: 'PENDING_APPROVAL' }] },
    { system: NESTED.target.system, role: 'TARGET_REQUEST', versions: [{ id: 'v-tgt', versionLabel: '1.0', status: 'DRAFT' }] });
  await seedNestedAppointment(args(call));
  assert.equal(state.imports, 0);
  assert.ok(calls.some((c) => c.p === '/api/v1/admin/contracts/versions/v-src/approve'));
  assert.ok(!calls.some((c) => c.p === '/api/v1/admin/contracts/versions/v-src/submit'));
  assert.ok(calls.some((c) => c.p === '/api/v1/admin/contracts/versions/v-tgt/submit'));
  assert.ok(calls.some((c) => c.p === '/api/v1/admin/contracts/versions/v-tgt/approve'));
  assert.equal(state.contracts.flatMap((c) => c.versions).every((v) => v.status === 'APPROVED'), true);
});

test('seed: a refused step names itself and carries the status', async () => {
  const { call } = fakeAdmin();
  const refusing = async (login, method, p, body) => (p === '/api/v1/admin/contracts/versions/import' ? { status: 409, data: { error: 'x' } } : call(login, method, p, body));
  await assert.rejects(seedNestedAppointment(args(refusing)), /NESTED-APPOINTMENT import .*: HTTP 409/);
});
