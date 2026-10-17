// T130 (row 323, F21 BS-B) step 5: the base suite's OWN Playwright config - separate from the screens
// suite (playwright.config.mjs, testDir tests/screens), one worker, the seed project first.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE_SUITE_CONFIG, BS_STORAGE_STATE, SEED_PROJECT, SPEC_PROJECT } from '../lib/suite-config.mjs';

const UI = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const inUi = (relative) => path.resolve(UI, relative);

test('the base suite reads only tests/base-suite/specs - never the screens suite', () => {
  assert.equal(BASE_SUITE_CONFIG.testDir, 'tests/base-suite/specs');
  const screens = fs.readFileSync(inUi('playwright.config.mjs'), 'utf8');
  assert.match(screens, /testDir: 'tests\/screens'/, 'the screens config still reads tests/screens only');
  assert.ok(!inUi(BASE_SUITE_CONFIG.testDir).startsWith(inUi('tests/screens')));
});

test('one worker: one booted backend serves the run, specs walk real server state', () => {
  assert.equal(BASE_SUITE_CONFIG.workers, 1);
  assert.equal(BASE_SUITE_CONFIG.fullyParallel, false);
  assert.equal(BASE_SUITE_CONFIG.retries, 0, 'a flaky scenario is reported, never retried to green');
});

test('its own global setup and teardown files exist under tests/base-suite/support', () => {
  for (const file of [BASE_SUITE_CONFIG.globalSetup, BASE_SUITE_CONFIG.globalTeardown]) {
    assert.match(file, /^tests\/base-suite\/support\/global-(setup|teardown)\.mjs$/);
    assert.ok(fs.existsSync(inUi(file)), `${file} exists`);
  }
});

test('the seed project runs first; the scenario project depends on it', () => {
  const [seed, specs] = BASE_SUITE_CONFIG.projects;
  assert.equal(seed.name, SEED_PROJECT);
  assert.ok(seed.testMatch.test('bs-seed.setup.mjs'));
  assert.ok(!seed.testMatch.test('bs-07.docker.spec.mjs'));
  assert.equal(specs.name, SPEC_PROJECT);
  assert.deepEqual(specs.dependencies, [SEED_PROJECT]);
  assert.ok(specs.testMatch.test('bs-07.docker.spec.mjs'));
  assert.ok(!specs.testMatch.test('bs-seed.setup.mjs'));
});

test('its signed-in state is its own file, never the screens suite console.json', () => {
  assert.equal(BASE_SUITE_CONFIG.use.storageState, BS_STORAGE_STATE);
  assert.match(BS_STORAGE_STATE.replaceAll('\\', '/'), /\/test-results\/\.auth\/base-suite\.json$/);
});
