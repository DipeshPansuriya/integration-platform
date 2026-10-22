// T174 / T174b (lane t174-sc): the Release 1 gate runner.
//   1. takes a real docker-suite slot (.docker-suite.lock, -1, -2, -3; polls, never steals) and keeps its note fresh;
//   2. starts one Artemis (loopback, run-scoped login) and boots the base-suite backend once (tenants A and B, the
//      WireMock on the exception port, Kafka) with the MQ port added to the dev-only showcase ports;
//   3. seeds REF-ORDER through the admin API (two targets, four-eyes), mints the four bearer tokens in memory;
//   4. runs platform-e2e Release1ScenariosContainerTest against it with -De2e.* and E2E_BEARER_*;
//   5. tears everything down and releases the slot, whatever happened.
// Usage: IPASS_BS_EXCEPTION_* (see tests/base-suite/lib/settings.mjs) node scripts/run-release1-gate.mjs <log-file>
// Secrets stay in this process's memory; the log carries only names, statuses and the Maven output.
import { spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startArtemis } from './release1/artemis.mjs';
import { seedRefOrder } from './release1/seed-ref-order.mjs';
import { SLOT_NAMES, acquireSlot } from './release1/slot.mjs';
import { bearerEnv, bindSubjectSql, clientCredentialsToken } from './release1/tokens.mjs';
import { baseSuiteBootOptions } from '../tests/base-suite/lib/boot-options.mjs';
import { exceptionSettings } from '../tests/base-suite/lib/settings.mjs';
import { TENANTS, TENANT_B_PLAN } from '../tests/base-suite/lib/tenants.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..');
const logFile = process.argv[2];
if (!logFile) throw new Error('usage: run-release1-gate.mjs <log-file>');
const log = (message) => fs.appendFileSync(logFile, `${new Date().toISOString()} ${message}\n`);

const MQ_PORT = 61616;
const MQ_QUEUE = 'r1.orders.mq';
const ARTEMIS_NAME = 't174c-artemis';
const HOLDER = 'aditya t174c';

// the slot root is the main clone's parent, as boot-backend.mjs and check-container-exclusion.sh derive it
const common = spawnSync('git', ['rev-parse', '--path-format=absolute', '--git-common-dir'], { cwd: REPO, encoding: 'utf8', windowsHide: true });
const slotRoot = path.dirname(path.dirname(common.stdout.trim()));

