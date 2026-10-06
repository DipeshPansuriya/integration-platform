// Playwright global setup (T-017/T-022 protocol): write the harness's login config into the
// console's public dir (the app renders the second-binding affordance only while it exists),
// BUILD the console, boot the REAL backend once for the whole suite (boot-backend.mjs takes
// the docker-suite lock itself) and serve the built console (scripts/preview in-process) with
// /api proxied and bearer-stamped for the harness logins.

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { setBooted } from './boot-state.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const UI = path.resolve(HERE, '..', '..');

export default async function globalSetup() {
  const { default: bootBackend } = await import(new URL('../../scripts/boot-backend.mjs', import.meta.url).href);
  const { startConsole } = await import(new URL('./console-server.mjs', import.meta.url).href);

  fs.mkdirSync(path.join(UI, 'public'), { recursive: true });
  fs.writeFileSync(
    path.join(UI, 'public', 'console-config.json'),
    JSON.stringify({ logins: ['approver', 'other-tenant'] }, null, 2)
  );
  const build = spawnSync('npm', ['run', 'build'], { cwd: UI, encoding: 'utf8', shell: process.platform === 'win32' });
  if (build.status !== 0) {
    throw new Error(`global-setup: the console build failed (an unbuilt console is the suite's own red):\n${build.stdout}\n${build.stderr}`);
  }

  const backend = await bootBackend();
  const consoleServer = await startConsole(backend.baseUrl, backend.tokens);
  setBooted(backend, consoleServer);
  process.env.CONSOLE_URL = consoleServer.url; // workers inherit the env name (R07)
}
