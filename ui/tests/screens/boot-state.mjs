// Shared state between global-setup and global-teardown (Playwright runs both in the runner
// process). Holds the booted backend + console server for teardown. Specs read the console
// URL from the CONSOLE_URL environment name (global-setup sets it; worker processes inherit
// it), and `test:screens` boots the real backend once for the whole suite (boot-backend.mjs
// holds the docker-suite lock while it runs).

import process from 'node:process';

let boot = null;
let consoleServer = null;

export function setBooted(backend, consoleServerArg) {
  boot = backend;
  consoleServer = consoleServerArg;
}

export function consoleUrl() {
  return process.env.CONSOLE_URL;
}

export async function teardownAll() {
  if (consoleServer) await consoleServer.close();
  if (boot) await boot.teardown();
}