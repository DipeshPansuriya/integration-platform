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

/** one probe against a given file text (the negative-control seam): the needle is there; for an `absent` probe, it is not. */
function holds({ needle, absent }, text) {
  return absent ? !text.includes(needle) : text.includes(needle);
}

/** true when every probe of the item holds in this tree (file holds the needle; for an `absent` probe: the file exists without it). */
function probePresent(item) {
  const probes = pending.ON_MAIN_PROBES?.[item];
  assert.ok(Array.isArray(probes) && probes.length > 0, `${item}: no probe in ON_MAIN_PROBES (file + needle)`);
  return probes.every(({ file, needle, absent }) => {
    assert.ok(file && needle, `${item}: a probe needs both file and needle`);
    const full = path.resolve(REPO, file);
    return existsSync(full) && holds({ needle, absent }, readFileSync(full, 'utf8')); // absent: the landed state is "the needle is gone"
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

// Negative control for DELIVERY_BASE_URL: the flag is only worth flipping if its probe FAILS once the product call is gone.
// (The first probe was an `absent` check on 'https://endpoint.invalid/', which stays in JdbcSystemRepository.java as the null
// fallback - it could never turn true. These probes are positive and are proven both ways on the real file text.)
test('DELIVERY_BASE_URL probes hold on the real SystemAdminService and fail when the baseUrl call is removed', () => {
  const probes = pending.ON_MAIN_PROBES.DELIVERY_BASE_URL;
  assert.ok(probes.length >= 2 && probes.every((p) => !p.absent), 'DELIVERY_BASE_URL probes must be positive (needle present)');
  const real = readFileSync(path.resolve(REPO, probes[0].file), 'utf8');
  for (const p of probes) assert.equal(holds(p, real), true, `real tree: ${p.needle}`);
  // create path loses its baseUrl argument (the pre-73257d41 shape: the placeholder endpoint)
  const createMutated = real.replace('systems.insertDefaultEndpoint(id, code, name, baseUrl)', 'systems.insertDefaultEndpoint(id, code, name)');
  assert.notEqual(createMutated, real, 'mutation must change the text');
  assert.equal(holds(probes[0], createMutated), false, 'probe must fail when create stops passing baseUrl');
  // save path no longer updates the endpoint
  const saveMutated = real.replace('systems.updateEndpointBaseUrl(current.id(), baseUrl)', 'noop()');
  assert.notEqual(saveMutated, real, 'mutation must change the text');
  assert.equal(holds(probes[1], saveMutated), false, 'probe must fail when save stops updating the endpoint');
});

test('an absent probe cannot be satisfied by a literal that stays in the file (why the old DELIVERY_BASE_URL probe was wrong)', () => {
  const fallback = "coalesce(:base_url::text, 'https://endpoint.invalid/')";
  assert.equal(holds({ needle: 'https://endpoint.invalid/', absent: true }, fallback), false);
});
