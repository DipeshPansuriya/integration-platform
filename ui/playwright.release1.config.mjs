// Row 323 T139 (E15-F1-S1): the Release 1 browser journey. A container suite in effect (the screens
// globalSetup boots the real backend under the docker lock) - run only once T143 lands:
//   npx playwright test -c playwright.release1.config.mjs --reporter=line --trace on
// `--list` boots nothing. workers 1 / retries 0: a flaky step is reported, never retried to green.
import { defineConfig } from '@playwright/test';
import { STORAGE_STATE } from './tests/screens/boot-state.mjs';

export default defineConfig({
  testDir: 'tests/release1',
  testIgnore: '**/unit/**', // node:test unit tests (pending-gate) run via `node --test`, never through Playwright
  timeout: 120000,
  workers: 1,
  retries: 0,
  globalSetup: 'tests/screens/global-setup.mjs',
  globalTeardown: 'tests/screens/global-teardown.mjs',
  use: { headless: true, storageState: STORAGE_STATE, trace: 'on' }
});
