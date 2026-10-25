// T214: the hosted driver of the NESTED-APPOINTMENT seed only reads env NAMES, plans without touching anything, and refuses to
// start with a missing name - these tests never reach a network.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const driver = new URL('../../../scripts/seed-nested-appointment-hosted.mjs', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const run = (args, env) => spawnSync(process.execPath, [driver, ...args], { encoding: 'utf8', env: { PATH: process.env.PATH, ...env } });

test('--print-plan lists the steps and the env names and starts nothing', () => {
  const out = run(['--print-plan'], { SEED_AUTHOR_PASSWORD: 'must-not-be-printed' });
  assert.equal(out.status, 0, out.stderr);
  for (const name of ['IPASS_BASE_URL', 'SEED_AUTHOR_USER', 'SEED_AUTHOR_PASSWORD', 'SEED_APPROVER_USER', 'SEED_APPROVER_PASSWORD', 'SEED_TARGET_BASE', 'SEED_SOURCE_BASE']) {
    assert.ok(out.stdout.includes(name), name);
  }
  assert.ok(!out.stdout.includes('must-not-be-printed'));
});

test('a missing name stops the run before any call, naming it', () => {
  const out = run([], { IPASS_BASE_URL: 'https://ipass.invalid' });
  assert.notEqual(out.status, 0);
  assert.match(out.stderr, /SEED_AUTHOR_PASSWORD/);
  assert.ok(!out.stderr.includes('ipass.invalid/'), 'no request was made');
});

test('the hosted base must be https', () => {
  const env = { IPASS_BASE_URL: 'http://ipass.invalid', SEED_AUTHOR_USER: 'a', SEED_AUTHOR_PASSWORD: 'b', SEED_APPROVER_USER: 'c', SEED_APPROVER_PASSWORD: 'd', SEED_TARGET_BASE: 'http://wiremock:8089/nested-target/', SEED_SOURCE_BASE: 'http://wiremock:8089/nested-source/' };
  const out = run([], env);
  assert.notEqual(out.status, 0);
  assert.match(out.stderr, /https/);
});
