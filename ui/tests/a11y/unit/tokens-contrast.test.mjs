// BS-14 (lane 4): the design tokens' own WCAG AA pairs, computed from src/tokens.css with the same
// contrast helper the browser specs use. Static (no browser, no backend) so it runs in `node --test` now;
// the routes spec then proves the PAINTED pages. Text pairs >= 4.5:1 (1.4.3), control boundary and focus
// ring >= 3:1 against their surfaces (1.4.11). Translucent tokens are composited over their surface.
// shell-tokens.test.mjs pins the token VALUES; this pins the RATIOS they promise.

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { compositeOver, contrastRatio, parseColor } from '../lib/color.mjs';

const css = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'src', 'tokens.css'), 'utf8');
const darkAt = css.indexOf('[data-theme="dark"] {');
assert.ok(darkAt > 0, 'tokens.css has a dark block');
const blocks = { light: css.slice(0, darkAt), dark: css.slice(darkAt) };

function tokens(theme) {
  const out = {};
  for (const m of blocks[theme].matchAll(/(--color-[a-z-]+)\s*:\s*([^;]+);/g)) out[m[1]] = m[2].trim();
  return out;
}

/** Colour of `name` for the theme; dark inherits nothing, light is the base. White = literal. */
function colorOf(t, name, over) {
  const raw = name === 'white' ? '#ffffff' : t[`--color-${name}`];
  assert.ok(raw, `token --color-${name} exists`);
  const c = parseColor(raw);
  assert.ok(c, `token --color-${name} (${raw}) is a readable colour`);
  return c.a < 1 && over ? compositeOver(c, over) : c;
}

// [foreground, background, minimum, why]
const TEXT = 4.5;
const NON_TEXT = 3;
const PAIRS = {
  light: [
    ['ink', 'canvas', TEXT], ['ink', 'surface', TEXT], ['ink-muted', 'canvas', TEXT], ['ink-muted', 'surface', TEXT],
    ['ink-faint', 'surface', TEXT], ['ink-faint', 'canvas', TEXT],
    ['link', 'canvas', TEXT], ['link', 'surface', TEXT],
    ['white', 'accent', TEXT, 'primary button'],
    ['accent-faint-ink', 'accent-faint', TEXT],
    ['success-text', 'success-bg', TEXT], ['warn-text', 'warn-bg', TEXT], ['danger-text', 'danger-bg', TEXT],
    ['danger-text', 'surface', TEXT], ['neutral-text', 'neutral-bg', TEXT],
    ['border-control', 'surface', NON_TEXT, 'control boundary 1.4.11'], ['border-control', 'canvas', NON_TEXT, 'control boundary 1.4.11'],
    ['focus', 'canvas', NON_TEXT, 'focus ring 1.4.11'], ['focus', 'surface', NON_TEXT, 'focus ring 1.4.11'],
  ],
  dark: [
    ['ink', 'canvas', TEXT], ['ink', 'surface', TEXT], ['ink', 'surface-raised', TEXT],
    ['ink-muted', 'canvas', TEXT], ['ink-muted', 'surface', TEXT],
    ['ink-faint', 'canvas', TEXT], ['ink-faint', 'surface', TEXT], ['ink-faint', 'surface-raised', TEXT],
    ['link', 'canvas', TEXT], ['link', 'surface', TEXT], ['link', 'surface-raised', TEXT],
    ['white', 'accent-hover', TEXT, 'dark primary button fill'],
    ['accent-faint-ink', 'accent-faint', TEXT],
    ['success-text', 'success-bg', TEXT], ['warn-text', 'warn-bg', TEXT], ['danger-text', 'danger-bg', TEXT],
    ['danger-text', 'surface', TEXT], ['neutral-text', 'neutral-bg', TEXT, 'translucent bg over the surface'],
    ['border-control', 'surface', NON_TEXT, 'control boundary 1.4.11'], ['border-control', 'canvas', NON_TEXT, 'control boundary 1.4.11'],
    ['focus', 'canvas', NON_TEXT, 'focus ring 1.4.11'], ['focus', 'surface', NON_TEXT, 'focus ring 1.4.11'], ['focus', 'surface-raised', NON_TEXT, 'focus ring 1.4.11'],
  ],
};

for (const theme of ['light', 'dark']) {
  const t = tokens(theme);
  const surface = colorOf(t, 'surface');
  test(`${theme}: every design-token colour pair reaches its WCAG AA ratio`, () => {
    const failures = [];
    for (const [fg, bg, min, why] of PAIRS[theme]) {
      const back = colorOf(t, bg, surface);
      const front = colorOf(t, fg, back);
      const ratio = Math.round(contrastRatio(front, back) * 100) / 100;
      if (ratio < min) failures.push(`${fg} on ${bg}${why ? ` (${why})` : ''}: ${ratio}:1 < ${min}:1`);
    }
    assert.deepEqual(failures, [], `token pairs under AA in the ${theme} theme`);
  });
}
