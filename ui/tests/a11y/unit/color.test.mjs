// BS-14 (lane 4): the pure colour maths behind the WCAG AA contrast checks. node --test, no browser.
// RED first: ../lib/color.mjs did not exist when this file was written.

import test from 'node:test';
import assert from 'node:assert/strict';
import {
  parseColor, compositeOver, relativeLuminance, contrastRatio, isLargeText, requiredRatio,
  effectiveBackground, effectiveForeground, evaluateTextSamples,
} from '../lib/color.mjs';

const rgb = (r, g, b, a = 1) => ({ r, g, b, a });
const approx = (actual, expected, eps = 0.01) => assert.ok(Math.abs(actual - expected) <= eps, `${actual} !~ ${expected}`);

test('parseColor reads the shapes Chromium reports for computed colours', () => {
  assert.deepEqual(parseColor('rgb(0, 0, 0)'), rgb(0, 0, 0, 1));
  assert.deepEqual(parseColor('rgba(15, 23, 42, 0.5)'), rgb(15, 23, 42, 0.5));
  assert.deepEqual(parseColor('rgb(15 23 42 / 50%)'), rgb(15, 23, 42, 0.5));
  assert.deepEqual(parseColor('#fff'), rgb(255, 255, 255, 1));
  assert.deepEqual(parseColor('#0f172a'), rgb(15, 23, 42, 1));
  assert.deepEqual(parseColor('transparent'), rgb(0, 0, 0, 0));
  assert.deepEqual(parseColor('rgba(0, 0, 0, 0)'), rgb(0, 0, 0, 0));
  assert.deepEqual(parseColor('color(srgb 1 0 0 / 0.5)'), rgb(255, 0, 0, 0.5));
});

test('parseColor answers null for what it cannot read (never a guessed colour)', () => {
  assert.equal(parseColor('oklab(0.5 0.1 0.1)'), null);
  assert.equal(parseColor('not-a-colour'), null);
  assert.equal(parseColor(''), null);
  assert.equal(parseColor(undefined), null);
});

test('compositeOver blends a translucent colour onto an opaque one', () => {
  const out = compositeOver(rgb(0, 0, 0, 0.5), rgb(255, 255, 255, 1));
  approx(out.r, 127.5, 0.001);
  assert.equal(out.a, 1);
  assert.deepEqual(compositeOver(rgb(10, 20, 30, 1), rgb(255, 255, 255, 1)), rgb(10, 20, 30, 1));
  assert.deepEqual(compositeOver(rgb(10, 20, 30, 0), rgb(1, 2, 3, 1)), rgb(1, 2, 3, 1));
});

test('relativeLuminance and contrastRatio match the WCAG 2.x reference values', () => {
  assert.equal(relativeLuminance(rgb(0, 0, 0)), 0);
  assert.equal(relativeLuminance(rgb(255, 255, 255)), 1);
  assert.equal(contrastRatio(rgb(0, 0, 0), rgb(255, 255, 255)), 21);
  assert.equal(contrastRatio(rgb(255, 255, 255), rgb(0, 0, 0)), 21, 'symmetric');
  assert.equal(contrastRatio(rgb(9, 9, 9), rgb(9, 9, 9)), 1);
  approx(contrastRatio(parseColor('#767676'), parseColor('#ffffff')), 4.54);
  approx(contrastRatio(parseColor('#777777'), parseColor('#ffffff')), 4.48);
  approx(contrastRatio(parseColor('#2563eb'), parseColor('#ffffff')), 5.17); // tokens.css: white on accent
});

test('isLargeText / requiredRatio: 24px, or 18.66px bold, is large (3:1); everything else 4.5:1', () => {
  assert.equal(isLargeText(24, 400), true);
  assert.equal(isLargeText(18.66, 700), true);
  assert.equal(isLargeText('18.67px', '700'), true, 'computed strings are accepted');
  assert.equal(isLargeText(18.66, 400), false);
  assert.equal(isLargeText(18.5, 700), false);
  assert.equal(isLargeText(14, 700), false);
  assert.equal(requiredRatio(24, 400), 3);
  assert.equal(requiredRatio(14, 400), 4.5);
});

test('effectiveBackground stacks ancestor layers (nearest first) over the white canvas', () => {
  assert.deepEqual(effectiveBackground([{ color: 'rgba(0, 0, 0, 0)' }, { color: 'rgb(30, 41, 59)' }]), { color: rgb(30, 41, 59, 1), indeterminate: false });
  const half = effectiveBackground([{ color: 'rgba(0, 0, 0, 0.5)' }]);
  approx(half.color.r, 127.5, 0.001);
  assert.deepEqual(effectiveBackground([{ color: 'rgba(0, 0, 0, 0)' }]), { color: rgb(255, 255, 255, 1), indeterminate: false });
  // an image/gradient layer cannot be measured: indeterminate, never a guessed pass or fail
  assert.equal(effectiveBackground([{ color: 'rgba(0, 0, 0, 0)', image: 'linear-gradient(red, blue)' }, { color: 'rgb(255, 255, 255)' }]).indeterminate, true);
  assert.equal(effectiveBackground([{ color: 'oklab(0.5 0 0)' }]).indeterminate, true);
});

test('effectiveForeground applies the ancestors\' opacity before blending onto the background', () => {
  const out = effectiveForeground('rgb(0, 0, 0)', 0.5, rgb(255, 255, 255, 1));
  approx(out.r, 127.5, 0.001);
  assert.equal(effectiveForeground('oklab(0.5 0 0)', 1, rgb(255, 255, 255, 1)), null);
});

test('evaluateTextSamples: passes AA text, fails the grey-on-white one, counts what it cannot measure', () => {
  const base = { selector: 'p', text: 'x', fontSize: '14px', fontWeight: '400', opacity: 1, count: 1 };
  const out = evaluateTextSamples([
    { ...base, selector: 'p.ok', color: 'rgb(15, 23, 42)', layers: [{ color: 'rgb(255, 255, 255)' }] },
    { ...base, selector: 'p.grey', color: 'rgb(150, 150, 150)', layers: [{ color: 'rgb(255, 255, 255)' }] },
    { ...base, selector: 'h2.largegrey', fontSize: '24px', color: 'rgb(130, 130, 130)', layers: [{ color: 'rgb(255, 255, 255)' }] }, // ~3.5:1, large -> passes 3:1
    { ...base, selector: 'p.img', color: 'rgb(0, 0, 0)', layers: [{ color: 'rgba(0, 0, 0, 0)', image: 'url(x.png)' }] },
    { ...base, selector: 'p.weird', color: 'oklab(0.5 0 0)', layers: [{ color: 'rgb(255, 255, 255)' }] },
  ]);
  assert.equal(out.checked, 3);
  assert.equal(out.skipped, 2);
  assert.deepEqual(out.failures.map((f) => f.selector), ['p.grey']);
  assert.equal(out.failures[0].required, 4.5);
  assert.ok(out.failures[0].ratio < 4.5 && out.failures[0].ratio > 2.5);
});
