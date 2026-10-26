// L28 (row 372): the JS gate (tests/release1/pending.mjs) is the twin of platform-e2e Release1Pending.java.
// A twin that drifts hides a journey behind test.fixme (the 24-Oct audit found T98 and T143 stale). This
// test reads the Java file and fails on ANY difference, in either direction. Offline; no docker.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { ON_MAIN, waitsOn, waitsOnIn } from '../pending.mjs';

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

// A synthetic map, so the gate can fail whatever ON_MAIN holds today (T121 was the real "not on main" example until it landed).
test('the gate names what is missing: an item not on main yields "waits on X", all on main yields null', () => {
  const map = { A1: true, B2: false, C3: false };
  assert.match(waitsOnIn(map, 'A1', 'B2'), /^waits on B2 \(not on main\)/);
  assert.match(waitsOnIn(map, 'B2', 'C3'), /waits on B2, C3/);
  assert.equal(waitsOnIn(map, 'A1'), null);
  assert.equal(waitsOnIn({ A1: true, B2: true }, 'A1', 'B2'), null);
  assert.throws(() => waitsOnIn(map, 'Z9'), /unknown/);
});

test('the real waitsOn is waitsOnIn over ON_MAIN', () => {
  const items = Object.keys(ON_MAIN);
  assert.equal(waitsOn(...items), waitsOnIn(ON_MAIN, ...items));
  assert.equal(waitsOn(...items) === null, items.every((item) => ON_MAIN[item]));
});

test('waitsOn refuses an unknown item and an empty call (the throw paths the old @negative test held)', () => {
  assert.throws(() => waitsOn('T999'), /unknown/);
  assert.throws(() => waitsOn(), /at least one/);
});
