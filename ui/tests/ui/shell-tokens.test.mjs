// T78 lane F (amit-developer): unit assertions for the token layer + app shell.
// Reads the sources as text — no TypeScript loading needed. `npm test` keeps its
// integration-only baseline script; this file runs standalone with:
//   node --test tests/ui/shell-tokens.test.mjs
// It pins the WCAG AA pairs of 01-tokens.md §1, the [data-nav] contract of the
// shell (values must not change — screen e2e keys off them), the sign-out
// semantics markers, the pre-paint theme boot, and the base primitives
// (focus-visible ring, type floor, status never colour-alone).

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const src = join(root, 'src');

function readTokens() {
  const css = readFileSync(join(src, 'tokens.css'), 'utf8');
  const darkAt = css.indexOf('[data-theme="dark"]');
  assert.ok(darkAt > 0, 'tokens.css must declare a dark block');
  return { css, light: css.slice(0, darkAt), dark: css.slice(darkAt) };
}

function varNames(text) {
  const names = new Set();
  for (const m of text.matchAll(/--color-[a-z][a-z-]*\s*:/g)) names.add(m[0].replace(/\s*:$/, ''));
  return names;
}

test('tokens.css declares every colour token as a light+dark pair', () => {
  const { light, dark } = readTokens();
  const lightVars = varNames(light);
  const darkVars = varNames(dark);
  assert.ok(lightVars.size >= 18, `light theme must carry the 01-§1 colour floor (has ${lightVars.size})`);
  assert.deepEqual(darkVars, lightVars, 'dark must mirror exactly the light colour tokens');
});

test('01-§1 acceptance pairs hold in both themes', () => {
  const { light, dark } = readTokens();
  for (const [tok, value, block] of [
    ['--color-canvas', '#f8fafc', light],
    ['--color-surface', '#ffffff', light],
    ['--color-ink', '#0f172a', light],
    ['--color-accent', '#2563eb', light],
    ['--color-accent-hover', '#1d4ed8', light],
    ['--color-success-text', '#15803d', light],
    ['--color-danger-text', '#b91c1c', light],
    ['--color-canvas', '#0f172a', dark],
    ['--color-surface', '#1e293b', dark],
    ['--color-ink', '#e2e8f0', dark],
    ['--color-accent', '#3b82f6', dark],
    ['--color-success-text', '#4ade80', dark],
  ]) {
    assert.ok(block.includes(`${tok}: ${value}`), `${tok} must be ${value} in ${block === dark ? 'dark' : 'light'}`);
  }
});

test('[data-nav] contract: the six screen entries keep their exact values', () => {
  const app = readFileSync(join(src, 'App.tsx'), 'utf8');
  // Values may be declared in the NAV_GROUPS table (`nav: 'flows'`) or inline as
  // data-nav="flows" — either way the exact literal must exist in the shell source.
  for (const entry of ['flows', 'versions', 'systems', 'contracts', 'rules', 'mappings']) {
    const declared = new RegExp(`nav: '${entry}'`).test(app) || app.includes(`data-nav="${entry}"`);
    assert.ok(declared, `data-nav="${entry}" must survive the redesign`);
  }
  assert.ok(app.includes('data-nav={entry.nav}'), 'nav anchors must bind data-nav from the entries table');
});

test('nav entries carry real-link semantics (anchors with hash routes)', () => {
  const app = readFileSync(join(src, 'App.tsx'), 'utf8');
  for (const m of app.matchAll(/<a[^>]*data-nav="[a-z-]+"/g)) {
    const tag = m[0];
    assert.ok(tag.includes('href="#/'), `nav anchor must href a hash route: ${tag}`);
  }
  assert.ok(/<nav[\s>]/.test(app), 'side nav must be a <nav> element');
});

test('sign-out keeps its semantics markers (data-action="logout" + POST /logout)', () => {
  const app = readFileSync(join(src, 'App.tsx'), 'utf8');
  assert.ok(app.includes('data-action="logout"'), 'data-action="logout" must stay');
  assert.ok(app.includes("fetch('/logout', { method: 'POST'"), 'sign-out must stay POST /logout with the redirect body');
  assert.ok(app.includes('X-XSRF-TOKEN'), 'sign-out must keep the CSRF header');
});

