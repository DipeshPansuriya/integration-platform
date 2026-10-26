// T215: the LIVE proof runner of the NESTED-APPOINTMENT showcase flow (T214). It mirrors run-release1-journey.mjs:
//   1. builds the console (before the lock: no docker needed for that);
//   2. takes the PC-wide docker lock (.docker-suite.lock beside the main clone: mkdir + owner note, never removes another
//      owner's lock; polls up to 20 min, then reports) and keeps its note fresh;
//   3. boots the base-suite backend once (Keycloak, Postgres, Kafka, WireMock on the exception port) - boot-backend.mjs, unchanged;
//   4. adds the WireMock stub for /nested-target/, seeds NESTED-APPOINTMENT through the admin API, mints the source bearer in memory;
//   5. seeds a SECOND time and records whether anything changed (the spec asserts it);
//   6. serves the built console and runs playwright.nested.config.mjs twice: first the NEGATIVE CONTROL (one expected value
//      broken - must go RED), then the real run (must be GREEN);
//   7. tears everything down and releases the lock, whatever happened.
// Usage: IPASS_BS_EXCEPTION_PROFILE=showcase IPASS_BS_EXCEPTION_HOST=localhost
//        IPASS_BS_EXCEPTION_HOST_VAR=PLATFORM_CONFIG_DESTINATIONS_SHOWCASE_LOCAL_HOSTS IPASS_BS_EXCEPTION_PORT=8089
//        node scripts/run-nested-appointment-live.mjs <log-file> [--no-negative-control]
// `--print-plan` prints the steps and the env NAMES and starts nothing. Secrets stay in this process's memory.
import { spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { installSignalCleanup, makeCleanup, readStats } from './release1/journey-run.mjs';
import { seedNestedAppointment } from './release1/seed-nested-appointment.mjs';
import { acquireSlot } from './release1/slot.mjs';
import { clientCredentialsToken } from './release1/tokens.mjs';
import { baseSuiteBootOptions } from '../tests/base-suite/lib/boot-options.mjs';
import { exceptionSettings } from '../tests/base-suite/lib/settings.mjs';
import { TENANTS } from '../tests/base-suite/lib/tenants.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const UI = path.resolve(HERE, '..');
const REPO = path.resolve(UI, '..');
const CONSOLE = path.join(REPO, 'ui');
const SEED_DIR = path.join(HERE, 'release1', 'nested-appointment');

const ENV_NAMES = ['CONSOLE_URL', 'E2E_BACKEND_URL', 'E2E_WIREMOCK_ADMIN_URL', 'E2E_INTERFACE_CODE', 'E2E_BEARER_SOURCE', 'E2E_ADMIN_USER', 'E2E_ADMIN_PASSWORD',
  'E2E_RESEED_JSON', 'E2E_SEED_DIR', 'E2E_PROOF_DIR', 'E2E_NEGATIVE_CONTROL (negative-control pass only)'];

if (process.argv.includes('--print-plan')) {
  console.log('run-nested-appointment-live: build console -> docker lock -> boot backend -> nested-target stub -> seed NESTED-APPOINTMENT -> second seed (idempotence) -> serve console -> playwright (negative control RED, then real GREEN) -> teardown');
  console.log(`env names wired: ${ENV_NAMES.join(', ')}`);
  process.exit(0);
}

const logFile = process.argv[2];
if (!logFile || logFile.startsWith('--')) throw new Error('usage: run-nested-appointment-live.mjs <log-file> [--no-negative-control] | --print-plan');
const withNegative = !process.argv.includes('--no-negative-control');
const log = (message) => fs.appendFileSync(logFile, `${new Date().toISOString()} ${message}\n`);

const HOLDER = 'aditya t215-nested-live';
const common = spawnSync('git', ['rev-parse', '--path-format=absolute', '--git-common-dir'], { cwd: REPO, encoding: 'utf8', windowsHide: true });
if (common.status !== 0 || !common.stdout.trim()) throw new Error('runner: git could not name the main clone (the docker lock sits beside it)');
const lockRoot = path.dirname(path.dirname(common.stdout.trim()));

const cleanup = makeCleanup(log);
installSignalCleanup(cleanup, { log });
let exitCode = 2;

/** One Playwright pass over the nested spec; returns { code, stats, out }. */
function playwrightPass({ name, env, grep }) {
  const jsonReport = path.join(os.tmpdir(), `t215-${name}-${process.pid}.json`);
  const npx = process.platform === 'win32' ? 'npx.cmd' : 'npx';
  const args = ['playwright', 'test', '-c', 'playwright.nested.config.mjs', '--reporter=line,json', ...(grep ? ['-g', grep] : [])];
  return new Promise((resolve) => {
    const child = spawn(npx, args, {
      cwd: UI, shell: process.platform === 'win32', stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, ...env, NESTED_OUT: path.join(os.tmpdir(), `t215-out-${name}-${process.pid}`), PLAYWRIGHT_JSON_OUTPUT_NAME: jsonReport }
    });
    let out = '';
    const sink = fs.createWriteStream(logFile, { flags: 'a' });
    sink.write(`\n===== playwright pass: ${name} =====\n`);
    for (const stream of [child.stdout, child.stderr]) stream.on('data', (chunk) => { out += chunk; sink.write(chunk); });
    child.on('close', (code) => { sink.end(); const stats = readStats(jsonReport); fs.rmSync(jsonReport, { force: true }); resolve({ code, stats, out }); });
  });
}

