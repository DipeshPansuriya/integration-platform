// T215: Playwright config of the NESTED-APPOINTMENT live proof. Run it ONLY through scripts/run-nested-appointment-live.mjs
// (the runner takes the docker lock, boots the real backend, seeds the flow and sets every E2E_* name the spec reads).
// workers 1 / retries 0: a flaky step is reported, never retried to green. The trace is kept only for a FAILED run
// (it holds the run-scoped logins in clear text; they die with the run and the output folder is gitignored).
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: 'tests/release1',
  testMatch: /nested-appointment\.docker\.spec\.mjs$/,
  timeout: 5 * 60 * 1000,
  workers: 1,
  retries: 0,
  outputDir: process.env.NESTED_OUT || 'test-results',
  use: { headless: true, trace: 'retain-on-failure' }
});