test('theme boot is pre-paint (inline, before the app script) and persists', () => {
  const html = readFileSync(join(root, 'index.html'), 'utf8');
  const boot = html.indexOf('localStorage');
  const rootDiv = html.indexOf('src="/src/main.tsx"');
  assert.ok(boot > 0 && boot < rootDiv, 'theme boot must be inline in <head>, before the app loads');
  assert.ok(html.includes('prefers-color-scheme'), 'unset choice falls back to the OS preference');
  assert.ok(/dataset\.theme\s*=/, 'boot must set data-theme on the document element');
});

test('base.css: focus-visible 2px ring everywhere + reduced-motion off + worded badges', () => {
  const base = readFileSync(join(src, 'ui', 'base.css'), 'utf8');
  assert.ok(base.includes('outline: 2px solid var(--color-focus)'), 'focus ring must be the 2px accent token');
  assert.ok(base.includes('prefers-reduced-motion'), 'motion must stop under prefers-reduced-motion');
  assert.ok(base.includes('badge'), 'base.css must ship the badge primitive');
  for (const tok of ['--color-success-text', '--color-warn-text', '--color-danger-text', '--color-accent-faint-ink']) {
    assert.ok(base.includes(tok), `badges draw their word colour from ${tok} (status never colour-alone)`);
  }
  assert.ok(base.includes('12.5px'), 'the 12.5px type floor must be enforced in the primitives');
});

test('main.tsx loads the token layer before the app renders', () => {
  const main = readFileSync(join(src, 'main.tsx'), 'utf8');
  assert.ok(main.includes("import './tokens.css'"), 'tokens.css must be imported in main.tsx');
});

// ---------------------------------------------------------------------------
// row 310 T85 (amit-developer): WCAG 2.x contrast of every side-nav text pair,
// computed from tokens.css values (rgba fills alpha-composited over the surface
// they sit on). Which token each nav rule uses is read from base.css, so a later
// CSS change cannot silently swap a pair without this test re-measuring it.

function parseColor(value) {
  const v = value.trim().toLowerCase();
  let m = v.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/);
  if (m) {
    const h = m[1].length === 3 ? [...m[1]].map((c) => c + c).join('') : m[1];
    return { r: parseInt(h.slice(0, 2), 16), g: parseInt(h.slice(2, 4), 16), b: parseInt(h.slice(4, 6), 16), a: 1 };
  }
  m = v.match(/^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/);
  if (m) return { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] };
  throw new Error(`unparsable colour: ${value}`);
}

/** Source-over: `top` (maybe translucent) painted on an opaque `under`. */
function composite(top, under) {
  assert.equal(under.a, 1, 'the backdrop of a composite must be opaque');
  const mix = (t, u) => t * top.a + u * (1 - top.a);
  return { r: mix(top.r, under.r), g: mix(top.g, under.g), b: mix(top.b, under.b), a: 1 };
}

