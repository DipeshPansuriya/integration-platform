// E18 (T-175) UI journeys config, authored on feature/playwright-author (lane playwright-author), NOT yet
// run: the journeys need the base-suite boot (WireMock + Kafka sidecars, the dev-only exception, source
// credentials) that the seeded docker environment provides - the e2e-full doc ("UI leg ... reusing the
// base-suite seed", documents/e2e-full.md) and tests/base-suite/support/global-setup.mjs boot both.
//
// This is deliberately a SECOND config from the e18 lane's read-only leg (e2e-ui/playwright.e2e-full.config.mjs,
// the screens boot): that one answers the shell reads; this one boots what the INTAKE journey needs. Reuses
// tests/base-suite/* unchanged (owned by feature/qa-release-base; imported, never edited) and does not edit
// e2e-ui/tests/base-suite either. Run: npx playwright test -c tests/e2e-full/playwright.e2e-full-journeys.config.mjs
// inside the docker env (the boot takes the docker-suite lock through boot-backed).
// NEVER runs on `npm test` (node --test list) and never with a booted docker lock of another suite.

import { defineConfig } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BS_STORAGE_STATE, SEED_PROJECT, suiteConfig } from '../../tests/base-suite/lib/suite-config.mjs';

const UI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

const base = suiteConfig();

export default defineConfig({
  ...base,
  // absolute: the base-suite paths in BASE_SUITE_CONFIG are relative to ui/, not to this config's directory
  testDir: path.join(UI, 'tests', 'e2e-full'),
  globalSetup: path.join(UI, 'tests', 'base-suite', 'support', 'global-setup.mjs'),
  globalTeardown: path.join(UI, 'tests', 'base-suite', 'support', 'global-teardown.mjs'),
  timeout: 120_000, // intake -> route -> transform -> deliver on one booted app (the base-suite's budget)
  expect: { timeout: 15_000 },
  workers: 1,
  retries: 0, // a flaky journey is reported, never retried to green
  use: { ...base.use, storageState: BS_STORAGE_STATE },
  projects: [
    // the base-suite seed: systems, contracts, the published orders flow, the source credentials.
    // Its per-project testDir is ABSOLUTE: the seed lives in tests/base-suite/specs, outside this
    // config's testDir (the base config's default testDir is relative to ui/, not to this directory).
    { name: SEED_PROJECT, testDir: path.join(UI, 'tests', 'base-suite', 'specs'), testMatch: /bs-seed\.setup\.mjs$/ },
    {
      name: 'e2e-full-journeys',
      testMatch: /e2e-full-journeys\.docker\.spec\.mjs$/,
      testIgnore: '**/unit/**',
      dependencies: [SEED_PROJECT]
    }
  ]
});