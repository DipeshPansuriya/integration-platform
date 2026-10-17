// T131 (row 323, F21 BS-C) - the ONE registry of names BS-08 / BS-09 / BS-10 use that are not on main yet,
// FAILING FIRST. No docker: pure functions of tests/base-suite/lib/pending-names.mjs.
//
// Two kinds of entry, one line each, so the Tue 20 run swaps one line per name:
//   provisional  measured on a lane (t112 @21f6835 / T110 2a9c835), not on main - used as is, re-measured Tue 20
//   TBD(<item>)  not known until the item lands - the scenario FAILS on it with the item named, never guesses

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { NAMES, fill, isPending, nameOf, pendingNames } from '../lib/pending-names.mjs';

test('the screens and reads measured on lane t112 / T110 are provisional values, not placeholders', () => {
  assert.equal(nameOf('detailRoute'), '#/messages/{trackingId}');
  assert.equal(nameOf('failuresRoute'), '#/failures');
  assert.equal(nameOf('timelineApi'), '/api/v1/admin/messages/{trackingId}/timeline');
  assert.equal(nameOf('failuresApi'), '/api/v1/admin/messages/failures');
});

test('a TBD name fails the step, naming the key and the item it waits for', () => {
  assert.throws(() => nameOf('replayApi'), /replayApi is still TBD\(T111\).*pending-names\.mjs/);
  assert.throws(() => nameOf('logFieldCorrelation'), /TBD\(T107\)/);
  assert.throws(() => nameOf('kafkaContainerEnv'), /TBD\(T106\)/);
});

test('an unknown key is refused - a typo never reads as a name', () => {
  assert.throws(() => nameOf('replayAPI'), /unknown name replayAPI/);
});

test('isPending: any value reading TBD( - any case - is a placeholder', () => {
  assert.equal(isPending('TBD(T111)'), true);
  assert.equal(isPending('tbd(t111)'), true);
  assert.equal(isPending('#/failures'), false);
  assert.equal(isPending(''), true, 'an empty name is never a configuration');
});

test('pendingNames lists every placeholder still open - the handoff list comes from here', () => {
  const open = pendingNames();
  assert.ok(open.length >= 10, `at least the T106/T107/T109/T111 names: ${JSON.stringify(open)}`);
  for (const [key, value] of open) {
    assert.ok(isPending(value), key);
    assert.match(value, /^TBD\(T\d{3}(\|T\d{3})*\)$/, `${key}: one item-named placeholder`);
  }
  assert.ok(open.some(([key]) => key === 'replayApi'));
  assert.ok(!open.some(([key]) => key === 'detailRoute'));
});

test('the registry is frozen - a spec cannot patch a name at run time', () => {
  assert.ok(Object.keys(NAMES).length > 0, 'the registry has names');
  assert.equal(Object.isFrozen(NAMES), true);
});

test('fill: each {param} is replaced, URI-encoded; a missing param or a leftover brace fails', () => {
  assert.equal(fill('#/messages/{trackingId}', { trackingId: 'a b' }), '#/messages/a%20b');
  assert.equal(fill('/x/{a}/y/{b}', { a: '1', b: '2' }), '/x/1/y/2');
  assert.throws(() => fill('/x/{a}', {}), /missing a/);
  assert.throws(() => fill('TBD(T111)', {}), /still TBD/);
});
