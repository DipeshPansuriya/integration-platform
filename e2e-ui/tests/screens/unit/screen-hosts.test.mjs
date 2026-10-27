// Row 323 (T97 save-time check), FAILING FIRST: every fixture host the screen specs seed a system with
// is on the allow-list the screens boot passes the app - so a new fixture host fails here, not in docker.
// No docker, no JVM: reads the spec sources and the pure screen-hosts.mjs.

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { SCREEN_ALLOWED_HOSTS, SCREEN_UNLISTED_HOST, screenAppEnv } from '../screen-hosts.mjs';

const SCREENS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** AllowList.java's rule: exact name, or `*.domain` for a sub-domain (never the bare domain); case-insensitive. */
function allows(patterns, host) {
  const name = host.trim().toLowerCase().replace(/\.$/, '');
  return patterns.map((p) => p.trim().toLowerCase()).some((p) =>
    p.startsWith('*.') && p.length > 2 ? name.length > p.length - 1 && name.endsWith(p.slice(1)) : p === name);
}

function fixtureHosts() {
  const hosts = new Set();
  for (const file of fs.readdirSync(SCREENS).filter((f) => f.endsWith('.spec.mjs'))) {
    const src = fs.readFileSync(path.join(SCREENS, file), 'utf8');
    for (const [, raw] of src.matchAll(/baseUrl:\s*[`'"]https?:\/\/([^/`'"\s:]+)/g)) {
      hosts.add(`${file}: ${raw.replace(/\$\{[^}]*\}/g, 'label')}`);
    }
    for (const [, raw] of src.matchAll(/\bhost:\s*[`'"]([^`'"]+)[`'"]/g)) hosts.add(`${file}: ${raw}`);
  }
  return [...hosts];
}

test('every screen fixture host is on the screens allow-list', () => {
  const found = fixtureHosts();
  assert.ok(found.length >= 5, `expected the known fixture hosts, found ${found.length}`);
  const refused = found.filter((entry) => !allows(SCREEN_ALLOWED_HOSTS, entry.split(': ')[1]));
  assert.deepEqual(refused, [], 'add the host to SCREEN_ALLOWED_HOSTS (tests/screens/screen-hosts.mjs)');
});

test('the allow-list holds no loopback or IP literal', () => {
  for (const p of SCREEN_ALLOWED_HOSTS) {
    assert.doesNotMatch(p, /^(\*\.)?(localhost|.*\.localhost)$/i, p);
    assert.doesNotMatch(p, /^[\d.]+$|:/, p);
  }
});

test('the boot env carries the list as the comma-joined app setting', () => {
  assert.deepEqual(screenAppEnv(), { PLATFORM_CONFIG_DESTINATIONS_ALLOWED_HOSTS: SCREEN_ALLOWED_HOSTS.join(',') });
});

test('the deliberately unlisted host stays off the allow-list (the refusal spec depends on it)', () => {
  assert.equal(allows(SCREEN_ALLOWED_HOSTS, SCREEN_UNLISTED_HOST), false);
  assert.equal(allows(SCREEN_ALLOWED_HOSTS, 'partner.example.test'), true, 'the control host of the same spec stays listed');
});
