// Playwright global setup (T-017/T-022 protocol): boot the REAL backend once for the whole
// suite (boot-backend.mjs takes the docker-suite lock itself) and start the built console
// (scripts/preview served in-process) with /api proxied to it. The built console must exist
// (npm run build) - an unbuilt console is the suite's own red.

import { setBooted } from './boot-state.mjs';

export default async function globalSetup() {
  const { default: bootBackend } = await import(new URL('../../scripts/boot-backend.mjs', import.meta.url).href);
  const { startConsole } = await import(new URL('./console-server.mjs', import.meta.url).href);
  const backend = await bootBackend();
  const consoleServer = await startConsole(backend.baseUrl);
  setBooted(backend, consoleServer);
  process.env.CONSOLE_URL = consoleServer.url; // workers inherit the env name (R07)
}