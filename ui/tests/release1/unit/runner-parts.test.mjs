// T174b: the Release 1 gate runner's pure parts - no docker, no network. Run: node --test tests/release1/unit/
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { runArgs } from '../../../scripts/release1/artemis.mjs';
import { REF, SAMPLE, mqCsvContract, routeFor, seedRefOrder } from '../../../scripts/release1/seed-ref-order.mjs';
import { SLOT_NAMES, acquireSlot } from '../../../scripts/release1/slot.mjs';
import { bearerEnv, bindSubjectSql, clientCredentialsToken } from '../../../scripts/release1/tokens.mjs';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 't174b-'));

test('slot: takes the first free slot and writes the owner note', async () => {
  const root = tmp();
  fs.mkdirSync(path.join(root, SLOT_NAMES[0]));
  const slot = await acquireSlot({ root, holder: 'aditya t174b' });
  assert.equal(path.basename(slot.path), SLOT_NAMES[1]);
  assert.match(fs.readFileSync(path.join(slot.path, 'owner'), 'utf8'), /^aditya t174b /);
});

test('slot: all held -> polls, never removes a foreign slot, takes the one that frees', async () => {
  const root = tmp();
  for (const name of SLOT_NAMES) {
    fs.mkdirSync(path.join(root, name));
    fs.writeFileSync(path.join(root, name, 'owner'), `arjun lane-${name}`);
  }
  let polls = 0;
  const slot = await acquireSlot({
    root, holder: 'aditya t174b', pollMs: 5,
    sleep: async () => { polls += 1; if (polls === 2) fs.rmSync(path.join(root, SLOT_NAMES[2]), { recursive: true }); }
  });
  assert.equal(path.basename(slot.path), SLOT_NAMES[2]);
  assert.equal(polls, 2);
  for (const name of [SLOT_NAMES[0], SLOT_NAMES[1], SLOT_NAMES[3]]) {
    assert.equal(fs.readFileSync(path.join(root, name, 'owner'), 'utf8'), `arjun lane-${name}`, 'a foreign slot is untouched');
  }
});

test('slot: gives up with the holders named once the wait window is over; release removes only our own', async () => {
  const root = tmp();
  for (const name of SLOT_NAMES) {
    fs.mkdirSync(path.join(root, name));
    fs.writeFileSync(path.join(root, name, 'owner'), 'arjun x');
  }
  let clock = 0;
  await assert.rejects(acquireSlot({ root, holder: 'aditya t174b', maxWaitMs: 10, now: () => clock, sleep: async () => { clock += 20; } }), /arjun x/);
  const free = tmp();
  const slot = await acquireSlot({ root: free, holder: 'aditya t174b' });
  fs.writeFileSync(path.join(slot.path, 'owner'), 'someone else');
  slot.release();
  assert.ok(fs.existsSync(slot.path), 'not ours any more: release leaves it');
  fs.writeFileSync(path.join(slot.path, 'owner'), 'aditya t174b again');
  slot.release();
  assert.ok(!fs.existsSync(slot.path));
});

test('tokens: the bind SQL is exact and refuses anything that is not a plain code, url, uuid or known role', () => {
  const [user, grant] = bindSubjectSql({ tenantCode: 'lane-d-1a2b3c', issuer: 'http://localhost:8081/realms/platform', subject: '0f8fad5b-d9cb-469f-a165-70867728950e', role: 'APPROVER' });
  assert.match(user, /INSERT INTO public\.app_user .* WHERE code='lane-d-1a2b3c'$/);
  assert.match(grant, /'APPROVER' FROM public\.app_user WHERE issuer=/);
  assert.throws(() => bindSubjectSql({ tenantCode: "x'; drop", issuer: 'http://a', subject: '0f8fad5b-d9cb-469f-a165-70867728950e', role: 'APPROVER' }), /plain code/);
  assert.throws(() => bindSubjectSql({ tenantCode: 'a', issuer: "http://a'", subject: '0f8fad5b-d9cb-469f-a165-70867728950e', role: 'APPROVER' }), /plain URL/);
  assert.throws(() => bindSubjectSql({ tenantCode: 'a', issuer: 'http://a', subject: 'nope', role: 'APPROVER' }), /uuid/);
  assert.throws(() => bindSubjectSql({ tenantCode: 'a', issuer: 'http://a', subject: '0f8fad5b-d9cb-469f-a165-70867728950e', role: 'ROOT' }), /role/);
});

test('tokens: E2E_BEARER_<ROLE> names; a blank token is refused; a failed grant names the client, not the secret', async () => {
  assert.deepEqual(Object.keys(bearerEnv({ CUSTOMER_A: 'a'.repeat(30), TENANT_B_ADMIN: 'b'.repeat(30) })), ['E2E_BEARER_CUSTOMER_A', 'E2E_BEARER_TENANT_B_ADMIN']);
  assert.throws(() => bearerEnv({ APPROVER: '' }), /APPROVER/);
  await assert.rejects(clientCredentialsToken('http://x', 'client-1', 'hunter2-secret', async () => ({ status: 401, json: async () => ({ error: 'unauthorized_client' }) })), (e) => /client-1/.test(e.message) && !/hunter2/.test(e.message));
  assert.equal(await clientCredentialsToken('http://x', 'c', 's', async () => ({ status: 200, json: async () => ({ access_token: 'T' }) })), 'T');
});

