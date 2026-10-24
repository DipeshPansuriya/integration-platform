// Shared state between global-setup and global-teardown (Playwright runs both in the runner
// process). Holds the booted backend + console server for teardown. Specs read the console
// URL from the CONSOLE_URL environment name (global-setup sets it; worker processes inherit
// it), and `test:screens` boots the real backend once for the whole suite (boot-backend.mjs
// holds the docker-suite lock while it runs). STORAGE_STATE is the browser's signed-in state
// (the backend's session cookie only), written by global-setup and removed at teardown.

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const STORAGE_STATE = path.resolve(HERE, '..', '..', 'test-results', '.auth', 'console.json');

let boot = null;
let consoleServer = null;

export function setBooted(backend, consoleServerArg) {
  boot = backend;
  consoleServer = consoleServerArg;
}

/** T171: the booted backend handle, for a global setup that publishes more environment names to the workers. */
export function bootedBackend() {
  return boot;
}

export function consoleUrl() {
  return process.env.CONSOLE_URL;
}

/** The realm user the specs log in with (values only in this run's memory). */
export function loginUser() {
  return { username: process.env.CONSOLE_LOGIN_USER, password: process.env.CONSOLE_LOGIN_PASSWORD };
}

export async function teardownAll() {
  fs.rmSync(STORAGE_STATE, { force: true });
  if (consoleServer) await consoleServer.close();
  if (boot) await boot.teardown();
}
