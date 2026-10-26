// Row 323 T139 (E15-F1-S1), runnable since row 372 L28: the Release 1 browser journey's Playwright config.
// Run it through `node scripts/run-release1-journey.mjs <log-file>` - the runner takes the docker slot, boots
// the backend, Artemis and the console and sets the E2E_* names. There is NO globalSetup here on purpose: a
// bare `playwright test -c playwright.release1.config.mjs` boots nothing and fails on the first missing name.
// workers 1 / retries 0: a flaky step is reported, never retried to green.
import { defineConfig } from '@playwright/test';

// Watch mode (JOURNEY_WATCH=1 in the caller's shell - the runner does not set it): headed, slowMo ~1s, video on.
// S1: the trace keeps the run-scoped admin login and bearer in clear text, so it is kept only for a FAILED run.
// Those secrets die with the run (backend and Keycloak torn down), and the output folders are gitignored.
const WATCH = process.env.JOURNEY_WATCH === '1';

export default defineConfig({
  testDir: 'tests/release1',
  testIgnore: '**/unit/**', // node:test unit tests (pending-gate) run via `node --test`, never through Playwright
  testMatch: /journey\.docker\.spec\.mjs$/,
  timeout: 10 * 60 * 1000,
  workers: 1,
  retries: 0,
  outputDir: process.env.JOURNEY_OUT || 'test-results',
  use: WATCH
    ? { headless: false, trace: 'retain-on-failure', video: 'on', launchOptions: { slowMo: 1000 } }
    : { headless: true, trace: 'retain-on-failure' }
});
