// T130 (row 323, F21 BS-B) step 5: the base suite's OWN Playwright config, as a plain object so the unit
// tests can read it without Playwright (unit/suite-config.test.mjs); playwright.base-suite.config.mjs
// wraps it in defineConfig. Separate from the screens suite (playwright.config.mjs, testDir tests/screens):
// its own specs dir, its own global setup (the boot with the base-suite options - boot-options.mjs) and
// its own signed-in state file. Run: npx playwright test -c playwright.base-suite.config.mjs - a
// docker-suite command (it takes the docker-suite lock through bootBackend); `npm test` never boots it.

import path from 'node:path';
import { fileURLToPath } from 'node:url';

const UI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** The base suite's signed-in browser state (tenant A's admin; the backend's session cookie only). */
export const BS_STORAGE_STATE = path.join(UI, 'test-results', '.auth', 'base-suite.json');

export const SEED_PROJECT = 'bs-seed';
export const SPEC_PROJECT = 'bs';

export const BASE_SUITE_CONFIG = Object.freeze({
  testDir: 'tests/base-suite/specs',
  timeout: 120000, // a scenario waits for the real pipeline (intake -> route -> transform -> deliver)
  expect: { timeout: 15000 },
  workers: 1, // ONE booted backend serves the run; the scenarios walk real server state in order
  fullyParallel: false,
  retries: 0, // a flaky scenario is reported, never retried to green
  globalSetup: 'tests/base-suite/support/global-setup.mjs',
  globalTeardown: 'tests/base-suite/support/global-teardown.mjs',
  use: { headless: true, storageState: BS_STORAGE_STATE },
  projects: [
    // both tenants' systems, contracts, flow, mapping and source credentials - through the real API only
    { name: SEED_PROJECT, testMatch: /bs-seed\.setup\.mjs$/ },
    { name: SPEC_PROJECT, testMatch: /\.docker\.spec\.mjs$/, dependencies: [SEED_PROJECT] }
  ]
});
