// T130 (row 323, F21 BS-B): state shared by the base suite's global setup and teardown (Playwright runs
// both in the runner process) - the booted backend and the console server. Workers read what they need
// from the BS_* environment names global-setup sets (names only; values live in this run's memory).

import fs from 'node:fs';
import process from 'node:process';
import { BS_STORAGE_STATE } from '../lib/suite-config.mjs';

/** The environment names global-setup hands the workers. */
export const BS_ENV = Object.freeze({
  consoleUrl: 'BS_CONSOLE_URL',
  issuer: 'BS_ISSUER', // the realm issuer: a source system's client-credentials token comes from it
  tenantA: 'BS_TENANT_A_CODE',
  tenantB: 'BS_TENANT_B_CODE',
  pgContainer: 'BS_PG_CONTAINER',
  wiremockPort: 'BS_WIREMOCK_PORT',
  wiremockAdmin: 'BS_WIREMOCK_ADMIN',
  appPid: 'BS_APP_PID',
  appLog: 'BS_APP_LOG_URL' // T131: the loopback app-log tap (support/log-tap.mjs) - BS-09 reads its own lines
});

let boot = null;
let consoleServer = null;
let logTap = null;

export function setBooted(backend, consoleServerArg) {
  boot = backend;
  consoleServer = consoleServerArg;
}

/** One BS_* value in a worker; a missing one fails the scenario (the global setup did not run). */
export function bsEnv(key) {
  const name = BS_ENV[key];
  if (!name) throw new Error(`base suite: unknown environment key ${key}`);
  const value = process.env[name];
  if (typeof value !== 'string' || value === '') throw new Error(`base suite: ${name} is not set - run through playwright.base-suite.config.mjs`);
  return value;
}

/** T131: the app-log tap global-setup started; closed first at teardown. */
export function setLogTap(tap) {
  logTap = tap;
}

export async function teardownAll() {
  fs.rmSync(BS_STORAGE_STATE, { force: true });
  try {
    if (logTap) await logTap.close();
    if (consoleServer) await consoleServer.close();
  } finally {
    if (boot) await boot.teardown(); // sidecars, containers, JVM, then the docker-suite lock
  }
}
