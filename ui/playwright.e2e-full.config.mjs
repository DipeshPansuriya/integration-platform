// E18 (T-175) UI leg of the project-close gate: the screens protocol (REAL backend, REAL Keycloak, built console, one
// serial worker, docker lock in global-setup) pointed at tests/e2e-full. Reuses tests/screens/* and
// tests/base-suite/support/* unchanged; new files only under tests/e2e-full (base-suite is owned by feature/qa-release-base).
// Run: npx playwright test -c playwright.e2e-full.config.mjs   (container-suite command, needs the docker lock).

import { defineConfig } from '@playwright/test';
import { STORAGE_STATE } from './tests/screens/boot-state.mjs';

export default defineConfig({
  testDir: 'tests/e2e-full',
  timeout: 20000,
  workers: 1,
  globalSetup: 'tests/screens/global-setup.mjs',
  globalTeardown: 'tests/screens/global-teardown.mjs',
  use: { headless: true, storageState: STORAGE_STATE }
});
