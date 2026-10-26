// L28 (row 372): the Release 1 BROWSER journey runner (the T-145 leg). It is NOT the Release 1 gate: the gate
// (run-release1-gate.mjs, Maven Release1ScenariosContainerTest) is the frozen proof and is not touched.
//   1. takes a real docker-suite slot (polls, never steals) and keeps its note fresh;
//   2. starts one Artemis (loopback, run-scoped login) and boots the base-suite backend once (WireMock on the
//      exception port, the MQ port added to the dev-only showcase ports) - boot-backend.mjs, unchanged;
//   3. seeds REF-ORDER through the admin API (same seed as the gate), mints the customer A bearer in memory;
//   4. builds the console and serves it with the backend proxied (tests/screens/console-server.mjs);
//   5. runs playwright.release1.config.mjs with the E2E_* names wired from the boot output (journey-env.mjs);
//   6. tears everything down and releases the slot, whatever happened.
// Usage (a docker-suite command - only when no other container suite runs):
//   IPASS_BS_EXCEPTION_* (see tests/base-suite/lib/settings.mjs) node scripts/run-release1-journey.mjs <log-file>
// `--print-plan` prints the steps and the env NAMES and starts nothing. Secrets stay in this process's memory.
import { spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startArtemis } from './release1/artemis.mjs';
import { JOURNEY_ENV_NAMES, journeyEnv } from './release1/journey-env.mjs';
import { installSignalCleanup, journeyNames, makeCleanup, readStats } from './release1/journey-run.mjs';
import { journeyVerdict } from './release1/journey-verdict.mjs';
import { seedRefOrder } from './release1/seed-ref-order.mjs';
import { acquireSlot } from './release1/slot.mjs';
import { clientCredentialsToken } from './release1/tokens.mjs';
import { baseSuiteBootOptions } from '../tests/base-suite/lib/boot-options.mjs';
import { exceptionSettings } from '../tests/base-suite/lib/settings.mjs';
import { TENANTS } from '../tests/base-suite/lib/tenants.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const UI = path.resolve(HERE, '..');
const REPO = path.resolve(UI, '..');
const CONSOLE = path.join(REPO, 'ui'); // L29: the console project (public/ + npm run build); UI above is e2e-ui

if (process.argv.includes('--print-plan')) {
  console.log('run-release1-journey: slot -> artemis -> boot backend -> seed REF-ORDER -> build+serve console -> playwright.release1.config.mjs -> teardown');
  console.log(`env names wired: ${JOURNEY_ENV_NAMES.join(', ')}`);
  process.exit(0);
}

const logFile = process.argv[2];
if (!logFile) throw new Error('usage: run-release1-journey.mjs <log-file> | --print-plan');
const log = (message) => fs.appendFileSync(logFile, `${new Date().toISOString()} ${message}\n`);

const MQ_QUEUE = 'r1.orders.mq';
const HOLDER = 'arjun l28-journey';

const common = spawnSync('git', ['rev-parse', '--path-format=absolute', '--git-common-dir'], { cwd: REPO, encoding: 'utf8', windowsHide: true });
if (common.status !== 0 || !common.stdout.trim()) throw new Error('runner: git could not name the main clone (the docker slots sit beside it)');
const slotRoot = path.dirname(path.dirname(common.stdout.trim()));

