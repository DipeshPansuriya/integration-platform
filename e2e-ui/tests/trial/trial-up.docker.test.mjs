// Row 278 close-E7, T94 (T-162 gap 3) - the trial start-up script end to end, FAILING FIRST.
//
// Container group (like tests/openapi-contract.docker.test.mjs): startTrial() boots the REAL stack
// through boot-backend.mjs (its own PostgreSQL, the real Keycloak, the platform-app jar) under the
// one-docker-suite-at-a-time lock, seeds the trial tenant through the real API and serves the built
// console on 127.0.0.1:4173. This test then signs in AS THE TRIAL USER through the realm's real
// login form and does what the T-162 trial does by hand: find flow "orders", its one route to
// Target B and that route's mapping; save the example's three links with the contract binding; run its
// sample. stop() must release the lock and remove the postgres container.
//
// The password: a throwaway value generated here and handed in as IPASS_TRIAL_PASSWORD - in memory
// only, never printed or written (the banner must not carry it either).

import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import { sessionHeaders, sessionLogin } from '../../scripts/keycloak-harness.mjs';
import { TRIAL_PASSWORD_ENV, TRIAL_USERNAME, startTrial } from '../../scripts/trial-up.mjs';

const SAMPLE = { customerId: 'C-0001', orderId: 'O-777', amount: 42.5 };
const EXPECTED = { clientId: 'C-0001', referenceNo: 'O-777', invoiceValue: 42.5 };
const EXPRESSION = "{'clientId': customerId, 'referenceNo': orderId, 'invoiceValue': amount}";

const password = crypto.randomBytes(18).toString('base64url');
const printed = [];
let trial;
let session;

async function api(method, path, body) {
  const response = await fetch(new URL(path, trial.backendUrl), {
    method,
    headers: { ...sessionHeaders(session, method), ...(body ? { 'content-type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined
  });
  return { status: response.status, data: await response.json().catch(() => null) };
}

test.before(async () => {
  trial = await startTrial({ env: { [TRIAL_PASSWORD_ENV]: password }, print: (line) => printed.push(String(line)) });
  session = await sessionLogin(trial.backendUrl, { username: TRIAL_USERNAME, password });
});

test.after(async () => {
  if (trial) await trial.stop();
});

test('the banner names the console, the trial login and the password source - never the password', () => {
  const banner = printed.join('\n');
  assert.match(banner, /http:\/\/127\.0\.0\.1:4173/);
  assert.match(banner, /trial-configurator/);
  assert.match(banner, /password: from IPASS_TRIAL_PASSWORD/);
  assert.equal(banner.includes(password), false);
});

test('the trial user signs in through the realm form and the console answers on 127.0.0.1:4173', async () => {
  assert.equal(session.status, 200);
  const page = await fetch('http://127.0.0.1:4173/');
  assert.equal(page.status, 200);
  assert.match(await page.text(), /<div id="root">|<script/);
});

test('flow "orders" lists one route to Target B with a mapping, and both contracts are there', async () => {
  const flows = await api('GET', '/api/v1/admin/flows');
  assert.equal(flows.status, 200);
  const orders = flows.data.filter((flow) => flow.name === 'orders');
  assert.equal(orders.length, 1, `one flow "orders": ${JSON.stringify(flows.data)}`);

  const routes = await api('GET', `/api/v1/admin/flows/${orders[0].id}/routes`);
  assert.equal(routes.status, 200);
  assert.equal(routes.data.routes.length, 1);
  assert.equal(routes.data.routes[0].targetSystem, trial.targetSystem);
  assert.ok(routes.data.routes[0].mappingId, 'the route names its mapping');

  const contracts = await api('GET', '/api/v1/admin/contracts');
  assert.equal(contracts.status, 200);
  const of = (system, role) => contracts.data.contracts.find((c) => c.system === system && c.role === role);
  const source = of(trial.sourceSystem, 'SOURCE_REQUEST');
  const target = of(trial.targetSystem, 'TARGET_REQUEST');
  assert.ok(source && source.versions.some((v) => v.versionLabel === '1.0'), 'Customer A v1.0');
  assert.ok(target && target.versions.some((v) => v.versionLabel === '2.0' && v.status === 'APPROVED'), 'Target B v2.0 APPROVED');

  const mapping = await api('GET', `/api/v1/admin/routes/${routes.data.routes[0].id}/mapping`);
  assert.equal(mapping.status, 200);
  assert.deepEqual(mapping.data.fieldMappings, [], 'the mapping starts empty - the trial user builds it');
});

test('the trial user saves the three links with the binding and the sample run answers OK', async () => {
  const flows = await api('GET', '/api/v1/admin/flows');
  const flowId = flows.data.find((flow) => flow.name === 'orders').id;
  const route = (await api('GET', `/api/v1/admin/flows/${flowId}/routes`)).data.routes[0];
  const contracts = (await api('GET', '/api/v1/admin/contracts')).data.contracts;
  const versionId = (system, role, label) => contracts
    .find((c) => c.system === system && c.role === role).versions.find((v) => v.versionLabel === label).id;

  const saved = await api('PUT', `/api/v1/admin/routes/${route.id}/mapping`, {
    sourceContractVersionId: versionId(trial.sourceSystem, 'SOURCE_REQUEST', '1.0'),
    targetContractVersionId: versionId(trial.targetSystem, 'TARGET_REQUEST', '2.0'),
    fieldMappings: [
      { source: 'customerId', target: 'clientId' },
      { source: 'orderId', target: 'referenceNo' },
      { source: 'amount', target: 'invoiceValue' }
    ],
    compiledExpression: EXPRESSION
  });
  assert.equal(saved.status, 200, JSON.stringify(saved.data));

  const run = await api('POST', `/api/v1/admin/mappings/${route.mappingId}/test`, { sample: SAMPLE });
  assert.equal(run.status, 200, JSON.stringify(run.data));
  assert.equal(run.data.status, 'OK', JSON.stringify(run.data));
  assert.deepEqual(run.data.output, EXPECTED);
});

test('the lock holder note names trial-up while the trial is up', () => {
  const note = fs.readFileSync(`${trial.lockPath}/owner`, 'utf8');
  assert.match(note, new RegExp(`^trial-up pid=${process.pid} started=`));
});

test('stop() tears everything down and releases the lock', async () => {
  const { lockPath, pgName } = trial;
  await trial.stop();
  trial = null;
  assert.equal(fs.existsSync(lockPath), false, 'the docker-suite lock is released');
  const names = spawnSync('docker', ['ps', '-a', '--format', '{{.Names}}'], { encoding: 'utf8' }).stdout;
  assert.equal(names.split('\n').includes(pgName), false, 'the postgres container is gone');
  const refused = await fetch('http://127.0.0.1:4173/').then(() => 'answered', () => 'refused');
  assert.equal(refused, 'refused', 'the console is closed');
});
