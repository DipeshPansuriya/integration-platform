// T-096 (E3-F1-S2, E3 lane D): the seeded p95 gate. `npm run test:perf` = the @negative pair
// (same engine, no database) chained before THIS script, which runs the scenario for real:
//
//   Given configuration seeded for 50 interfaces and 200 targets  (seeded through the same
//   boot-backend.mjs container protocol - real jar, pinned postgres, real API writes)
//   When  each admin page of tests/perf/pages.json is loaded 100 times                  (the
//   built console, served exactly like the screen harness, signed in once through the real
//   Keycloak login form - E4 T-097 - every load carrying the backend's session cookie)
//   Then  every page's 95th-percentile (nearest-rank) load time is under 1.5 s.
//
// R03 parity: this boots docker, so `npm test` must never run it - it is its own command
// (the same exclusion rule the Java side locks with check-container-exclusion.sh). R06: the
// one-docker-suite-at-a-time lock is taken by bootBackend itself. R07: no secret value here,
// the passwords exist only inside boot-backend's memory.

import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import bootBackend from './boot-backend.mjs';
import { startConsole } from '../tests/screens/console-server.mjs';
import { browserLogin } from '../tests/screens/keycloak-login.mjs';
import { runP95Check, formatReport } from './measure-p95.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const UI = path.resolve(HERE, '..');
const CONSOLE = path.resolve(UI, '..', 'ui'); // L29: the console project (npm run build); UI is e2e-ui

const INTERFACES = 50; // E3-F1-S2's seeded fixture
const TARGETS_PER_INTERFACE = 4; // 50 x 4 = 200 target endpoints
const LOADS = 100;

async function api(boot, method, urlPath, body) {
  const response = await fetch(new URL(urlPath, boot.baseUrl), {
    method,
    headers: { ...boot.authHeaders(method), ...(body ? { 'content-type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined
  });
  return { status: response.status, data: await response.json().catch(() => null) };
}

/** The seed, through the REAL config API: 50 flows, each 4 destinations auto-registered as OUTBOUND targets. */
async function seedFixture(boot) {
  for (let i = 0; i < INTERFACES; i++) {
    const destinations = Array.from({ length: TARGETS_PER_INTERFACE }, (_, d) =>
      `T${String(i * TARGETS_PER_INTERFACE + d + 1).padStart(3, '0')}`);
    const created = await api(boot, 'POST', '/api/v1/admin/flows', {
      sourceSystem: 'S',
      destinations,
      businessKeyRule: 'customer_id',
      ordering: 'PER_KEY'
    });
    if (created.status !== 201) {
      throw new Error(`seed: flow ${i} answered ${created.status} (${JSON.stringify(created.data)})`);
    }
  }
  const flows = await api(boot, 'GET', '/api/v1/admin/flows');
  if (flows.status !== 200 || flows.data.length !== INTERFACES) {
    throw new Error(`seed verification: GET /flows answered ${flows.status} with ${flows.data?.length} rows, expected ${INTERFACES}`);
  }
  let targets = 0;
  for (const flow of flows.data) {
    const detail = await api(boot, 'GET', `/api/v1/admin/flows/${flow.id}`);
    if (detail.status !== 200) throw new Error(`seed verification: flow read answered ${detail.status}`);
    targets += detail.data.destinations.length;
  }
  if (targets !== INTERFACES * TARGETS_PER_INTERFACE) {
    throw new Error(`seed verification: ${targets} destination rows, expected ${INTERFACES * TARGETS_PER_INTERFACE}`);
  }
  console.log(`run-perf-suite: seeded ${INTERFACES} interfaces and ${targets} targets through the real API`);
}

/** One login through the realm's real form; its storage state (the session cookie) signs every load in. */
async function signedInState(consoleUrl, user) {
  const browser = await chromium.launch();
  try {
    const context = await browser.newContext();
    await browserLogin(await context.newPage(), consoleUrl, user);
    return await context.storageState();
  } finally {
    await browser.close();
  }
}

const build = spawnSync('npm', ['run', 'build'], { cwd: CONSOLE, encoding: 'utf8', shell: process.platform === 'win32' });
if (build.status !== 0) {
  console.error(`run-perf-suite: the console build failed:\n${build.stdout}\n${build.stderr}`);
  process.exit(1);
}

const config = JSON.parse(fs.readFileSync(path.join(UI, 'tests', 'perf', 'pages.json'), 'utf8'));
const boot = await bootBackend();
let consoleServer = null;
try {
  await seedFixture(boot);
  consoleServer = await startConsole(boot.baseUrl, boot.sessions);
  const storageState = await signedInState(consoleServer.url, boot.users.default);
  const result = await runP95Check(config, { base: consoleServer.url, loads: LOADS, storageState });
  console.log(formatReport(result));
  if (result.failures.length) {
    console.error(`run-perf-suite: FAIL - ${result.failures.map((f) => f.name).join(', ')} at or above the 1500 ms budget`);
    process.exitCode = 1;
  } else {
    console.log(`run-perf-suite: PASS - seeded ${INTERFACES} interfaces / ${INTERFACES * TARGETS_PER_INTERFACE} targets, ${LOADS} loads per page`);
  }
} finally {
  await consoleServer?.close();
  await boot.teardown();
}