const slot = await acquireSlot({ root: slotRoot, holder: HOLDER, slotNames: ['.docker-suite.lock-2', '.docker-suite.lock-3'], pollMs: 60000, log });
const { artemisName: ARTEMIS_NAME, mqPort: MQ_PORT, innerLock: inner } = journeyNames(slot.path); // R2: slot-derived, collision-free
const cleanup = makeCleanup(log); // R1: one idempotent cleanup for finally AND for SIGINT / SIGTERM
cleanup.add('slot', () => slot.release());
installSignalCleanup(cleanup, { log });
const keepFresh = setInterval(() => { try { slot.refresh(); } catch (e) { log(`slot refresh: ${e.message}`); } }, 10 * 60 * 1000);
cleanup.add('refresh timer', () => clearInterval(keepFresh));
try {
  // boot-backend takes its own lock: a private one inside our slot
  fs.rmSync(inner, { recursive: true, force: true });
  process.env.DOCKER_SUITE_LOCK = inner;
  const { default: bootBackend } = await import('./boot-backend.mjs');
  const { startConsole } = await import(new URL('../tests/screens/console-server.mjs', import.meta.url).href);

  spawnSync('docker', ['rm', '-f', ARTEMIS_NAME], { encoding: 'utf8' });
  const artemis = await startArtemis({ name: ARTEMIS_NAME, port: MQ_PORT, queue: MQ_QUEUE, user: `r1-${crypto.randomUUID()}`, password: crypto.randomUUID(), base: process.env });
  cleanup.add('artemis', () => artemis.stop());
  log(`artemis ${ARTEMIS_NAME} up on 127.0.0.1:${MQ_PORT}`);

  const settings = exceptionSettings(process.env);
  const options = baseSuiteBootOptions(settings, { lockHolder: HOLDER });
  const portsVar = Object.keys(options.appEnv).find((name) => /SHOWCASE_LOCAL_PORTS/i.test(name));
  if (!portsVar) throw new Error('runner: the showcase ports variable is not among the exception app settings');
  options.appEnv = { ...options.appEnv, [portsVar]: `${settings.port},${MQ_PORT}` };

  // the console's login config, then the build (an unbuilt console is this run's own red)
  fs.mkdirSync(path.join(CONSOLE, 'public'), { recursive: true });
  fs.writeFileSync(path.join(CONSOLE, 'public', 'console-config.json'), JSON.stringify({ logins: ['approver'] }, null, 2));
  const build = spawnSync('npm', ['run', 'build'], { cwd: CONSOLE, encoding: 'utf8', shell: process.platform === 'win32' });
  if (build.status !== 0) throw new Error(`the console build failed:\n${String(build.stdout).slice(-2000)}\n${String(build.stderr).slice(-2000)}`);

  const backend = await bootBackend(options);
  cleanup.add('backend', () => backend.teardown());
  const wiremock = backend.services.wiremock;
  const base = `http://${settings.host}:${wiremock.port}`;
  log(`backend ${backend.baseUrl}; wiremock ${wiremock.url}; tenant A ${backend.tenantCode}`);

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
  const tokens = { CUSTOMER_A: await clientCredentialsToken(backend.issuer, seeded.credential.clientId, seeded.credential.secret) };

  const consoleServer = await startConsole(backend.baseUrl, backend.sessions);
  cleanup.add('console', () => consoleServer.close());
  log(`console ${consoleServer.url}`);
  const env = journeyEnv({ ui: UI, consoleUrl: consoleServer.url, backend, wiremock, seeded, tokens });
  log(`env wired: ${Object.keys(env).join(', ')}`);

  const jsonReport = path.join(os.tmpdir(), `l28-journey-report-${process.pid}.json`);
  const npx = process.platform === 'win32' ? 'npx.cmd' : 'npx';
  const code = await new Promise((resolve) => {
    const child = spawn(npx, ['playwright', 'test', '-c', 'playwright.release1.config.mjs', '--reporter=line,html,json'],
      { cwd: UI, shell: process.platform === 'win32', stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, ...env, PLAYWRIGHT_HTML_OPEN: 'never', PLAYWRIGHT_JSON_OUTPUT_NAME: jsonReport } });
    const out = fs.createWriteStream(logFile, { flags: 'a' });
    child.stdout.pipe(out, { end: false });
    child.stderr.pipe(out, { end: false });
    child.on('close', (c) => { out.end(); resolve(c); });
  });
  const verdict = journeyVerdict(code, readStats(jsonReport));
  fs.rmSync(jsonReport, { force: true });
  log(verdict.line);
  console.log(verdict.line);
  process.exitCode = verdict.exitCode;
} catch (e) {
  log(`RUNNER FAILED: ${e.message}`);
  console.error(e.message);
  process.exitCode = 2;
} finally {
  await cleanup.run();
  log('slot released');
}