test('artemis: loopback-only publish, the login by env NAME only', () => {
  const args = runArgs({ name: 't174b-artemis', port: 61616 });
  assert.ok(args.includes('127.0.0.1:61616:61616'));
  assert.ok(args.includes('ARTEMIS_USER') && args.includes('ARTEMIS_PASSWORD'));
  assert.ok(!args.some((a) => /^ARTEMIS_(USER|PASSWORD)=/.test(a)));
  assert.throws(() => runArgs({ name: 'bad name;', port: 61616 }), /not plain/);
  assert.throws(() => runArgs({ name: 'ok', port: 0 }), /TCP port/);
});

test('seed: the CSV contract has the Java journey columns and header/CRLF layout', () => {
  const contract = mqCsvContract();
  assert.deepEqual(contract.columns.map((c) => c.name), ['order_no', 'customer', 'total']);
  assert.equal(contract.lineEnding, 'CRLF');
  assert.equal(contract.header, true);
});

test('seed: the source contract refuses a blank orderNo and a negative qty (SC-02 lists every violation); targets stay type-only', () => {
  const { properties, required } = REF.source.document;
  assert.deepEqual(required, ['orderNo', 'customer', 'qty']);
  assert.equal(properties.orderNo.minLength, 1);
  assert.equal(properties.qty.minimum, 0);
  assert.deepEqual(properties.customer, { type: 'string' });
  assert.deepEqual(REF.targetA.document.properties.qty, { type: 'number' });
});

test('seed: two targets (API 1.0, MQ 2.0), four-eyes on every contract and the flow, mapping bound to the right route', async () => {
  const calls = [];
  const call = async (login, method, p, body) => {
    calls.push({ login, method, p, body });
    if (p === '/api/v1/admin/systems') return { status: 201, data: { id: `sys-${body.code}` } };
    if (p === '/api/v1/admin/contracts/versions/import') return { status: 201, data: { id: `cv-${body.system}-${body.versionLabel}` } };
    if (p === '/api/v1/admin/flows' && method === 'POST') return { status: 201, data: { id: 'flow-1' } };
    if (p === '/api/v1/admin/flows/flow-1/routes') return { status: 200, data: { routes: [{ id: 'route-b', targetSystem: 'ref-target-b', mappingId: 'm-b' }, { id: 'route-a', targetSystem: 'ref-target-a', mappingId: 'm-a' }] } };
    if (p.endsWith('/test')) return { status: 200, data: { status: 'OK' } };
    if (p === '/api/v1/admin/flows/flow-1' && method === 'GET') return { status: 200, data: { code: 'flow-abc' } };
    if (p.endsWith('/source-credentials')) return { status: 201, data: { clientId: 'cid', secret: 'sec' } };
    return { status: 200, data: {} };
  };
  const seeded = await seedRefOrder({ call, customerId: 'CUST-A', apiBase: 'http://localhost:8089/target-a/', sourceBase: 'http://localhost:8089/ref-source/', mq: { host: 'localhost', port: 61616, queue: 'r1.orders.mq' } });
  assert.equal(seeded.interfaceCode, 'flow-abc');
  const approvals = calls.filter((c) => c.p.endsWith('/approve'));
  assert.equal(approvals.length, 4, 'three contracts and the flow');
  assert.ok(approvals.every((c) => c.login === 'approver'));
  assert.ok(calls.filter((c) => /submit|publish|import/.test(c.p)).every((c) => c.login === 'default'));
  const mapping = Object.fromEntries(calls.filter((c) => c.p.endsWith('/mapping')).map((c) => [c.p, c.body]));
  assert.equal(mapping['/api/v1/admin/routes/route-a/mapping'].targetContractVersionId, 'cv-ref-target-a-1.0');
  assert.equal(mapping['/api/v1/admin/routes/route-b/mapping'].targetContractVersionId, 'cv-ref-target-b-2.0');
  const mqSystem = calls.find((c) => c.body?.code === REF.targetB.system);
  assert.deepEqual(mqSystem.body.settings, { host: 'localhost', port: 61616, queue: 'r1.orders.mq', contract: mqCsvContract() });
  const samples = calls.filter((c) => c.p.endsWith('/test'));
  assert.deepEqual(samples.map((c) => c.p).sort(), ['/api/v1/admin/mappings/m-a/test', '/api/v1/admin/mappings/m-b/test']);
  assert.ok(samples.every((c) => c.body.sample === SAMPLE));
  const publishAt = calls.findIndex((c) => c.p.endsWith('/publish'));
  assert.ok(samples.every((c) => calls.indexOf(c) < publishAt), 'every sample run passes before publish');
});

test('seed: a refused step names itself and carries the status', async () => {
  await assert.rejects(seedRefOrder({ call: async () => ({ status: 409, data: { error: 'x' } }), customerId: 'c', apiBase: 'http://a/', sourceBase: 'http://a/', mq: { host: 'h', port: 1, queue: 'q' } }), /REF-ORDER source system: HTTP 409/);
  assert.equal(routeFor([{ id: 1 }, { id: 2 }], 'nope', 1).id, 2, 'by position when the route does not name its target');
});
