// lane qa-release (2026-10-22): the front half's OWN Playwright config as a plain object (unit-testable
// without Playwright; tests/base-suite/playwright.front.config.mjs wraps it in defineConfig).
// Its own specs dir (specs-front, so the exception-bound docker specs are never picked up), its own global
// setup (the boot WITHOUT the dev-only exception) and the base suite's teardown and signed-in state file.
// Run from ui/: npx playwright test -c tests/base-suite/playwright.front.config.mjs (a docker-suite command).

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BS_STORAGE_STATE } from './suite-config.mjs';

const UI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

export const FRONT_SEED_PROJECT = 'front-seed';
export const FRONT_SPEC_PROJECT = 'front';

export const FRONT_CONFIG = Object.freeze({
  testDir: 'tests/base-suite/specs-front',
  timeout: 120000,
  expect: { timeout: 15000 },
  workers: 1, // ONE booted backend serves the run; the scenarios walk real server state in order
  fullyParallel: false,
  retries: 0, // a flaky scenario is reported, never retried to green
  globalSetup: 'tests/base-suite/support/front-setup.mjs',
  globalTeardown: 'tests/base-suite/support/global-teardown.mjs',
  use: { headless: true, storageState: BS_STORAGE_STATE },
  projects: [
    { name: FRONT_SEED_PROJECT, testMatch: /front-seed\.setup\.mjs$/ },
    { name: FRONT_SPEC_PROJECT, testMatch: /\.front\.spec\.mjs$/, dependencies: [FRONT_SEED_PROJECT] }
  ]
});

/**
 * A fresh, extensible copy for defineConfig. The config file sits in tests/base-suite, so Playwright would
 * resolve the relative paths against that folder: they are made absolute against ui/ here.
 */
export function frontConfig() {
  return {
    ...FRONT_CONFIG,
    testDir: path.join(UI, FRONT_CONFIG.testDir),
    globalSetup: path.join(UI, FRONT_CONFIG.globalSetup),
    globalTeardown: path.join(UI, FRONT_CONFIG.globalTeardown),
    outputDir: path.join(UI, 'test-results'),
    expect: { ...FRONT_CONFIG.expect },
    use: { ...FRONT_CONFIG.use },
    projects: FRONT_CONFIG.projects.map((project) => ({ ...project }))
  };
}
