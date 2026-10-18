// T130 (row 323, F21 BS-B): the base suite's global setup - the screens suite's protocol
// (tests/screens/global-setup.mjs) with the base-suite boot options (lib/boot-options.mjs).
//
// Fail closed, cheapest first: the dev-only exception must be configured (settings.mjs - unset or still
// TBD(row278) FAILS here, never a skip) and the app must keep its real host resolver - both checked
// BEFORE the console build, the docker-suite lock or any container. Then: build the console, boot the
// real backend ONCE (tenant B's admin + approver, the one WireMock inside the lock, the exception's app
// settings, the platform-admin secret for source credentials), serve the console with the backend routes
// proxied, sign tenant A's admin in through the realm's real login form, and hand the workers the
// BS_* names (boot-state.mjs). Nothing here writes a pipeline row.

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { BS_CONSOLE_LOGINS, baseSuiteBootOptions } from '../lib/boot-options.mjs';
import { assertRealResolver, exceptionSettings } from '../lib/settings.mjs';
import { BS_STORAGE_STATE } from '../lib/suite-config.mjs';
import { TENANT_B_PLAN } from '../lib/tenants.mjs';
import { BS_ENV, setBooted, setLogTap } from './boot-state.mjs';
import { startLogTap } from './log-tap.mjs';

const UI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

export default async function globalSetup() {
  const settings = exceptionSettings(process.env);
  assertRealResolver(process.env);
  const options = baseSuiteBootOptions(settings, {
    lockHolder: `aditya-qa-automation base-suite pid=${process.pid} started=${new Date().toISOString()}`
  });

  const { default: bootBackend } = await import(new URL('../../../scripts/boot-backend.mjs', import.meta.url).href);
  const { startConsole } = await import(new URL('../../screens/console-server.mjs', import.meta.url).href);
  const { browserLogin } = await import(new URL('../../screens/keycloak-login.mjs', import.meta.url).href);

  fs.mkdirSync(path.join(UI, 'public'), { recursive: true });
  fs.writeFileSync(path.join(UI, 'public', 'console-config.json'), JSON.stringify({ logins: BS_CONSOLE_LOGINS }, null, 2));
  const build = spawnSync('npm', ['run', 'build'], { cwd: UI, encoding: 'utf8', shell: process.platform === 'win32' });
  if (build.status !== 0) {
    throw new Error(`base-suite global-setup: the console build failed:\n${build.stdout}\n${build.stderr}`);
  }

  const backend = await bootBackend(options);
  let consoleServer;
  try {
    const tenantB = backend.extraTenants[TENANT_B_PLAN.key];
    const wiremock = backend.services.wiremock;
    const kafka = backend.services.kafka;
    if (!tenantB || !wiremock || !kafka) throw new Error('base-suite global-setup: the boot came up without tenant B, the WireMock or the Kafka broker');
    consoleServer = await startConsole(backend.baseUrl, backend.sessions);
    setBooted(backend, consoleServer);
    // T131 (BS-09): the app's log stays in this process's memory; workers read it over the loopback tap
    const logTap = await startLogTap(() => backend.logDump());
    setLogTap(logTap);

    const browser = await chromium.launch();
    try {
      const context = await browser.newContext();
      await browserLogin(await context.newPage(), consoleServer.url, backend.users.default);
      fs.mkdirSync(path.dirname(BS_STORAGE_STATE), { recursive: true });
      await context.storageState({ path: BS_STORAGE_STATE });
    } finally {
      await browser.close();
    }

    // Workers inherit these names (R07: names; values in this run's memory). The IPASS_BS_EXCEPTION_*
    // names are already in the environment - the specs read the exception through settings.mjs.
    process.env[BS_ENV.consoleUrl] = consoleServer.url;
    process.env[BS_ENV.issuer] = backend.issuer;
    process.env[BS_ENV.tenantA] = backend.tenantCode;
    process.env[BS_ENV.tenantB] = tenantB.code;
    process.env[BS_ENV.pgContainer] = backend.pgName;
    process.env[BS_ENV.wiremockPort] = String(wiremock.port);
    process.env[BS_ENV.wiremockAdmin] = wiremock.adminUrl;
    process.env[BS_ENV.kafkaContainer] = kafka.name;
    process.env[BS_ENV.appPid] = String(backend.appPid);
    process.env[BS_ENV.appLog] = logTap.url;
  } catch (e) {
    // a failure after the boot would strand its containers, JVM and the docker-suite lock
    if (consoleServer) await consoleServer.close().catch(() => {});
    await backend.teardown();
    throw e;
  }
}
