// Playwright global setup (T-017/T-022 protocol; real login since T-097): write the harness's
// login config into the console's public dir (the app renders the second-binding affordance only
// while it exists), BUILD the console, boot the REAL backend and Keycloak once for the whole suite
// (boot-backend.mjs takes the docker-suite lock itself), serve the built console with the backend
// routes proxied, and log the browser in ONCE through the realm's real login form. The signed-in
// storage state (the backend's session cookie, never a token) is what every spec starts from; the
// login scenarios open their own fresh contexts.

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { setBooted, STORAGE_STATE } from './boot-state.mjs';
import { screenAppEnv } from './screen-hosts.mjs';
import { browserLogin } from './keycloak-login.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const UI = path.resolve(HERE, '..', '..', '..', 'ui'); // L29: the console project (public/ + npm run build), not e2e-ui

/** Row 575 S1: one realm user per menu role in the default tenant (the harness has no SUPPORT user and its CONFIGURATOR is in tenant B). */
const MENU_ROLES = { 'menu-cf': 'CONFIGURATOR', 'menu-su': 'SUPPORT', 'menu-ap': 'APPROVER', 'menu-ro': 'READ_ONLY' };

/** T171: the screens boot with an optional lock holder name (the security gate suite names itself in the lock note). */
export async function bootScreens({ lockHolder } = {}) {
  const { default: bootBackend } = await import(new URL('../../scripts/boot-backend.mjs', import.meta.url).href);
  const { startConsole } = await import(new URL('./console-server.mjs', import.meta.url).href);

  fs.mkdirSync(path.join(UI, 'public'), { recursive: true });
  fs.writeFileSync(
    path.join(UI, 'public', 'console-config.json'),
    JSON.stringify({ logins: ['approver', 'other-tenant', 'empty-tenant'] }, null, 2)
  );
  const build = spawnSync('npm', ['run', 'build'], { cwd: UI, encoding: 'utf8', shell: process.platform === 'win32' });
  if (build.status !== 0) {
    throw new Error(`global-setup: the console build failed (an unbuilt console is the suite's own red):\n${build.stdout}\n${build.stderr}`);
  }

  // Passwords are random per run and live in this run's memory and its workers' environment only (R07).
  const menuUsers = Object.entries(MENU_ROLES).map(([key, role]) => ({
    key, username: key, role, password: `Aa1!${randomBytes(12).toString('hex')}`
  }));
  const backend = await bootBackend({ loginSeed: true, appEnv: screenAppEnv(), lockHolder, extraUsers: menuUsers });
  let consoleServer;
  try {
    consoleServer = await startConsole(backend.baseUrl, backend.sessions);
  } catch (e) {
    // startConsole's failure would strand the booted backend (its containers, JVM and the
    // docker-suite lock) with no global teardown having run yet - release before rethrowing.
    await backend.teardown();
    throw e;
  }
  setBooted(backend, consoleServer);

  const browser = await chromium.launch();
  try {
    const context = await browser.newContext();
    await browserLogin(await context.newPage(), consoleServer.url, backend.users.default);
    fs.mkdirSync(path.dirname(STORAGE_STATE), { recursive: true });
    await context.storageState({ path: STORAGE_STATE });
  } finally {
    await browser.close();
  }

  // Workers inherit these environment names (R07: names; the values live in this run's memory).
  process.env.CONSOLE_URL = consoleServer.url;
  process.env.IPASS_UI_PG_CONTAINER = backend.pgName; // L22: the specs ask THIS run's postgres, not any ipass-ui-t41-pg-*
  process.env.CONSOLE_ISSUER = backend.issuer;
  process.env.CONSOLE_TENANT_CODE = backend.tenantCode;
  process.env.CONSOLE_OTHER_TENANT_FLOW = backend.otherTenantFlowId;
  process.env.CONSOLE_LOGIN_USER = backend.users.default.username;
  process.env.CONSOLE_LOGIN_PASSWORD = backend.users.default.password;
  process.env.CONSOLE_MENU_USERS = JSON.stringify(
    Object.fromEntries(menuUsers.map(({ key, role, username, password }) => [role, { username: backend.extraUsers?.[key]?.username ?? username, password }]))
  );
}

export default async function globalSetup() {
  await bootScreens();
}