function luminance({ r, g, b }) {
  const lin = (c) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

function contrast(fg, bg) {
  const [hi, lo] = [luminance(fg), luminance(bg)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/** `--color-x: <value>;` declarations of one theme block, comments stripped. */
function tokenValues(block) {
  const map = new Map();
  const clean = block.replace(/\/\*[\s\S]*?\*\//g, '');
  for (const m of clean.matchAll(/(--color-[a-z-]+)\s*:\s*([^;]+);/g)) map.set(m[1], m[2].trim());
  return map;
}

/** A selector list split on its top-level commas (commas inside :where(...)/:not(...) stay). */
function splitTopLevel(list) {
  const parts = [];
  let depth = 0;
  let cur = '';
  for (const ch of list) {
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if (ch === ',' && depth === 0) {
      parts.push(cur);
      cur = '';
    } else cur += ch;
  }
  return [...parts, cur];
}

/** Declarations of the base.css rule whose selector list contains exactly `selector`. */
function ruleDecls(css, selector) {
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, '');
  for (const m of clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const selectors = splitTopLevel(m[1]).map((s) => s.trim().replace(/\s+/g, ' '));
    if (!selectors.includes(selector)) continue;
    const decls = new Map();
    for (const d of m[2].matchAll(/([a-z-]+)\s*:\s*([^;]+);/g)) decls.set(d[1], d[2].trim());
    return decls;
  }
  throw new Error(`base.css has no rule for ${selector}`);
}

/** The --color-* token a declaration draws from (`var(--color-x)`), or null if absent. */
function tokenOf(decls, prop) {
  const v = decls.get(prop) ?? (prop === 'background' ? decls.get('background-color') : undefined);
  if (v === undefined) return null;
  const m = v.match(/^var\((--color-[a-z-]+)\)$/);
  assert.ok(m, `nav colours must come from a --color-* token, got "${prop}: ${v}"`);
  return m[1];
}

function navPairs() {
  const base = readFileSync(join(src, 'ui', 'base.css'), 'utf8');
  const sidenav = ruleDecls(base, '.sidenav');
  const item = ruleDecls(base, '.nav-item');
  const hover = ruleDecls(base, '.nav-item:hover');
  const active = ruleDecls(base, '.nav-item-active');
  const title = ruleDecls(base, '.nav-title');
  const initial = ruleDecls(base, '.shell-collapsed .nav-item::before');
  const surface = tokenOf(sidenav, 'background');
  assert.ok(surface, '.sidenav must paint a token background (the backdrop of every nav pair)');
  assert.equal(tokenOf(item, 'background'), null, 'an inactive item is transparent over the side nav');
  assert.equal(tokenOf(title, 'background'), null, 'group titles are transparent over the side nav');
  // [name, foreground token, fill layers bottom-up painted over the side-nav surface]
  return [
    ['inactive label', tokenOf(item, 'color'), []],
    ['hover label', tokenOf(hover, 'color'), [tokenOf(hover, 'background')]],
    ['active label', tokenOf(active, 'color'), [tokenOf(active, 'background')]],
    ['group title', tokenOf(title, 'color'), []],
    ['collapsed-rail initial', tokenOf(initial, 'color'), [tokenOf(initial, 'background')]],
    // the rail mark of the active (or hovered) entry sits on that entry's own fill
    ['collapsed-rail initial on active', tokenOf(initial, 'color'),
      [tokenOf(active, 'background'), tokenOf(initial, 'background')]],
  ].map(([name, fg, fills]) => ({ name, fg, fills, surface }));
}

function navRatios(theme) {
  const { light, dark } = readTokens();
  const lightMap = tokenValues(light);
  const map = theme === 'dark' ? new Map([...lightMap, ...tokenValues(dark)]) : lightMap;
  const color = (tok) => {
    assert.ok(map.has(tok), `${tok} must be declared for the ${theme} theme`);
    return parseColor(map.get(tok));
  };
  return navPairs().map(({ name, fg, fills, surface }) => {
    let bg = color(surface);
    for (const fill of fills) bg = composite(color(fill), bg);
    const under = [...fills].reverse().concat(surface).join(' over ');
    return { name, pair: `${fg} on ${under}`, ratio: contrast(color(fg), bg) };
  });
}

test('contrast helper matches the 01-§1 measured ratios (sanity)', () => {
  const r = (a, b) => Math.round(contrast(parseColor(a), parseColor(b)) * 100) / 100;
  assert.equal(r('#0f172a', '#ffffff'), 17.85);
  assert.equal(r('#ffffff', '#2563eb'), 5.17);
  assert.equal(r('#94a3b8', '#1e293b'), 5.71);
  const half = composite(parseColor('rgba(0, 0, 0, 0.5)'), parseColor('#ffffff'));
  assert.deepEqual([half.r, half.g, half.b], [127.5, 127.5, 127.5]);
});

for (const theme of ['light', 'dark']) {
  test(`side-nav text meets WCAG AA 4.5:1 in the ${theme} theme (every state)`, () => {
    const ratios = navRatios(theme);
    assert.equal(ratios.length, 6);
    for (const p of ratios) console.log(`# ${theme} ${p.name}: ${p.pair} = ${p.ratio.toFixed(2)}:1`);
    const failing = ratios.filter((p) => p.ratio < 4.5);
    assert.deepEqual(
      failing.map((p) => `${p.name}: ${p.pair} = ${p.ratio.toFixed(2)}:1`),
      [],
      `${theme} nav pairs below 4.5:1`,
    );
  });
}

test('theme toggle never paints a mid-transition frame (transitions off while data-theme flips)', () => {
  const base = readFileSync(join(src, 'ui', 'base.css'), 'utf8');
  for (const sel of ['.theme-switching *', '.theme-switching *::before', '.theme-switching *::after']) {
    assert.equal(ruleDecls(base, sel).get('transition'), 'none !important', `${sel} must kill every transition`);
  }

  const app = readFileSync(join(src, 'App.tsx'), 'utf8');
  const start = app.indexOf('function toggleTheme()');
  assert.ok(start > 0, 'App.tsx must keep the toggleTheme handler');
  const body = app.slice(start, app.indexOf('\n  }\n', start));
  const add = body.indexOf("classList.add('theme-switching')");
  const flip = body.indexOf('dataset.theme = next');
  const raf = body.indexOf('requestAnimationFrame');
  const remove = body.indexOf("classList.remove('theme-switching')");
  assert.ok(add >= 0 && flip > add, 'the switching class goes on before data-theme flips');
  assert.ok(raf > flip && remove > raf, 'the class comes off only in a later animation frame');
});

// ---------------------------------------------------------------------------
// row 310 T89 (amit-developer): element defaults for pages not yet redesigned
// (bare label/input/select/textarea/button/table/h1/h2 inside the shell's
// main.content). Zero specificity (:where) so a wave 2-4 page class always wins;
// third-party subtrees (jdm decision table, xyflow canvas, CodeMirror) excluded.

const SCOPE = ':where(main.content)';
const DARK_SCOPE = ':where([data-theme="dark"] main.content)';
const NOT_3P = ':not(:where([data-decision-table], [data-canvas], .raw-expression) *)';
const FIELDS = ':where(input:not([type="checkbox"], [type="radio"], [type="hidden"]), select, textarea)';
const sel = {
  heading: `${SCOPE} :where(h1, h2)`,
  label: `${SCOPE} :where(label)${NOT_3P}`,
  inlineLabel: `${SCOPE} :where(label:has(> input[type="checkbox"], > input[type="radio"]))${NOT_3P}`,
  field: `${SCOPE} ${FIELDS}${NOT_3P}`,
  placeholder: `${SCOPE} :where(input, textarea)${NOT_3P}::placeholder`,
  button: `${SCOPE} :where(button)${NOT_3P}`,
  submit: `${SCOPE} :where(button[type="submit"])${NOT_3P}`,
  darkSubmit: `${DARK_SCOPE} :where(button[type="submit"])${NOT_3P}`,
  table: `${SCOPE} :where(table)${NOT_3P}`,
  th: `${SCOPE} :where(th)${NOT_3P}`,
  td: `${SCOPE} :where(td)${NOT_3P}`,
  hidden: `${SCOPE} :where([hidden])`,
};

/** Selector with every :where(...) group removed - what is left is what carries specificity. */
function specificResidue(selector) {
  let out = '';
  for (let i = 0; i < selector.length; i++) {
    if (selector.startsWith(':where(', i)) {
      let depth = 0;
      for (i += 6; i < selector.length; i++) {
        if (selector[i] === '(') depth++;
        else if (selector[i] === ')' && --depth === 0) break;
      }
      continue;
    }
    out += selector[i];
  }
  return out.replace(/:not\(\s*\*\s*\)/g, '').trim();
}

/** A value that is var(--color-x) resolves through the theme map; a literal colour stays itself. */
function themeColour(theme) {
  const { light, dark } = readTokens();
  const lightMap = tokenValues(light);
  const map = theme === 'dark' ? new Map([...lightMap, ...tokenValues(dark)]) : lightMap;
  return (value) => {
    assert.ok(value, 'a colour declaration is missing');
    const m = value.match(/^var\((--color-[a-z-]+)\)$/);
    if (!m) return parseColor(value);
    assert.ok(map.has(m[1]), `${m[1]} must be declared for the ${theme} theme`);
    return parseColor(map.get(m[1]));
  };
}

test('element defaults: scoped to main.content, zero specificity, third-party subtrees excluded', () => {
  const base = readFileSync(join(src, 'ui', 'base.css'), 'utf8');
  for (const s of Object.values(sel)) ruleDecls(base, s); // every expected rule exists (throws if not)
  // ...and every main.content rule in base.css is specificity-free
  const clean = base.replace(/\/\*[\s\S]*?\*\//g, '');
  for (const m of clean.matchAll(/([^{}]+)\{[^{}]*\}/g)) {
    for (const s of splitTopLevel(m[1]).map((x) => x.trim().replace(/\s+/g, ' '))) {
      if (!s.includes('main.content')) continue;
      const residue = specificResidue(s);
      assert.ok(/^(::[a-z-]+)?$/.test(residue), `${s} must carry no specificity (left: "${residue}")`);
    }
  }
  for (const key of ['label', 'field', 'button', 'submit', 'table', 'th', 'td']) {
    assert.ok(sel[key].includes(NOT_3P), `${key} defaults must skip the third-party subtrees`);
  }
});

test('element defaults: forms stack (label above a full-width field, capped), checkboxes inline', () => {
  const base = readFileSync(join(src, 'ui', 'base.css'), 'utf8');
  const label = ruleDecls(base, sel.label);
  assert.equal(label.get('display'), 'flex');
  assert.equal(label.get('flex-direction'), 'column', 'the label text sits above its field');
  const max = Number((label.get('max-width') ?? '').replace('px', ''));
  assert.ok(max >= 480 && max <= 560, `fields are capped at 480-560px (got ${label.get('max-width')})`);
  assert.equal(ruleDecls(base, sel.inlineLabel).get('flex-direction'), 'row', 'checkbox/radio stay inline');
  const field = ruleDecls(base, sel.field);
  assert.equal(field.get('border'), '1px solid var(--color-border)');
  assert.equal(field.get('border-radius'), 'var(--r-1)');
  assert.equal(field.get('background'), 'var(--color-surface)');
  assert.equal(field.get('color'), 'var(--color-ink)');
  assert.equal(ruleDecls(base, sel.placeholder).get('opacity'), '1', 'placeholder colour is not faded further');
  assert.equal(ruleDecls(base, sel.hidden).get('display'), 'none !important', '[hidden] must still hide (Queue Reload)');
});

test('element defaults: secondary button, submit as primary, bare tables, h1/h2 on the type scale', () => {
  const base = readFileSync(join(src, 'ui', 'base.css'), 'utf8');
  const button = ruleDecls(base, sel.button);
  assert.equal(button.get('border-radius'), 'var(--r-2)');
  assert.equal(button.get('background'), 'var(--color-surface)');
  const submit = ruleDecls(base, sel.submit);
  assert.equal(submit.get('background'), 'var(--color-accent)', 'type=submit is the primary action');
  assert.equal(submit.get('color'), '#ffffff');
  const th = ruleDecls(base, sel.th);
  assert.equal(th.get('color'), 'var(--color-ink-muted)');
  assert.equal(th.get('font-size'), '12.5px', 'table headers sit on the 12.5px floor');
  assert.equal(th.get('border-bottom'), '1px solid var(--color-border-strong)');
  assert.equal(ruleDecls(base, sel.td).get('border-bottom'), '1px solid var(--color-border)');
  const heading = ruleDecls(base, sel.heading);
  assert.equal(heading.get('font-size'), '20px', 'page titles are 20px (01-§2.1)');
  assert.equal(heading.get('font-weight'), '600');
});

for (const theme of ['light', 'dark']) {
  test(`element defaults meet WCAG AA 4.5:1 in the ${theme} theme`, () => {
    const base = readFileSync(join(src, 'ui', 'base.css'), 'utf8');
    const c = themeColour(theme);
    const canvas = c(ruleDecls(base, 'body').get('background'));
    const ink = ruleDecls(base, 'body').get('color');
    const field = ruleDecls(base, sel.field);
    const button = ruleDecls(base, sel.button);
    const submit = ruleDecls(base, sel.submit);
    const darkSubmit = ruleDecls(base, sel.darkSubmit);
    const submitFill = theme === 'dark' ? darkSubmit.get('background') ?? submit.get('background') : submit.get('background');
    const tableBg = c(ruleDecls(base, sel.table).get('background'));
    const fieldBg = composite(c(field.get('background')), canvas);
    const pairs = [
      ['label text on canvas', c(ruleDecls(base, sel.label).get('color') ?? ink), canvas],
      ['input text on input', c(field.get('color')), fieldBg],
      ['placeholder on input', c(ruleDecls(base, sel.placeholder).get('color')), fieldBg],
      ['secondary button text', c(button.get('color')), composite(c(button.get('background')), canvas)],
      ['primary (submit) text on fill', c(submit.get('color')), composite(c(submitFill), canvas)],
      ['th text on table', c(ruleDecls(base, sel.th).get('color')), tableBg],
      ['td text on table', c(ruleDecls(base, sel.td).get('color') ?? ink), tableBg],
    ].map(([name, fg, bg]) => ({ name, ratio: contrast(fg, bg) }));
    for (const p of pairs) console.log(`# ${theme} ${p.name} = ${p.ratio.toFixed(2)}:1`);
    assert.deepEqual(pairs.filter((p) => p.ratio < 4.5).map((p) => `${p.name} = ${p.ratio.toFixed(2)}:1`), []);
  });
}