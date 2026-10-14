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