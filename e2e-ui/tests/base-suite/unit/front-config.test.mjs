// lane qa-release (2026-10-22): the front half's own Playwright config - its own specs dir and testMatch,
// the boot WITHOUT the dev-only exception, the same one-worker / no-retry rules as the base suite.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE_SUITE_CONFIG } from '../lib/suite-config.mjs';
import { FRONT_CONFIG, FRONT_SEED_PROJECT, FRONT_SPEC_PROJECT, frontConfig } from '../lib/front-config.mjs';

const BS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

test('front config: separate specs dir, serial, no retries', () => {
  assert.equal(FRONT_CONFIG.testDir, 'tests/base-suite/specs-front');
  assert.notEqual(FRONT_CONFIG.testDir, BASE_SUITE_CONFIG.testDir, 'the exception-bound specs are never picked up');
  assert.equal(FRONT_CONFIG.workers, 1);
  assert.equal(FRONT_CONFIG.retries, 0);
  assert.equal(FRONT_CONFIG.fullyParallel, false);
  assert.equal(FRONT_CONFIG.globalSetup, 'tests/base-suite/support/front-setup.mjs');
  assert.equal(FRONT_CONFIG.globalTeardown, BASE_SUITE_CONFIG.globalTeardown);
  assert.equal(FRONT_CONFIG.use.storageState, BASE_SUITE_CONFIG.use.storageState);
});

test('front config: seed project first, specs depend on it, only .front.spec.mjs files match', () => {
  const [seed, specs] = FRONT_CONFIG.projects;
  assert.equal(seed.name, FRONT_SEED_PROJECT);
  assert.equal(specs.name, FRONT_SPEC_PROJECT);
  assert.deepEqual(specs.dependencies, [FRONT_SEED_PROJECT]);
  assert.ok(seed.testMatch.test('front-seed.setup.mjs'));
  assert.ok(specs.testMatch.test('sc07.front.spec.mjs'));
  assert.ok(!specs.testMatch.test('bs-07.docker.spec.mjs'));
});

test('frontConfig() is a fresh extensible copy', () => {
  const a = frontConfig();
  assert.equal(Object.isExtensible(a), true);
  assert.notEqual(a, frontConfig());
});

test('every front spec file is in specs-front and none reads the exception', () => {
  const dir = path.join(BS, 'specs-front');
  const files = fs.readdirSync(dir);
  assert.ok(files.some((f) => f.endsWith('.front.spec.mjs')));
  for (const f of files) {
    const text = fs.readFileSync(path.join(dir, f), 'utf8');
    assert.ok(!/exceptionSettings|requireException|IPASS_BS_EXCEPTION|targetUrl\(/.test(text), `${f} must not depend on the exception`);
  }
});

test('front-setup boots with the front options, never the exception settings', () => {
  const text = fs.readFileSync(path.join(BS, 'support', 'front-setup.mjs'), 'utf8');
  assert.match(text, /frontSuiteBootOptions/);
  assert.ok(!/exceptionSettings|requireException|baseSuiteBootOptions/.test(text));
});
