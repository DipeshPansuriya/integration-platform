// Playwright protocol for the console's screen tests (T-017, T-022) - container-tagged in
// effect: globalSetup boots the REAL backend (docker lock, pinned postgres, local test IdP)
// and the BUILT console in-process (tests/screens/console-server.mjs, /api proxied to it).
// No MSW, no stub layer between screens and the API - E3-F3-S1. `npm run test:screens` is a
// container-suite command; `npm test` never boots it (R03). Specs navigate with the state's
// consoleUrl(), not a config baseURL.

import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: 'tests/screens',
  timeout: 15000,
  workers: 1, // ONE booted backend serves the whole suite (global-setup): the lifecycle walks real
  // server state, so the specs run serially - file order alphabetical, no cross-worker races.
  globalSetup: 'tests/screens/global-setup.mjs',
  globalTeardown: 'tests/screens/global-teardown.mjs',
  use: {
    headless: true
  }
});