// Row 278 close-E7, T94 (T-162 gap 3) - the trial start-up script's credential and lock rules,
// FAILING FIRST. No docker: pure functions of scripts/trial-up.mjs and scripts/boot-backend.mjs.
//
//   password source : IPASS_TRIAL_PASSWORD set -> that value is the password and is NEVER printed
//                     (the banner says only where it comes from); unset or empty -> a strong random
//                     password per start, printed exactly once in the sign-in banner, never to a file.
//   lock note       : the docker-suite lock's owner note is rewritten with a holder line and its
//                     mtime moves forward, so a trial that stays up never reads as stale (> 60 min).

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { TRIAL_PASSWORD_ENV, TRIAL_USERNAME, signInBanner, trialPassword } from '../../scripts/trial-up.mjs';
import { writeLockNote } from '../../scripts/boot-backend.mjs';

const occurrences = (text, needle) => text.split(needle).length - 1;

test('the password variable is IPASS_TRIAL_PASSWORD and the trial login is trial-configurator', () => {
  assert.equal(TRIAL_PASSWORD_ENV, 'IPASS_TRIAL_PASSWORD');
  assert.equal(TRIAL_USERNAME, 'trial-configurator');
});

test('env set: that value is the password and the banner never prints it', () => {
  const value = `throwaway-${Date.now().toString(36)}-Xy9`;
  const credential = trialPassword({ [TRIAL_PASSWORD_ENV]: value });
  assert.equal(credential.password, value);
  assert.equal(credential.generated, false);
  const banner = signInBanner({ consoleUrl: 'http://127.0.0.1:4173', username: TRIAL_USERNAME, credential }).join('\n');
  assert.equal(occurrences(banner, value), 0, 'the stored password must never be printed');
  assert.match(banner, /password: from IPASS_TRIAL_PASSWORD/);
  assert.match(banner, /http:\/\/127\.0\.0\.1:4173/);
  assert.match(banner, /trial-configurator/);
});

for (const [label, env] of [['unset', {}], ['empty', { IPASS_TRIAL_PASSWORD: '' }], ['blank', { IPASS_TRIAL_PASSWORD: '   ' }]]) {
  test(`env ${label}: a generated strong password, random per call, printed exactly once`, () => {
    const first = trialPassword(env);
    const second = trialPassword(env);
    assert.equal(first.generated, true);
    assert.ok(first.password.length >= 16, `generated password is ${first.password.length} chars`);
    assert.notEqual(first.password, second.password, 'a fresh password per start');
    const banner = signInBanner({ consoleUrl: 'http://127.0.0.1:4173', username: TRIAL_USERNAME, credential: first }).join('\n');
    assert.equal(occurrences(banner, first.password), 1, 'printed exactly once');
    assert.doesNotMatch(banner, /from IPASS_TRIAL_PASSWORD/);
  });
}

test('lock note refresh: writes a holder line and bumps the owner mtime', () => {
  const lockDir = fs.mkdtempSync(path.join(os.tmpdir(), 't94-lock-'));
  try {
    const owner = path.join(lockDir, 'owner');
    fs.writeFileSync(owner, 'old note');
    const past = new Date(Date.now() - 50 * 60 * 1000);
    fs.utimesSync(owner, past, past);
    const before = fs.statSync(owner).mtimeMs;

    const holder = `trial-up pid=${process.pid} started=2026-10-15T10:00:00.000Z`;
    writeLockNote(lockDir, holder);

    const note = fs.readFileSync(owner, 'utf8');
    assert.match(note, new RegExp(`^${holder.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`));
    assert.match(note, /refreshed=\d{4}-\d{2}-\d{2}T/);
    assert.ok(fs.statSync(owner).mtimeMs > before + 40 * 60 * 1000, 'mtime moved to now');
  } finally {
    fs.rmSync(lockDir, { recursive: true, force: true });
  }
});

test('lock note refresh never re-creates a lock that is gone', () => {
  const lockDir = path.join(os.tmpdir(), `t94-gone-${process.pid}-${Date.now()}`);
  assert.throws(() => writeLockNote(lockDir, 'trial-up pid=1'));
  assert.equal(fs.existsSync(lockDir), false);
});
