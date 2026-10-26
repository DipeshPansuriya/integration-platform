// L28 (row 372): the JS gate (tests/release1/pending.mjs) is the twin of platform-e2e Release1Pending.java.
// A twin that drifts hides a journey behind test.fixme (the 24-Oct audit found T98 and T143 stale). This
// test reads the Java file and fails on ANY difference, in either direction. Offline; no docker.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { ON_MAIN, waitsOn } from '../pending.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const JAVA = path.resolve(HERE, '../../../../platform-e2e/src/test/java/io/integrationplatform/e2e/release1/Release1Pending.java');

function javaFlags() {
  const text = fs.readFileSync(JAVA, 'utf8');
  return Object.fromEntries([...text.matchAll(/flags\.put\("(T\d+)",\s*(true|false)\)/g)].map(([, item, value]) => [item, value === 'true']));
}

test('pending.mjs ON_MAIN equals Release1Pending.java ON_MAIN, item by item', () => {
  const java = javaFlags();
  assert.ok(Object.keys(java).length >= 15, 'the Java flags were parsed');
  assert.deepEqual({ ...ON_MAIN }, java);
});

test('the items the journey waits on (T98 MQ target, T143 e2e profile) are on main', () => {
  assert.equal(waitsOn('T98', 'T143'), null);
});

// T121 (report screen) was the "not on main" example until it landed; the check now follows the map, so it never goes stale again.
test('the gate names what is missing: the first item not on main, or null when everything is', () => {
  const missing = Object.keys(ON_MAIN).find((item) => !ON_MAIN[item]);
  if (missing) assert.match(waitsOn('T95', missing), new RegExp(`waits on ${missing}`));
  else assert.equal(waitsOn(...Object.keys(ON_MAIN)), null);
});

test('waitsOn refuses an unknown item and an empty call (the throw paths the old @negative test held)', () => {
  assert.throws(() => waitsOn('T999'), /unknown/);
  assert.throws(() => waitsOn(), /at least one/);
});
