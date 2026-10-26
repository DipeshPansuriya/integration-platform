// BS-14 (lane 4): WCAG AA basics on the console screens. Two projects:
//  - fixtures: the probes proven on inline pages - no backend, runs anywhere:
//      A11Y_FIXTURES_ONLY=1 npx playwright test -c playwright.a11y.config.mjs
//  - routes:   the real console behind the real backend (the screens protocol: docker-suite lock,
//      pinned postgres, real Keycloak sign-in, ONE serial worker). Container-tagged like test:screens:
//      npx playwright test -c playwright.a11y.config.mjs --project=routes
// No axe dependency: the checks are Playwright built-ins (role locators, ariaSnapshot, keyboard, computed
// style) judged by the pure helpers in tests/a11y/lib (node --test tests/a11y/unit/*.test.mjs).

import { defineConfig } from '@playwright/test';
import { STORAGE_STATE } from './tests/screens/boot-state.mjs';

const FIXTURES_ONLY = process.env.A11Y_FIXTURES_ONLY === '1';

export default defineConfig({
  testDir: 'tests/a11y',
  outputDir: 'test-results/a11y',
  timeout: 90000,
  workers: 1, // one booted backend; the walks read real server state serially
  fullyParallel: false,
  retries: 0, // a flaky a11y check is reported, never retried to green
  ...(FIXTURES_ONLY ? {} : { globalSetup: 'tests/a11y/global-setup.mjs', globalTeardown: 'tests/a11y/global-teardown.mjs' }),
  use: { headless: true, trace: 'retain-on-failure' },
  projects: [
    { name: 'fixtures', testMatch: /.*\.fixture\.spec\.mjs/ },
    ...(FIXTURES_ONLY ? [] : [{ name: 'routes', testMatch: /.*\.docker\.spec\.mjs/, use: { storageState: STORAGE_STATE } }]),
  ],
});
