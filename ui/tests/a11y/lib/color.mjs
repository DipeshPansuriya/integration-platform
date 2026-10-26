// BS-14 (lane 4): pure colour maths for the WCAG AA contrast checks - no browser, no dependency.
// Unit: tests/a11y/unit/color.test.mjs. The in-page sampler (probes.mjs) only gathers raw computed
// strings; every decision is made here so it can be tested without a browser.

/** A colour as {r,g,b,a} (0-255, alpha 0-1), or null when the string is a format we cannot read. */
export function parseColor(input) {
  if (typeof input !== 'string') return null;
  const s = input.trim().toLowerCase();
  if (s === '') return null;
  if (s === 'transparent') return { r: 0, g: 0, b: 0, a: 0 };

  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/.exec(s);
  if (hex) {
    const h = hex[1].length === 3 ? [...hex[1]].map((c) => c + c).join('') : hex[1];
    return { r: parseInt(h.slice(0, 2), 16), g: parseInt(h.slice(2, 4), 16), b: parseInt(h.slice(4, 6), 16), a: 1 };
  }

  const alphaOf = (raw, pct) => {
    if (raw === undefined) return 1;
    const n = parseFloat(raw);
    return Math.min(1, Math.max(0, pct ? n / 100 : n));
  };

  const fn = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:\s*[,/]\s*([\d.]+)(%?))?\s*\)$/.exec(s);
  if (fn) return { r: +fn[1], g: +fn[2], b: +fn[3], a: alphaOf(fn[4], fn[5] === '%') };

  const srgb = /^color\(\s*srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+)(%?))?\s*\)$/.exec(s);
  if (srgb) return { r: +srgb[1] * 255, g: +srgb[2] * 255, b: +srgb[3] * 255, a: alphaOf(srgb[4], srgb[5] === '%') };

  return null;
}

/** `fg` painted over `bg` (both {r,g,b,a}). */
export function compositeOver(fg, bg) {
  const a = fg.a + bg.a * (1 - fg.a);
  if (a === 0) return { r: 0, g: 0, b: 0, a: 0 };
  const mix = (f, b) => (f * fg.a + b * bg.a * (1 - fg.a)) / a;
  return { r: mix(fg.r, bg.r), g: mix(fg.g, bg.g), b: mix(fg.b, bg.b), a };
}

const linear = (channel) => {
  const c = channel / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
};

/** WCAG 2.x relative luminance of an opaque {r,g,b}. */
export function relativeLuminance({ r, g, b }) {
  return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
}

/** WCAG contrast ratio of two opaque colours, 4 decimals (1 to 21). */
export function contrastRatio(a, b) {
  const [hi, lo] = [relativeLuminance(a), relativeLuminance(b)].sort((x, y) => y - x);
  return Math.round(((hi + 0.05) / (lo + 0.05)) * 1e4) / 1e4;
}

const round2 = (n) => Math.round(n * 100) / 100;

/** WCAG 1.4.3 "large text": >= 24px, or >= 18.66px (14pt) and bold (>= 700). Accepts computed strings. */
export function isLargeText(fontSize, fontWeight) {
  const px = parseFloat(fontSize);
  const weight = fontWeight === 'bold' ? 700 : parseInt(fontWeight, 10);
  return px >= 24 || (px >= 18.66 && weight >= 700);
}

/** The AA ratio the text must reach. */
export function requiredRatio(fontSize, fontWeight) {
  return isLargeText(fontSize, fontWeight) ? 3 : 4.5;
}

/**
 * What an element's text actually sits on. `layers` run nearest ancestor first, each
 * {color, image?} (computed background-color / background-image). Stops at the first opaque layer;
 * nothing painted = the browser's white canvas. An image or an unreadable colour makes the answer
 * indeterminate - the caller reports it as not measured, never as a pass or a fail.
 */
export function effectiveBackground(layers) {
  const stack = [];
  for (const layer of layers) {
    if (layer.image && layer.image !== 'none') return { color: null, indeterminate: true };
    const color = parseColor(layer.color);
    if (color === null) return { color: null, indeterminate: true };
    if (color.a === 0) continue;
    stack.push(color);
    if (color.a === 1) break;
  }
  let out = { r: 255, g: 255, b: 255, a: 1 };
  for (const layer of stack.reverse()) out = compositeOver(layer, out);
  return { color: out, indeterminate: false };
}

/** The text colour as painted: its own alpha times the ancestors' opacity, over `background`. */
export function effectiveForeground(color, opacity, background) {
  const parsed = parseColor(color);
  if (parsed === null) return null;
  return compositeOver({ ...parsed, a: parsed.a * opacity }, background);
}

/**
 * Judge raw in-page samples ({selector, text, color, fontSize, fontWeight, opacity, layers, count}).
 * Returns {checked, skipped, failures[]} - skipped = indeterminate background or unreadable colour.
 */
export function evaluateTextSamples(samples) {
  let checked = 0;
  let skipped = 0;
  const failures = [];
  for (const sample of samples) {
    const bg = effectiveBackground(sample.layers);
    const fg = bg.indeterminate ? null : effectiveForeground(sample.color, sample.opacity ?? 1, bg.color);
    if (fg === null) {
      skipped += 1;
      continue;
    }
    checked += 1;
    const ratio = round2(contrastRatio(fg, bg.color));
    const required = requiredRatio(sample.fontSize, sample.fontWeight);
    if (ratio < required) {
      failures.push({ selector: sample.selector, text: sample.text, ratio, required, color: sample.color, fontSize: sample.fontSize, count: sample.count ?? 1 });
    }
  }
  return { checked, skipped, failures };
}
