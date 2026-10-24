// Row 323 T139 (E15-F1-S1), runnable since row 372 L28: the Release 1 browser journey's Playwright config.
// Run it through `node scripts/run-release1-journey.mjs <log-file>` - the runner takes the docker slot, boots
// the backend, Artemis and the console and sets the E2E_* names. There is NO globalSetup here on purpose: a
// bare `playwright test -c playwright.release1.config.mjs` boots nothing and fails on the first missing name.
// workers 1 / retries 0: a flaky step is reported, never retried to green.
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: 'tests/release1',
  testMatch: /journey\.docker\.spec\.mjs$/,
  timeout: 10 * 60 * 1000,
  workers: 1,
  retries: 0,
  use: { headless: true, trace: 'on' }
});
