// Playwright protocol for the console's screen tests (T-017, T-022) - container-tagged in
// effect: globalSetup boots the REAL backend (docker lock, pinned postgres, the real Keycloak)
// and the BUILT console in-process (tests/screens/console-server.mjs, /api proxied to it).
// No MSW, no stub layer between screens and the API - E3-F3-S1. `npm run test:screens` is a
// container-suite command; `npm test` never boots it (R03). Specs navigate with the state's
// consoleUrl(), not a config baseURL.

import { defineConfig } from '@playwright/test';
import { STORAGE_STATE } from './tests/screens/boot-state.mjs';

export default defineConfig({
  testDir: 'tests/screens',
  timeout: 15000,
  workers: 1, // ONE booted backend serves the whole suite (global-setup): the lifecycle walks real
  // server state, so the specs run serially - file order alphabetical, no cross-worker races.
  globalSetup: 'tests/screens/global-setup.mjs',
  globalTeardown: 'tests/screens/global-teardown.mjs',
  use: {
    headless: true,
    // E4 (T-097): every spec starts signed in - the session global-setup obtained through the
    // real Keycloak login form (the backend's HttpOnly session cookie, never a token).
    storageState: STORAGE_STATE
  }
});