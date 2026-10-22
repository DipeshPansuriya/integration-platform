// BS-14 (lane 4): the pure focus-indicator judge. WCAG 2.4.7 (a visible indicator) and 1.4.11 (the
// indicator reaches 3:1 against the background it sits on). The in-page probe hands over the focused
// element's computed outline / box-shadow strings and its effective background; no decision lives
// in the browser. Unit: tests/a11y/unit/focus.test.mjs.

import { compositeOver, contrastRatio, parseColor } from './color.mjs';

const COLOR_TOKEN = /(?:rgba?|color|hsla?|oklab|oklch|lab|lch)\([^)]*\)|#[0-9a-fA-F]{3,8}\b/;

function splitTopLevel(text) {
  const parts = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < text.length; i += 1) {
    if (text[i] === '(') depth += 1;
    else if (text[i] === ')') depth -= 1;
    else if (text[i] === ',' && depth === 0) {
      parts.push(text.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(text.slice(start));
  return parts.map((p) => p.trim()).filter(Boolean);
}

/** computed `box-shadow` -> [{color, x, y, blur, spread, inset}] (color null when unreadable). */
export function parseBoxShadow(text) {
  if (!text || text.trim() === 'none') return [];
  return splitTopLevel(text).map((layer) => {
    const colorToken = COLOR_TOKEN.exec(layer)?.[0] ?? null;
    const rest = colorToken === null ? layer : layer.replace(colorToken, ' ');
    const inset = /\binset\b/.test(rest);
    const [x = 0, y = 0, blur = 0, spread = 0] = rest.replace(/\binset\b/, ' ').trim().split(/\s+/).filter(Boolean).map(parseFloat);
    return { color: colorToken === null ? null : parseColor(colorToken), x, y, blur, spread, inset };
  });
}

const measure = (paint, background) => {
  if (paint === null) return { visible: true, measured: false, ratio: null, passes: false };
  if (paint.a === 0) return null;
  const ratio = Math.round(contrastRatio(compositeOver(paint, background), background) * 100) / 100;
  return { visible: true, measured: true, ratio, passes: ratio >= 3 };
};

/**
 * Judge one focused element. `style` = {outlineStyle, outlineWidth, outlineColor, boxShadow} (computed),
 * `background` = the opaque {r,g,b,a} behind it. Returns {kind: 'outline'|'shadow'|'none', visible,
 * measured, ratio, passes}; the best candidate wins.
 */
export function focusIndicator(style, background) {
  const candidates = [];

  const outlineOn = !['none', 'hidden', ''].includes(style.outlineStyle ?? 'none') && parseFloat(style.outlineWidth) > 0;
  if (outlineOn) {
    const m = measure(parseColor(style.outlineColor), background);
    if (m) candidates.push({ kind: 'outline', ...m });
  }

  for (const layer of parseBoxShadow(style.boxShadow)) {
    const ring = layer.x === 0 && layer.y === 0 && (layer.spread > 0 || layer.blur > 0);
    if (!ring) continue; // an offset shadow is elevation, not a focus indicator
    const m = measure(layer.color, background);
    if (m) candidates.push({ kind: 'shadow', ...m });
  }

  if (candidates.length === 0) return { kind: 'none', visible: false, measured: true, ratio: null, passes: false };
  const rank = (c) => (c.passes ? 2 : c.measured ? 1 : 0) * 1000 + (c.ratio ?? 0);
  return candidates.sort((a, b) => rank(b) - rank(a))[0];
}
