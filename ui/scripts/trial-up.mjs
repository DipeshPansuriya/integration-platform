// Row 278 close-E7, T94 (T-162 gap 3): ONE start-up command for the 30-minute mapping trial.
//
//   cd ui && node scripts/trial-up.mjs        (Windows PowerShell: the same two commands)
//
// Needs JAVA_HOME pointing at the JDK 25 install (AGENTS.md); docker running. Boots the real stack
// through boot-backend.mjs (its own PostgreSQL, the real Keycloak with realm `platform`, the
// platform-app jar - built with mvnw when missing), seeds the trial tenant through the real API,
// serves the BUILT console on http://127.0.0.1:4173 (runs `vite build` first when ui/dist is
// missing), prints the sign-in lines and STAYS UP until Ctrl+C or Ctrl+Break in its terminal (or
// typing `stop` + Enter, closing the window, or SIGTERM on Linux/macOS). Then everything is torn
// down - console, app, postgres container, Keycloak - and the docker-suite lock is released. A
// failed boot or seed tears down too. On Windows `kill <pid>` / `taskkill /F` end the process
// WITHOUT the teardown (Windows has no catchable SIGTERM): containers stay and the lock note goes
// stale after 60 minutes - stop it from its own terminal instead.
//
// Seeded in the default tenant: source system "Customer A" (code customer-a) with SOURCE_REQUEST
// contract v1.0 {customerId, orderId, amount}; target system "Target B" (code target-b) with
// TARGET_REQUEST contract v2.0 APPROVED {clientId, referenceNo, invoiceValue} (approved by the
// harness approver - never its author); flow "orders" Customer A -> Target B (one route). The
// route's mapping stays empty and unbound - the trial user builds it.
//
// The trial login: realm user `trial-configurator`, role CONFIGURATOR in that tenant.
// Its password - R07 / credential-storage: no password value in any file, log or commit:
//   * from the environment variable IPASS_TRIAL_PASSWORD (held in the OS secret store - on Windows
//     the User environment; a terminal opened before it was set does not see it). Its value is
//     never printed: the banner says only "password: from IPASS_TRIAL_PASSWORD".
//   * when that variable is unset or empty: a strong random password for this run only, printed
//     ONCE to the console and nowhere else. Do not redirect this script's output to a file then.
//
// Lock (R06): taken only through boot-backend's acquire (waits while another suite holds it, never
// removes a live holder's lock). While up, the owner note is rewritten every 5 minutes with
// `trial-up pid=<pid> started=<iso>` so no other run ever reads it as stale (60 minutes).

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';
import bootBackend, { CONSOLE_ORIGIN, UI } from './boot-backend.mjs';
import { startConsole } from '../tests/screens/console-server.mjs';

export const TRIAL_PASSWORD_ENV = 'IPASS_TRIAL_PASSWORD';
export const TRIAL_USERNAME = 'trial-configurator';
const LOCK_REFRESH_MS = 5 * 60 * 1000;

const SOURCE = { code: 'customer-a', name: 'Customer A' };
const TARGET = { code: 'target-b', name: 'Target B' };
const SCHEMA = 'https://json-schema.org/draft/2020-12/schema';
const objectOf = (fields) => ({
  $schema: SCHEMA,
  type: 'object',
  required: Object.keys(fields),
  properties: Object.fromEntries(Object.entries(fields).map(([name, type]) => [name, { type }]))
});
const CUSTOMER_A_V1 = objectOf({ customerId: 'string', orderId: 'string', amount: 'number' });
const TARGET_B_V2 = objectOf({ clientId: 'string', referenceNo: 'string', invoiceValue: 'number' });

/** The trial password: the env variable's value when set and not blank, else a random one for this run. */
export function trialPassword(env = process.env) {
  const value = env[TRIAL_PASSWORD_ENV];
  if (typeof value === 'string' && value.trim() !== '') return { password: value, generated: false };
  return { password: crypto.randomBytes(18).toString('base64url'), generated: true };
}

/** The sign-in lines. A stored password is never printed; a generated one appears exactly once. */
export function signInBanner({ consoleUrl, username, credential }) {
  return [
    '',
    'Trial is up.',
    `  sign in at: ${consoleUrl}`,
    `  username:   ${username}`,
    credential.generated
      ? `  password:   ${credential.password}   (generated for this run only; ${TRIAL_PASSWORD_ENV} was not set - it is not saved anywhere)`
      : `  password: from ${TRIAL_PASSWORD_ENV} (your OS secret store)`,
    `  seeded:     ${SOURCE.name} contract v1.0, ${TARGET.name} contract v2.0 APPROVED, flow "orders" -> ${TARGET.name} (mapping empty)`,
    '  stop:       Ctrl+C or Ctrl+Break here (or type stop + Enter) - tears everything down and releases the docker-suite lock',
    ''
  ];
}

function must(answer, what) {
  if (answer.status !== 200 && answer.status !== 201) {
    throw new Error(`trial seed ${what}: HTTP ${answer.status} ${JSON.stringify(answer.data)}`);
  }
  return answer.data;
}