const slot = await acquireSlot({ root: slotRoot, holder: HOLDER, slotNames: ['.docker-suite.lock-3'], pollMs: 60000, log });
const keepFresh = setInterval(() => slot.refresh(), 10 * 60 * 1000); // other lock takers call a note > 60 min old stale
let backend;
let artemis;
try {
  // boot-backend takes its own lock at this path: a private one inside our slot, never a shared one
  const inner = path.join(os.tmpdir(), 't174c-inner.lock');
  fs.rmSync(inner, { recursive: true, force: true });
  process.env.DOCKER_SUITE_LOCK = inner;
  const { default: bootBackend, psql } = await import('./boot-backend.mjs');

  spawnSync('docker', ['rm', '-f', ARTEMIS_NAME], { encoding: 'utf8' });
  artemis = await startArtemis({ name: ARTEMIS_NAME, port: MQ_PORT, queue: MQ_QUEUE, user: `r1-${crypto.randomUUID()}`, password: crypto.randomUUID(), base: process.env });
  log(`artemis ${ARTEMIS_NAME} up on 127.0.0.1:${MQ_PORT}, queue ${MQ_QUEUE} created`);

  const settings = exceptionSettings(process.env);
  const options = baseSuiteBootOptions(settings, { lockHolder: HOLDER });
  const portsVar = Object.keys(options.appEnv).find((name) => /SHOWCASE_LOCAL_PORTS/i.test(name));
  if (!portsVar) throw new Error('runner: the showcase ports variable is not among the exception app settings');
  options.appEnv = { ...options.appEnv, [portsVar]: `${settings.port},${MQ_PORT}` }; // WireMock + Artemis, both loopback

  backend = await bootBackend(options);
  const wiremock = backend.services.wiremock;
  const base = `http://${settings.host}:${wiremock.port}`;
  log(`backend ${backend.baseUrl}; wiremock ${wiremock.url}; tenant A ${backend.tenantCode}`);

  // target A's default answer (the scenarios add their own, higher-priority stubs on top)
  const stub = await fetch(`${wiremock.adminUrl}/mappings`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ request: { method: 'POST', urlPathPattern: '/target-a/.*' }, response: { status: 200 }, priority: 10 })
  });
  if (stub.status !== 201) throw new Error(`runner: WireMock default stub answered ${stub.status}`);

  const seeded = await seedRefOrder({
    call: (login, method, apiPath, body) => backend.call(login, method, apiPath, body),
    customerId: TENANTS.A.customerId, apiBase: `${base}/target-a/`, sourceBase: `${base}/ref-source/`,
    mq: { host: settings.host, port: MQ_PORT, queue: MQ_QUEUE }
  });
  log(`REF-ORDER seeded: interface ${seeded.interfaceCode}, flow ${seeded.flowId}`);

  const tenantB = backend.extraTenants[TENANT_B_PLAN.key];
  const tokens = { CUSTOMER_A: await clientCredentialsToken(backend.issuer, seeded.credential.clientId, seeded.credential.secret) };
  for (const [role, bearerRole, tenantCode, grant] of [
    ['TENANT_ADMIN', 'TENANT_ADMIN', backend.tenantCode, 'TENANT_ADMIN'],
    ['APPROVER', 'APPROVER', backend.tenantCode, 'APPROVER'],
    ['TENANT_B_ADMIN', 'TENANT_B_ADMIN', tenantB.code, 'TENANT_ADMIN']
  ]) {
    const client = await backend.createServiceClient(`t174c-${bearerRole.toLowerCase().replace(/_/g, '-')}`);
    for (const sql of bindSubjectSql({ tenantCode, issuer: backend.issuer, subject: client.subject, role: grant })) psql(backend.pgName, sql);
    tokens[role] = await clientCredentialsToken(backend.issuer, client.clientId, client.secret);
  }
  log(`tokens minted for ${Object.keys(tokens).join(', ')}`);

  const mvnw = process.platform === 'win32' ? `"${path.join(REPO, 'mvnw.cmd')}"` : './mvnw';
  const code = await new Promise((resolve) => {
    const child = spawn(mvnw, ['-o', '-Pcontainer', '-pl', 'platform-e2e', 'test', '-Dtest=Release1ScenariosContainerTest',
      `-De2e.baseUrl=${backend.baseUrl}`, `-De2e.interfaceCode=${seeded.interfaceCode}`, `-De2e.tenantACode=${backend.tenantCode}`, `-De2e.tenantBCode=${tenantB.code}`, `-De2e.pgContainer=${backend.pgName}`,
      `-De2e.wiremockUrl=http://127.0.0.1:${wiremock.port}`, `-De2e.artemisContainer=${ARTEMIS_NAME}`],
    { cwd: REPO, shell: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, ...bearerEnv(tokens) } });
    const out = fs.createWriteStream(logFile, { flags: 'a' });
    child.stdout.pipe(out, { end: false });
    child.stderr.pipe(out, { end: false });
    child.on('close', (c) => { out.end(); resolve(c); });
  });
  log(`mvn exit ${code}`);
  console.log(`mvn exit ${code}`);
} catch (e) {
  log(`RUNNER FAILED: ${e.message}`);
  console.error(e.message);
  process.exitCode = 2;
} finally {
  clearInterval(keepFresh);
  if (backend) await backend.teardown().catch((e) => log(`backend teardown: ${e.message}`));
  if (artemis) artemis.stop();
  slot.release();
  log('slot released');
}
