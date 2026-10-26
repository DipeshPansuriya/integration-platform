// node --test tests/release1/unit/pending-gate.test.mjs  (excluded from the Playwright run by testIgnore).
// The Release 1 gate must follow the code: a true flag needs its probes; a false flag whose probes all hold is stale.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ON_MAIN } from '../pending.mjs';
import { PROBES } from '../pending-probes.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(here, '..', '..', '..', '..');
const JAVA = path.join(ROOT, 'platform-e2e/src/test/java/io/integrationplatform/e2e/release1/Release1Pending.java');

const probeHolds = ([file, needle]) => {
  const abs = path.resolve(ROOT, file);
  return existsSync(abs) && readFileSync(abs, 'utf8').includes(needle);
};
const landed = (item) => PROBES[item].every(probeHolds);

test('every flag has at least one probe, and every probe names a real item', () => {
  assert.deepEqual(Object.keys(PROBES).sort(), Object.keys(ON_MAIN).sort());
  for (const [item, list] of Object.entries(PROBES)) assert.ok(list.length > 0, item);
});

test('every ON_MAIN=true item has all its probes (file + needle) in the tree', () => {
  const missing = Object.keys(ON_MAIN).filter((i) => ON_MAIN[i] && !landed(i));
  assert.deepEqual(missing, [], `flag true but code missing: ${missing.join(', ')}`);
});

test('no stale gate: an ON_MAIN=false item whose probes all hold must be flipped', () => {
  const stale = Object.keys(ON_MAIN).filter((i) => !ON_MAIN[i] && landed(i));
  assert.deepEqual(stale, [], `flag false but code is on main - flip here and in Release1Pending.java: ${stale.join(', ')}`);
});

test('the probe check can fail: a missing file or needle is not a hold', () => {
  assert.equal(probeHolds(['ui/src/App.tsx', 'NO-SUCH-NEEDLE-\u0001']), false);
  assert.equal(probeHolds(['no/such/file.java', 'x']), false);
});

test('pending.mjs and Release1Pending.java carry the same flags', () => {
  const src = readFileSync(JAVA, 'utf8');
  const java = {};
  for (const m of src.matchAll(/flags\.put\("(T\d+)",\s*(true|false)\)/g)) java[m[1]] = m[2] === 'true';
  assert.ok(Object.keys(java).length > 0, 'parsed no flags.put lines from the Java map');
  assert.deepEqual(java, { ...ON_MAIN });
});
