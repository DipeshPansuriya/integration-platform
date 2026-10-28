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
  const app = readFileSync(join(src, 'App.tsx'), 'utf8') + readFileSync(join(src, 'nav-model.ts'), 'utf8');
  // Row 575 S1: the menu table lives in nav-model.ts. Values may be declared in that table (`nav: 'flows'`) or inline as
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
  // combinators (+ > ~) carry no specificity (row 310 B9 sibling rule)
  return out.replace(/:not\(\s*\*\s*\)/g, '').replace(/[+>~]/g, '').trim();
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
  // row 310 T90: the field boundary is the >= 3:1 control token (WCAG 1.4.11), not the hairline
  assert.equal(field.get('border'), '1px solid var(--color-border-control)');
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
  assert.equal(th.get('border-bottom'), '2px solid var(--color-border-strong)');
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


// ---------------------------------------------------------------------------
// row 310 T90 (amit-developer): WCAG AA polish - primary button text (1.4.3),
// form-control boundaries (1.4.11, >= 3:1 against every backdrop a control sits
// on) and dark --color-ink-faint on every surface tier (menus/popups included).
// The realm login theme mirrors the console, so the same values are pinned there.

const REALM_CSS = join(root, '..', 'compose', 'keycloak', 'themes', 'platform', 'login', 'resources', 'css');
const stripComments = (css) => css.replace(/\/\*[\s\S]*?\*\//g, '');

/** Body of the first block opened after `head` (an at-rule or a selector), braces matched. */
function blockBody(css, head) {
  const at = css.indexOf(head);
  assert.ok(at >= 0, `missing block ${head}`);
  const open = css.indexOf('{', at);
  let depth = 0;
  for (let i = open; i < css.length; i++) {
    if (css[i] === '{') depth++;
    else if (css[i] === '}' && --depth === 0) return css.slice(open + 1, i);
  }
  throw new Error(`unclosed block ${head}`);
}

function ruleOrNull(css, selector) {
  try {
    return ruleDecls(css, selector);
  } catch {
    return null;
  }
}

function appMap(theme) {
  const { light, dark } = readTokens();
  const lightMap = tokenValues(light);
  return theme === 'dark' ? new Map([...lightMap, ...tokenValues(dark)]) : lightMap;
}

function platformValues(block) {
  const map = new Map();
  for (const m of stripComments(block).matchAll(/(--platform-[a-z-]+)\s*:\s*([^;]+);/g)) map.set(m[1], m[2].trim());
  return map;
}

function realmMap(theme) {
  const css = stripComments(readFileSync(join(REALM_CSS, 'tokens.css'), 'utf8'));
  const light = platformValues(blockBody(css, ':root'));
  if (theme === 'light') return light;
  const dark = platformValues(blockBody(blockBody(css, '@media (prefers-color-scheme: dark)'), ':root'));
  return new Map([...light, ...dark]);
}

/** Resolve `var(--prefix-x)` (or a literal colour) through a theme map. */
function resolver(map, prefix, theme) {
  return (value) => {
    assert.ok(value, 'a colour declaration is missing');
    const m = value.match(new RegExp(`^var\\((${prefix}[a-z-]+)\\)$`));
    if (!m) return parseColor(value);
    assert.ok(map.has(m[1]), `${m[1]} must be declared for the ${theme} theme`);
    return parseColor(map.get(m[1]));
  };
}

const ratio = (fg, bg) => Math.round(contrast(fg, bg) * 100) / 100;

/** filter: brightness(x) scales label AND fill. Browsers differ on the colour space, so
    both readings are returned (sRGB channels, linear light) and each must pass. */
function brightnessPairs(filter, fg, bg) {
  const m = (filter ?? '').match(/^brightness\(([\d.]+)\)$/);
  assert.ok(m, `only a brightness() filter is modelled (got ${filter})`);
  const x = +m[1];
  const srgb = (c) => ({ r: c.r * x, g: c.g * x, b: c.b * x, a: 1 });
  const lin = (c) => {
    const f = (v) => {
      const s = v / 255;
      const l = (s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4) * x;
      return 255 * (l <= 0.0031308 ? l * 12.92 : 1.055 * l ** (1 / 2.4) - 0.055);
    };
    return { r: f(c.r), g: f(c.g), b: f(c.b), a: 1 };
  };
  return [['sRGB', ratio(srgb(fg), srgb(bg))], ['linear', ratio(lin(fg), lin(bg))]];
}
const DARK_BTN = '[data-theme="dark"] .btn-primary';

for (const theme of ['light', 'dark']) {
  test(`T90 .btn-primary white text >= 4.5:1 at rest and hover in the ${theme} theme`, () => {
    const base = readFileSync(join(src, 'ui', 'base.css'), 'utf8');
    const c = resolver(appMap(theme), '--color-', theme);
    const primary = ruleDecls(base, '.btn-primary');
    const text = c(primary.get('color'));
    // every fill that can paint under the label in this theme (rest and hover rules)
    const fills = [['.btn-primary:hover', ruleOrNull(base, '.btn-primary:hover')]];
    const dimmed = [];
    if (theme === 'dark') {
      const darkRest = ruleOrNull(base, DARK_BTN);
      assert.ok(darkRest?.get('background'), `${DARK_BTN} must override the fill (white on dark accent is 3.68:1)`);
      fills.unshift([DARK_BTN, darkRest]);
      const darkHover = ruleOrNull(base, `${DARK_BTN}:hover`);
      if (darkHover?.get('background')) fills.push([`${DARK_BTN}:hover`, darkHover]);
      const f = darkHover?.get('filter');
      if (f) {
        // the hovered label is dimmed together with the fill it sits on
        for (const [name, d] of fills) {
          for (const [space, r] of brightnessPairs(f, text, c(d.get('background')))) {
            dimmed.push({ name: `${name} under ${DARK_BTN}:hover ${f} (${space})`, r });
          }
        }
      }
      // consistent with the T89 submit default: same dark primary fill
      assert.equal(darkRest.get('background'), ruleDecls(base, sel.darkSubmit).get('background'));
    } else {
      fills.unshift(['.btn-primary', primary]);
    }
    const measured = fills.map(([name, d]) => ({ name, r: ratio(text, c(d.get('background'))) })).concat(dimmed);
    for (const p of measured) console.log(`# ${theme} white on ${p.name} fill = ${p.r}:1`);
    assert.deepEqual(measured.filter((p) => p.r < 4.5).map((p) => `${p.name} = ${p.r}:1`), []);
  });

  test(`T90 form-control boundary >= 3:1 on canvas, surface and raised (rest, hover, focus) in the ${theme} theme`, () => {
    const base = readFileSync(join(src, 'ui', 'base.css'), 'utf8');
    const map = appMap(theme);
    const c = resolver(map, '--color-', theme);
    const field = ruleDecls(base, sel.field);
    const m = (field.get('border') ?? '').match(/^1px solid (var\(--color-[a-z-]+\))$/);
    assert.ok(m, `field border must be one 1px token line (got ${field.get('border')})`);
    const states = [
      ['rest', m[1]],
      ['hover', ruleDecls(base, `${SCOPE} ${FIELDS}:where(:hover)${NOT_3P}`).get('border-color')],
      ['focus', ruleDecls(base, `${SCOPE} ${FIELDS}:where(:focus)${NOT_3P}`).get('border-color')],
    ];
    const measured = [];
    for (const [state, value] of states) {
      for (const bgTok of ['--color-canvas', '--color-surface', '--color-surface-raised']) {
        measured.push({ name: `${state} ${value} on ${bgTok}`, r: ratio(c(value), parseColor(map.get(bgTok))) });
      }
    }
    for (const p of measured) console.log(`# ${theme} control ${p.name} = ${p.r}:1`);
    assert.deepEqual(measured.filter((p) => p.r < 3).map((p) => `${p.name} = ${p.r}:1`), []);
    assert.notEqual(m[1], 'var(--color-border)', 'the hairline token is for dividers, not control boundaries');
    // hairlines stay hairlines
    assert.equal(map.get('--color-border'), theme === 'dark' ? '#334155' : '#e2e8f0');
  });

  test(`T90 --color-ink-faint >= 4.5:1 on canvas, surface and raised, still fainter than ink-muted (${theme})`, () => {
    const map = appMap(theme);
    const faint = parseColor(map.get('--color-ink-faint'));
    const muted = parseColor(map.get('--color-ink-muted'));
    const measured = ['--color-canvas', '--color-surface', '--color-surface-raised'].map((bgTok) => {
      const bg = parseColor(map.get(bgTok));
      return { name: `ink-faint on ${bgTok}`, r: ratio(faint, bg), muted: ratio(muted, bg) };
    });
    for (const p of measured) console.log(`# ${theme} ${p.name} = ${p.r}:1 (ink-muted ${p.muted}:1)`);
    assert.deepEqual(measured.filter((p) => p.r < 4.5).map((p) => `${p.name} = ${p.r}:1`), []);
    for (const p of measured) assert.ok(p.r < p.muted, `${p.name} must stay fainter than ink-muted`);
    if (theme === 'light') assert.equal(map.get('--color-ink-faint'), '#64748b', 'light ink-faint is unchanged');
  });

  test(`T90 realm login tokens carry the console values (${theme} parity)`, () => {
    const app = appMap(theme);
    const realm = realmMap(theme);
    for (const [a, r] of [
      ['--color-ink-faint', '--platform-ink-faint'],
      ['--color-border-control', '--platform-border-control'],
      ['--color-accent-hover', '--platform-accent-hover'],
      ['--color-accent', '--platform-accent'],
      ['--color-canvas', '--platform-canvas'],
      ['--color-surface', '--platform-surface'],
      ['--color-surface-raised', '--platform-raised'],
      ['--color-ink-muted', '--platform-ink-muted'],
      ['--color-border', '--platform-border'],
    ]) {
      assert.ok(app.has(a), `${a} must exist in the console (${theme})`);
      assert.equal(realm.get(r), app.get(a), `${r} must equal console ${a} in ${theme}`);
    }
  });

  test(`T90 realm login: primary text, control boundary and faint text pass in the ${theme} theme`, () => {
    const styles = stripComments(readFileSync(join(REALM_CSS, 'styles.css'), 'utf8'));
    const map = realmMap(theme);
    const c = resolver(map, '--platform-', theme);
    const darkBody = styles.includes('@media (prefers-color-scheme: dark)')
      ? blockBody(styles, '@media (prefers-color-scheme: dark)')
      : '';
    const pick = (selector, prop) =>
      (theme === 'dark' ? ruleOrNull(darkBody, selector)?.get(prop) : undefined) ?? ruleDecls(styles, selector).get(prop);
    const measured = [];
    const text = c(pick('#kc-login', 'color'));
    for (const s of ['#kc-login', '#kc-login:hover']) {
      measured.push({ name: `text-on-accent on ${s}`, r: ratio(text, c(pick(s, 'background'))), floor: 4.5 });
    }
    const hoverFilter = theme === 'dark' ? ruleOrNull(darkBody, '#kc-login:hover')?.get('filter') : undefined;
    if (hoverFilter) {
      for (const s of ['#kc-login', '#kc-login:hover']) {
        for (const [space, r] of brightnessPairs(hoverFilter, text, c(pick(s, 'background')))) {
          measured.push({ name: `text-on-accent on ${s} under hover ${hoverFilter} (${space})`, r, floor: 4.5 });
        }
      }
    }
    const border = ruleDecls(styles, 'input[type="text"]').get('border');
    const rest = (border ?? '').replace(/^1px solid /, '');
    for (const [state, value] of [['rest', rest], ['hover', pick('input:hover', 'border-color')]]) {
      for (const bgTok of ['--platform-canvas', '--platform-surface', '--platform-raised']) {
        measured.push({ name: `input ${state} ${value} on ${bgTok}`, r: ratio(c(value), parseColor(map.get(bgTok))), floor: 3 });
      }
    }
    for (const bgTok of ['--platform-canvas', '--platform-surface', '--platform-raised']) {
      measured.push({ name: `ink-faint on ${bgTok}`, r: ratio(c('var(--platform-ink-faint)'), parseColor(map.get(bgTok))), floor: 4.5 });
    }
    for (const p of measured) console.log(`# realm ${theme} ${p.name} = ${p.r}:1 (floor ${p.floor})`);
    assert.deepEqual(measured.filter((p) => p.r < p.floor).map((p) => `${p.name} = ${p.r}:1 < ${p.floor}`), []);
    assert.equal(border, '1px solid var(--platform-border-control)', 'realm inputs use the control token');
  });
}

test('T90 no token comment claims the false dark white-on-accent 5.17:1', () => {
  const { dark } = readTokens();
  const line = dark.split('\n').find((l) => l.includes('--color-accent:'));
  assert.ok(!/white text 5\.17/.test(line), `app dark accent comment is false: ${line.trim()}`);
  const realm = readFileSync(join(REALM_CSS, 'tokens.css'), 'utf8');
  assert.ok(!/dark accent also measures 5\.17/.test(realm), 'realm dark accent comment is false (3.68:1)');
  const styles = readFileSync(join(REALM_CSS, 'styles.css'), 'utf8');
  assert.ok(!/5\.17:1 \(dark\)/.test(styles), 'realm primary-button comment is false for dark');
  const base = readFileSync(join(src, 'ui', 'base.css'), 'utf8');
  assert.ok(!/dark accent same ratio/.test(base), 'base.css .btn-primary comment is false for dark');
});


// ---------------------------------------------------------------------------
// row 310 T90b (amit-developer): link text. Bare <a> in main.content painted the
// browser default blue; the realm card painted the dark accent #3b82f6 (3.98:1 on
// surface). --color-link / --platform-link carry >= 4.5:1 on every surface tier,
// at rest and on hover, in both themes.

const linkSel = `${SCOPE} :where(a)${NOT_3P}`;
const linkHoverSel = `${SCOPE} :where(a):where(:hover)${NOT_3P}`;

for (const theme of ['light', 'dark']) {
  test(`T90b main.content links >= 4.5:1 on canvas, surface and raised (rest + hover) in the ${theme} theme`, () => {
    const base = readFileSync(join(src, 'ui', 'base.css'), 'utf8');
    const map = appMap(theme);
    const c = resolver(map, '--color-', theme);
    const rest = ruleDecls(base, linkSel);
    assert.equal(rest.get('color'), 'var(--color-link)', 'bare links take the link token');
    const hover = ruleOrNull(base, linkHoverSel);
    const states = [['rest', rest.get('color')], ['hover', hover?.get('color') ?? rest.get('color')]];
    const measured = [];
    for (const [state, value] of states) {
      for (const bgTok of ['--color-canvas', '--color-surface', '--color-surface-raised']) {
        measured.push({ name: `link ${state} ${value} on ${bgTok}`, r: ratio(c(value), parseColor(map.get(bgTok))) });
      }
    }
    for (const p of measured) console.log(`# ${theme} ${p.name} = ${p.r}:1`);
    assert.deepEqual(measured.filter((p) => p.r < 4.5).map((p) => `${p.name} = ${p.r}:1`), []);
    assert.equal(map.get('--color-accent'), theme === 'dark' ? '#3b82f6' : '#2563eb', 'accent value unchanged');
  });

  test(`T90b realm login links carry --platform-link (= console --color-link) at >= 4.5:1 in the ${theme} theme`, () => {
    const app = appMap(theme);
    const realm = realmMap(theme);
    assert.ok(app.has('--color-link'), `--color-link must exist in the console (${theme})`);
    assert.equal(realm.get('--platform-link'), app.get('--color-link'), `--platform-link must equal console --color-link in ${theme}`);
    const styles = stripComments(readFileSync(join(REALM_CSS, 'styles.css'), 'utf8'));
    const c = resolver(realm, '--platform-', theme);
    assert.equal(ruleDecls(styles, 'a').get('color'), 'var(--platform-link)', 'realm links take the link token');
    const measured = [];
    for (const s of ['a', 'a:visited', 'a:hover']) {
      for (const bgTok of ['--platform-canvas', '--platform-surface', '--platform-raised']) {
        measured.push({ name: `${s} on ${bgTok}`, r: ratio(c(ruleDecls(styles, s).get('color')), parseColor(realm.get(bgTok))) });
      }
    }
    for (const p of measured) console.log(`# realm ${theme} link ${p.name} = ${p.r}:1`);
    assert.deepEqual(measured.filter((p) => p.r < 4.5).map((p) => `${p.name} = ${p.r}:1`), []);
  });
}

// row 310 B9/B15 + E7 T122: the Contracts bare-button sibling rule in base.css was DEAD and is
// removed - every Contracts control-adjacent button carries .btn (ContractList.tsx:76-107), and the
// one class-less submit (ImportContractForm.tsx:99) follows a <label>, not a control. The live 8px
// gaps are page-level: contracts.css (.btn + .btn, .btn + .contracts-note-group, the note-group's
// own inline-flex gap) and flows.css (.versions-actions flex gap), measured in the browser by
// tests/screens/layout-gaps.docker.spec.mjs.
test('T122/B9: base.css holds no Contracts sibling-gap rule, and the spacing lives in contracts.css', () => {
  const base = readFileSync(join(src, 'ui', 'base.css'), 'utf8');
  assert.ok(!base.includes(`:where(main.content [data-page="contracts"]) :where(button, input:not([type="hidden"]), select, textarea) + :where(button`),
    'the dead B9 bare-button sibling rule stays removed');
  assert.ok(!base.includes(`${SCOPE} :where(button, input:not([type="hidden"]), select, textarea) + :where(button`),
    'B15: no page-wide sibling gap in the content column either');
  const page = readFileSync(join(src, 'pages', 'contracts', 'contracts.css'), 'utf8');
  assert.ok(page.includes('.contracts-page .contracts-actions .btn + .btn'), 'Contracts .btn rows keep their page-level gap');
  assert.ok(page.includes('.contracts-page .contracts-actions .btn + .contracts-note-group'), 'change-note gap is page-level');
  assert.ok(page.includes('.contracts-page .contracts-note-group'), 'the note group carries its own inline-flex gap');
});
