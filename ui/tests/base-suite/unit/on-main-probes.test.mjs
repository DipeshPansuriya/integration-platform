// A flag in ON_MAIN can never go stale again: every T-item carries ONE probe (a file + a needle that proves the
// feature is in the tree), and the flag must equal the probe. A true flag with no probe or a missing needle fails;
// a false flag whose probe is present fails too (that is how T112/T113 stayed false after they landed).
// No docker: it reads source files from the working tree this suite is checked out in.

import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import * as pending from '../lib/pending-names.mjs';

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');

/** true when every probe of the item (file exists and holds the needle) is present in this tree. */
function probePresent(item) {
  const probes = pending.ON_MAIN_PROBES?.[item];
  assert.ok(Array.isArray(probes) && probes.length > 0, `${item}: no probe in ON_MAIN_PROBES (file + needle)`);
  return probes.every(({ file, needle }) => {
    assert.ok(file && needle, `${item}: a probe needs both file and needle`);
    const full = path.resolve(REPO, file);
    return existsSync(full) && readFileSync(full, 'utf8').includes(needle);
  });
}

test('ON_MAIN_PROBES is exported and frozen', () => {
  assert.equal(typeof pending.ON_MAIN_PROBES, 'object', 'ON_MAIN_PROBES must be exported');
  assert.equal(Object.isFrozen(pending.ON_MAIN_PROBES), true);
});

test('every ON_MAIN item has a probe and no probe is orphaned', () => {
  assert.deepEqual(Object.keys(pending.ON_MAIN_PROBES ?? {}).sort(), Object.keys(pending.ON_MAIN).sort());
});

for (const item of Object.keys(pending.ON_MAIN)) {
  test(`ON_MAIN.${item} equals what the tree shows (probe present = true, absent = false)`, () => {
    assert.equal(probePresent(item), pending.ON_MAIN[item],
      `${item}: ON_MAIN says ${pending.ON_MAIN[item]} but the probe says ${!pending.ON_MAIN[item]} - flip the flag (or fix the probe) in tests/base-suite/lib/pending-names.mjs`);
  });
}
