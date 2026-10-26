// BS-14 (lane 4): the pure focus-indicator judge (WCAG 2.4.7 visible + 1.4.11 >= 3:1). node --test.

import test from 'node:test';
import assert from 'node:assert/strict';
import { parseBoxShadow, focusIndicator } from '../lib/focus.mjs';

const WHITE = { r: 255, g: 255, b: 255, a: 1 };
const SLATE = { r: 30, g: 41, b: 59, a: 1 };

test('parseBoxShadow splits layers outside the colour parentheses', () => {
  assert.deepEqual(parseBoxShadow('none'), []);
  assert.deepEqual(parseBoxShadow(''), []);
  const [ring] = parseBoxShadow('rgba(37, 99, 235, 0.22) 0px 0px 0px 3px');
  assert.deepEqual(ring, { color: { r: 37, g: 99, b: 235, a: 0.22 }, x: 0, y: 0, blur: 0, spread: 3, inset: false });
  const two = parseBoxShadow('rgb(0, 0, 0) 0px 1px 2px 0px, rgba(15, 23, 42, 0.1) 0px 8px 24px -8px');
  assert.equal(two.length, 2);
  assert.equal(two[1].y, 8);
  assert.equal(two[1].spread, -8);
  assert.equal(parseBoxShadow('rgb(1, 2, 3) 0px 0px 0px 2px inset')[0].inset, true);
});

test('a 2px solid outline in the focus blue is visible and clears 3:1 on white', () => {
  const r = focusIndicator({ outlineStyle: 'solid', outlineWidth: '2px', outlineColor: 'rgb(37, 99, 235)', boxShadow: 'none' }, WHITE);
  assert.equal(r.kind, 'outline');
  assert.equal(r.visible, true);
  assert.equal(r.passes, true);
  assert.ok(r.ratio > 5 && r.ratio < 5.3);
});

test('no outline and no ring is no focus indicator', () => {
  for (const style of [
    { outlineStyle: 'none', outlineWidth: '0px', outlineColor: 'rgb(0, 0, 0)', boxShadow: 'none' },
    { outlineStyle: 'solid', outlineWidth: '0px', outlineColor: 'rgb(0, 0, 0)', boxShadow: 'none' },
    { outlineStyle: 'solid', outlineWidth: '2px', outlineColor: 'rgba(0, 0, 0, 0)', boxShadow: 'none' },
  ]) {
    const r = focusIndicator(style, WHITE);
    assert.equal(r.visible, false);
    assert.equal(r.passes, false);
    assert.equal(r.kind, 'none');
  }
});

test('a low-contrast outline is visible but fails 3:1', () => {
  const r = focusIndicator({ outlineStyle: 'solid', outlineWidth: '2px', outlineColor: 'rgb(200, 200, 200)', boxShadow: 'none' }, WHITE);
  assert.equal(r.visible, true);
  assert.equal(r.passes, false);
  assert.ok(r.ratio < 3);
});

test('a dark-theme light-blue outline passes against the dark surface', () => {
  const r = focusIndicator({ outlineStyle: 'solid', outlineWidth: '2px', outlineColor: 'rgb(147, 197, 253)', boxShadow: 'none' }, SLATE);
  assert.equal(r.passes, true);
});

test('a spread box-shadow ring counts when there is no outline; a drop shadow does not', () => {
  const ring = focusIndicator({ outlineStyle: 'none', outlineWidth: '0px', outlineColor: 'rgb(0, 0, 0)', boxShadow: 'rgb(37, 99, 235) 0px 0px 0px 3px' }, WHITE);
  assert.equal(ring.kind, 'shadow');
  assert.equal(ring.passes, true);
  const drop = focusIndicator({ outlineStyle: 'none', outlineWidth: '0px', outlineColor: 'rgb(0, 0, 0)', boxShadow: 'rgba(15, 23, 42, 0.1) 0px 8px 24px -8px' }, WHITE);
  assert.equal(drop.visible, false);
});

test('outline-style auto (the browser default ring) counts as visible with its colour', () => {
  const r = focusIndicator({ outlineStyle: 'auto', outlineWidth: '1px', outlineColor: 'rgb(16, 16, 16)', boxShadow: 'none' }, WHITE);
  assert.equal(r.visible, true);
  assert.equal(r.passes, true);
});

test('an unparseable colour is reported as unmeasured, not as a pass or a fail', () => {
  const r = focusIndicator({ outlineStyle: 'solid', outlineWidth: '2px', outlineColor: 'oklab(0.5 0 0)', boxShadow: 'none' }, WHITE);
  assert.equal(r.measured, false);
});