try {
  // the console's login config, then the build (an unbuilt console is this run's own red) - no docker needed, so before the lock
  fs.mkdirSync(path.join(CONSOLE, 'public'), { recursive: true });
  fs.writeFileSync(path.join(CONSOLE, 'public', 'console-config.json'), JSON.stringify({ logins: ['approver'] }, null, 2));
  const build = spawnSync('npm', ['run', 'build'], { cwd: CONSOLE, encoding: 'utf8', shell: process.platform === 'win32' });
  if (build.status !== 0) throw new Error(`the console build failed:\n${String(build.stdout).slice(-2000)}\n${String(build.stderr).slice(-2000)}`);
  log('console built');

  const slot = await acquireSlot({ root: lockRoot, holder: HOLDER, slotNames: ['.docker-suite.lock'], pollMs: 30000, maxWaitMs: 20 * 60 * 1000, log });
  cleanup.add('docker lock', () => slot.release());
  const keepFresh = setInterval(() => { try { slot.refresh(); } catch (e) { log(`lock refresh: ${e.message}`); } }, 10 * 60 * 1000);
  cleanup.add('refresh timer', () => clearInterval(keepFresh));

  // boot-backend takes its own lock: a private one (the PC-wide one is the one WE hold)
  const inner = path.join(os.tmpdir(), 't215-inner.lock');
  fs.rmSync(inner, { recursive: true, force: true });
  process.env.DOCKER_SUITE_LOCK = inner;
  const { default: bootBackend } = await import('./boot-backend.mjs');
  const { startConsole } = await import(new URL('../tests/screens/console-server.mjs', import.meta.url).href);

  const settings = exceptionSettings(process.env);
  const options = baseSuiteBootOptions(settings, { lockHolder: HOLDER });
  const backend = await bootBackend(options);
  cleanup.add('backend', () => backend.teardown());
  const wiremock = backend.services.wiremock;
  const base = `http://${settings.host}:${wiremock.port}`;
  log(`backend ${backend.baseUrl}; wiremock ${wiremock.url}; tenant A ${backend.tenantCode}`);

  const stub = await fetch(`${wiremock.adminUrl}/mappings`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ request: { method: 'POST', urlPathPattern: '/nested-target/.*' }, response: { status: 200 }, priority: 10 })
  });
  if (stub.status !== 201) throw new Error(`runner: WireMock nested-target stub answered ${stub.status}`);

  const call = (login, method, apiPath, body) => backend.call(login, method, apiPath, body);
  const seedArgs = { call, customerId: TENANTS.A.customerId, apiBase: `${base}/nested-target/`, sourceBase: `${base}/nested-source/` };
  const seeded = await seedNestedAppointment(seedArgs);
  if (seeded.skipped || !seeded.credential) throw new Error('runner: the first seed found the flow already there - the boot is not fresh');
  log(`NESTED-APPOINTMENT seeded: interface ${seeded.interfaceCode}, flow ${seeded.flowId}`);
  const bearer = await clientCredentialsToken(backend.issuer, seeded.credential.clientId, seeded.credential.secret);

  // idempotence: the state before and after a SECOND seed run (ids, versions, statuses - everything the admin API lists)
  const snapshot = async () => {
    const [flows, systems, contracts] = await Promise.all(['flows', 'systems', 'contracts'].map(async (what) => (await call('default', 'GET', `/api/v1/admin/${what}`)).data));
    const digest = crypto.createHash('sha256').update(JSON.stringify([flows, systems, contracts])).digest('hex');
    return { digest, flowsWithCode: flows.filter((flow) => flow.code === 'NESTED-APPOINTMENT' || flow.name === 'NESTED-APPOINTMENT').length, flows: flows.length, systems: systems.length, contractFamilies: (contracts.contracts ?? []).length };
  };
  const before = await snapshot();
  const again = await seedNestedAppointment(seedArgs);
  const after = await snapshot();
  const reseed = { skipped: again.skipped, credential: again.credential, flowIdSame: again.flowId === seeded.flowId, before, after };
  log(`second seed: skipped=${again.skipped} credential=${again.credential === null ? 'none' : 'ISSUED'} state ${before.digest === after.digest ? 'unchanged' : 'CHANGED'}`);

  const consoleServer = await startConsole(backend.baseUrl, backend.sessions);
  cleanup.add('console', () => consoleServer.close());
  log(`console ${consoleServer.url}`);
  const proofDir = fs.mkdtempSync(path.join(os.tmpdir(), 't215-proof-'));
  const env = {
    CONSOLE_URL: consoleServer.url,
    E2E_BACKEND_URL: backend.baseUrl,
    E2E_WIREMOCK_ADMIN_URL: wiremock.adminUrl,
    E2E_INTERFACE_CODE: seeded.interfaceCode,
    E2E_BEARER_SOURCE: bearer,
    E2E_ADMIN_USER: backend.users.default.username,
    E2E_ADMIN_PASSWORD: backend.users.default.password,
    E2E_RESEED_JSON: JSON.stringify(reseed),
    E2E_SEED_DIR: SEED_DIR,
    E2E_PROOF_DIR: proofDir
  };
  const missing = Object.entries(env).filter(([, value]) => !value).map(([name]) => name);
  if (missing.length) throw new Error(`runner: the boot did not provide ${missing.join(', ')}`);

  let negativeOk = true;
  if (withNegative) {
    // the control: ONE expected value broken (containerNo 26 -> 27) - the sample test must go RED and name the field
    const red = await playwrightPass({ name: 'negative-control', env: { ...env, E2E_NEGATIVE_CONTROL: '1' }, grep: 'sample.gives.ONE.record' });
    negativeOk = red.code !== 0 && (red.stats?.unexpected ?? 0) >= 1 && /containerNo/.test(red.out);
    log(`NEGATIVE CONTROL: ${negativeOk ? 'RED as required' : 'NOT RED - the assertion does not bite'} (exit ${red.code}, unexpected ${red.stats?.unexpected ?? '?'})`);
    console.log(`negative control (containerNo broken): ${negativeOk ? 'RED as required' : 'NOT RED - assertion does not bite'}`);
  }
  const green = await playwrightPass({ name: 'real', env });
  const stats = green.stats ?? {};
  const line = `REAL RUN: exit ${green.code}, expected(passed) ${stats.expected ?? '?'}, unexpected ${stats.unexpected ?? '?'}, flaky ${stats.flaky ?? '?'}, skipped ${stats.skipped ?? '?'}; negative control ${withNegative ? (negativeOk ? 'RED' : 'NOT RED') : 'not run'}`;
  log(line);
  console.log(line);
  console.log(`screen proof folder (this machine, temp): ${proofDir}`);
  exitCode = green.code === 0 && negativeOk ? 0 : 1;
} catch (e) {
  log(`RUNNER FAILED: ${e.message}`);
  console.error(e.message);
  exitCode = 2;
} finally {
  await cleanup.run();
  log('lock released');
}
process.exitCode = exitCode;
