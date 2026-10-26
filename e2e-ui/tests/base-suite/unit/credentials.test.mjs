// lane qa-release (2026-10-22): which source credential a scenario rotates. A rotation leaves the old credential
// ROTATING (its overlap window) and issues an ACTIVE replacement; rotating the SAME seeded id twice is a 409
// "the credential is already being rotated" (SourceCredentialService.rotate) - so every scenario file after the
// first must rotate the CURRENT active credential, found by listing the source system's credentials.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { activeCredentialId } from '../lib/credentials.mjs';

const row = (id, status, createdAt) => ({ id, status, createdAt, clientId: `c-${id}` });

test('one ACTIVE credential: that one', () => {
  assert.equal(activeCredentialId([row('a', 'ACTIVE', '2026-10-22T00:00:00Z')], 'seed'), 'a');
});

test('after a rotation the old one is ROTATING and the replacement ACTIVE: the replacement is rotated next', () => {
  const list = [row('old', 'ROTATING', '2026-10-22T00:00:00Z'), row('new', 'ACTIVE', '2026-10-22T00:01:00Z')];
  assert.equal(activeCredentialId(list, 'old'), 'new');
});

test('several ACTIVE: the newest by createdAt', () => {
  const list = [row('a', 'ACTIVE', '2026-10-22T00:00:00Z'), row('b', 'ACTIVE', '2026-10-22T00:05:00Z'), row('c', 'REVOKED', '2026-10-22T00:09:00Z')];
  assert.equal(activeCredentialId(list, 'a'), 'b');
});

test('an empty or unreadable list falls back to the seeded id; revoked-only is a loud failure, never a guess', () => {
  assert.equal(activeCredentialId([], 'seed'), 'seed');
  assert.equal(activeCredentialId(undefined, 'seed'), 'seed');
  assert.throws(() => activeCredentialId([row('x', 'REVOKED', '2026-10-22T00:00:00Z')], 'seed'), /no ACTIVE source credential/);
});
