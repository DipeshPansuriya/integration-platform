// BS-14 (lane 4): the pure Tab-walk judge. The spec presses Tab and records which control holds focus
// after each press (a stable id, or null when focus left the document); this decides trap / coverage /
// order. node --test, no browser.

import test from 'node:test';
import assert from 'node:assert/strict';
import { analyseTabWalk } from '../lib/tab-walk.mjs';

test('a clean walk visits every expected control in order and wraps out of the page', () => {
  const r = analyseTabWalk({ expected: ['a', 'b', 'c'], visited: ['a', 'b', 'c', null] });
  assert.deepEqual(r, { missing: [], extra: [], outOfOrder: [], trap: false, completed: true });
});

test('wrapping straight back to the first control is also a clean end', () => {
  const r = analyseTabWalk({ expected: ['a', 'b'], visited: ['a', 'b', 'a'] });
  assert.equal(r.trap, false);
  assert.equal(r.completed, true);
});

test('a keyboard trap: focus cycles among a subset and never reaches the rest', () => {
  const r = analyseTabWalk({ expected: ['a', 'b', 'c', 'd'], visited: ['a', 'b', 'a', 'b', 'a', 'b'] });
  assert.equal(r.trap, true);
  assert.deepEqual(r.missing, ['c', 'd']);
  assert.equal(r.completed, false);
});

test('a control Tab never reaches is reported missing (without being a trap when focus left)', () => {
  const r = analyseTabWalk({ expected: ['a', 'b', 'c'], visited: ['a', 'c', null] });
  assert.deepEqual(r.missing, ['b']);
  assert.equal(r.trap, false);
  assert.equal(r.completed, false);
});

test('out-of-order: a control reached after one that follows it in the page', () => {
  const r = analyseTabWalk({ expected: ['a', 'b', 'c'], visited: ['a', 'c', 'b', null] });
  assert.deepEqual(r.outOfOrder, ['b']);
});

test('extra: a stop that is not in the expected list is reported (e.g. a hidden tabindex=0 div)', () => {
  const r = analyseTabWalk({ expected: ['a', 'b'], visited: ['a', 'ghost', 'b', null] });
  assert.deepEqual(r.extra, ['ghost']);
  assert.equal(r.completed, true);
});

test('an empty page with nothing focusable is complete, not a failure', () => {
  assert.equal(analyseTabWalk({ expected: [], visited: [null] }).completed, true);
});