/** Seeds the trial data through the real API, exactly as mapping-designer.docker.spec.mjs:113-149 does. */
async function seed(call) {
  for (const system of [{ ...SOURCE, direction: 'INBOUND' }, { ...TARGET, direction: 'OUTBOUND' }]) {
    must(await call('default', 'POST', '/api/v1/admin/systems', {
      ...system, type: 'API', settings: { baseUrl: `https://${system.code}.partner.test/` }
    }), `system ${system.code}`);
  }
  const importVersion = async (system, role, versionLabel, document) => must(
    await call('default', 'POST', '/api/v1/admin/contracts/versions/import', { system, role, versionLabel, format: 'JSON_SCHEMA', document }),
    `contract ${system}/${role}/${versionLabel}`);
  await importVersion(SOURCE.code, 'SOURCE_REQUEST', '1.0', CUSTOMER_A_V1); // a draft source is allowed
  const target = await importVersion(TARGET.code, 'TARGET_REQUEST', '2.0', TARGET_B_V2);
  must(await call('default', 'POST', `/api/v1/admin/contracts/versions/${target.id}/submit`, { changeNote: 'ready for mapping (Target B v2.0)' }), 'submit Target B v2.0');
  must(await call('approver', 'POST', `/api/v1/admin/contracts/versions/${target.id}/approve`), 'approve Target B v2.0');
  const flow = must(await call('default', 'POST', '/api/v1/admin/flows', {
    code: 'orders', name: 'orders', sourceSystem: SOURCE.code, destinations: [TARGET.code], businessKeyRule: 'orderId'
  }), 'flow orders');
  const routes = must(await call('default', 'GET', `/api/v1/admin/flows/${flow.id}/routes`), 'routes of orders').routes ?? [];
  if (routes.length !== 1 || !routes[0].mappingId) throw new Error(`trial seed: flow orders should have one route with a mapping, saw ${JSON.stringify(routes)}`);
  return { flowId: flow.id, routeId: routes[0].id, mappingId: routes[0].mappingId };
}

async function ensureBuiltConsole(print) {
  if (fs.existsSync(path.join(UI, 'dist', 'index.html'))) return;
  print('trial-up: ui/dist is missing - building the console (vite build)');
  const { build } = await import('vite');
  await build({ root: UI, logLevel: 'warn' });
}

/**
 * Boots, seeds and serves the trial; resolves once the console answers. `stop()` (idempotent)
 * tears everything down and releases the lock. `env` is where the password variable is read.
 */
export async function startTrial({ env = process.env, print = (line) => console.log(line) } = {}) {
  process.chdir(UI); // the console's vite preview reads ui/'s config from the working directory
  await ensureBuiltConsole(print);
  const credential = trialPassword(env);
  const holder = `trial-up pid=${process.pid} started=${new Date().toISOString()}`;
  print('trial-up: booting PostgreSQL, Keycloak and the backend (takes a few minutes; waits if another docker suite holds the lock)');
  const boot = await bootBackend({
    lockHolder: holder,
    extraUsers: [{ key: 'trial', username: TRIAL_USERNAME, password: credential.password, role: 'CONFIGURATOR' }]
  });
  let consoleServer = null;
  let refresher = null;
  let stopping = null;
  const stop = () => {
    stopping ??= (async () => {
      clearInterval(refresher);
      try {
        await consoleServer?.close();
      } catch (e) {
        console.error(`trial-up: closing the console: ${e.message}`);
      }
      await boot.teardown(); // app, postgres container, Keycloak, then the lock
    })();
    return stopping;
  };
  try {
    refresher = setInterval(() => {
      try {
        boot.refreshLock();
      } catch (e) {
        console.error(`trial-up: could not refresh the lock note (${e.code ?? e.message}) - the lock may have been removed`);
      }
    }, LOCK_REFRESH_MS);
    const seeded = await seed(boot.call);
    consoleServer = await startConsole(boot.baseUrl, {}); // no harness sessions: no browser request can borrow one
    for (const line of signInBanner({ consoleUrl: CONSOLE_ORIGIN, username: TRIAL_USERNAME, credential })) print(line);
    return {
      consoleUrl: CONSOLE_ORIGIN,
      backendUrl: boot.baseUrl,
      username: TRIAL_USERNAME,
      sourceSystem: SOURCE.code,
      targetSystem: TARGET.code,
      ...seeded,
      lockPath: boot.lockPath,
      pgName: boot.pgName,
      stop
    };
  } catch (e) {
    await stop();
    throw e;
  }
}

async function main() {
  let trial = null;
  let requested = null;
  const shutdown = async (why) => {
    if (requested) return;
    requested = why;
    if (!trial) {
      console.log(`trial-up: ${why} - will stop as soon as the boot finishes`);
      return;
    }
    console.log(`trial-up: stopping (${why}) ...`);
    try {
      await trial.stop();
      console.log('trial-up: stopped - console, app, postgres and Keycloak are gone; lock released');
      process.exit(0);
    } catch (e) {
      console.error(`trial-up: teardown failed: ${e.message}`);
      process.exit(1);
    }
  };
  for (const signal of ['SIGINT', 'SIGTERM', 'SIGBREAK', 'SIGHUP']) process.on(signal, () => shutdown(signal));
  process.on('uncaughtException', (e) => {
    console.error(`trial-up: unexpected error: ${e.message}`);
    shutdown('error');
  });
  readline.createInterface({ input: process.stdin }).on('line', (line) => {
    if (['stop', 'q', 'quit'].includes(line.trim().toLowerCase())) shutdown('stop typed');
  });
  try {
    trial = await startTrial();
  } catch (e) {
    console.error(`trial-up: start failed (everything started so far was torn down): ${e.message}`);
    process.exit(1);
  }
  if (requested) {
    const why = requested;
    requested = null;
    await shutdown(why);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
