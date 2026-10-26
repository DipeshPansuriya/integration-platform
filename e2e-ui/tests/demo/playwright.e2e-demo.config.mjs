// The visible end-to-end demo (one command, see _reports/2026-10-22-e2e-demo-plan.md): the base suite's seed, then its BS-07
// (intake -> route -> transform -> deliver, both tenants), BS-08 (the stage timeline), BS-09 (one correlation id), BS-10 (a dead
// letter on the Failures screen, then Replay) and BS-12 (dashboard counts) in that order, in a HEADED browser with slowMo.
// Test code only: no spec is edited; the sign-in is the base-suite global setup's real Keycloak login, shown headed too.
// Run from e2e-ui/ with E2E_DEMO=1 (a docker-suite command; it takes the docker-suite lock through the boot).

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from '@playwright/test';
import { BS_STORAGE_STATE, SEED_PROJECT, SPEC_PROJECT, suiteConfig } from '../base-suite/lib/suite-config.mjs';
import { demoLaunchOptions } from './demo-mode.mjs';

const NARRATION_PROJECT = 'demo-narration';
const UI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const base = suiteConfig();
const launch = demoLaunchOptions();

export default defineConfig({
  ...base,
  testDir: path.join(UI, 'tests', 'base-suite', 'specs'), // absolute: this file lives under e2e-ui/tests/demo
  globalSetup: path.join(UI, 'tests', 'base-suite', 'support', 'global-setup.mjs'),
  globalTeardown: path.join(UI, 'tests', 'base-suite', 'support', 'global-teardown.mjs'),
  reporter: [['list']],
  use: {
    ...base.use,
    storageState: BS_STORAGE_STATE,
    viewport: null,
    ...(launch.headless === false ? { headless: false } : {}),
    launchOptions: launch.slowMo === undefined ? {} : { slowMo: launch.slowMo, args: launch.args }
  },
  projects: [
    { name: SEED_PROJECT, testMatch: /bs-seed\.setup\.mjs$/ },
    // beats a to d made visible (banner + the stub journal tab); runs after the seed, before the base-suite scenarios
    { name: NARRATION_PROJECT, testDir: path.join(UI, 'tests', 'demo'), testMatch: /narration\.docker\.spec\.mjs$/, dependencies: [SEED_PROJECT] },
    { name: SPEC_PROJECT, testMatch: /bs-(07|08|09|10|12)\.docker\.spec\.mjs$/, dependencies: [SEED_PROJECT, NARRATION_PROJECT] }
  ]
});
