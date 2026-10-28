// row310 T86 (lane K): the realm login theme `platform` (compose/keycloak/themes/platform).
// Keycloak 26.8's base login template renders the page title (h1#kc-page-title) in a <header>
// inside the element classed by `kcFormCardClass`, OUTSIDE #kc-content, and the password field's
// visibility toggle as a <button> whose only child is an <i> classed by
// kcFormPasswordVisibilityIconShow/Hide (base/login/login.ftl). parent=base leaves every one of
// those properties empty, so the title floated unstyled at the top-left and the toggle was an
// empty box. These checks read the theme files and the harness the way Keycloak and the screen
// tests consume them.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..', '..');
const LOGIN = path.join(REPO, 'compose', 'keycloak', 'themes', 'platform', 'login');

function properties() {
  const out = {};
  for (const line of fs.readFileSync(path.join(LOGIN, 'theme.properties'), 'utf8').split(/\r?\n/)) {
    const m = /^\s*([A-Za-z0-9_.]+)\s*=\s*(.*?)\s*$/.exec(line);
    if (m && !line.trimStart().startsWith('#')) out[m[1]] = m[2];
  }
  return out;
}

/** Declarations of every rule whose selector list names `selector` exactly, merged in order. */
function declarations(selector) {
  const css = fs.readFileSync(path.join(LOGIN, 'resources', 'css', 'styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const merged = {};
  for (const [, selectors, body] of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    if (!selectors.split(',').map((s) => s.trim()).includes(selector)) continue;
    for (const decl of body.split(';')) {
      const i = decl.indexOf(':');
      if (i > 0) merged[decl.slice(0, i).trim()] = decl.slice(i + 1).trim();
    }
  }
  return merged;
}

function themeClass(props, key) {
  const value = props[key];
  assert.ok(value && /^[a-z][a-z0-9-]*$/.test(value), `theme.properties must set ${key} to one class (got ${JSON.stringify(value)})`);
  return value;
}

test('the page title sits inside one styled card (kcFormCardClass), not floating above it', () => {
  const card = declarations(`.${themeClass(properties(), 'kcFormCardClass')}`);
  assert.equal(card.background, 'var(--platform-surface)', 'the card element carries the surface');
  assert.equal(card['border-radius'], 'var(--platform-r-2)', 'cards = 12px');
  assert.equal(card['max-width'], '420px', 'the 420px centred card (10-screens §2)');
  const title = declarations('#kc-page-title');
  assert.equal(title['font-size'], '20px', 'the one page h1 uses the page-title tier (01 §2.1)');
  assert.equal(title['font-weight'], '600');
  const inner = declarations('#kc-content-wrapper');
  assert.equal(inner.background, undefined, 'no card inside the card');
});

test('the password-visibility toggle is a styled control that shows a word, never an empty box', () => {
  const props = properties();
  const button = declarations(`.${themeClass(props, 'kcFormPasswordVisibilityButtonClass')}`);
  assert.ok(button.border && button.padding && button.cursor === 'pointer', 'the toggle is styled as a button');
  assert.equal(declarations(`.${themeClass(props, 'kcInputGroup')}`).display, 'flex', 'input and toggle share one row');
  assert.equal(declarations(`.${themeClass(props, 'kcFormPasswordVisibilityIconShow')}::before`).content, '"Show"');
  assert.equal(declarations(`.${themeClass(props, 'kcFormPasswordVisibilityIconHide')}::before`).content, '"Hide"');
});

test('the harness puts the platform theme where Keycloak loads themes, beside the realm import', async () => {
  const { containerCopies } = await import('../../scripts/keycloak-harness.mjs');
  assert.equal(typeof containerCopies, 'function', 'keycloak-harness.mjs exports containerCopies(repo)');
  const copies = containerCopies('/repo').map(([from, to]) => [from.replaceAll('\\', '/'), to]);
  assert.deepEqual(copies, [
    ['/repo/compose/keycloak', '/opt/keycloak/data/import'],
    ['/repo/compose/keycloak/themes/platform', '/opt/keycloak/themes/platform']
  ]);
});

// Row 571: the realm login keeps the Kale Deep Sea card signature and worded (never glyph-only) status.
test('the card carries the 4px Deep Sea / Noon rules, and the dark theme never puts Noon on its ground', () => {
  const card = declarations(`.${themeClass(properties(), 'kcFormCardClass')}`);
  assert.equal(card['border-top'], '4px solid var(--platform-rule-top)');
  assert.equal(card['border-bottom'], '4px solid var(--platform-rule-bottom)');
  const tokens = fs.readFileSync(path.join(LOGIN, 'resources', 'css', 'tokens.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const [light, dark] = tokens.split('@media (prefers-color-scheme: dark)');
  assert.match(light, /--platform-rule-top:\s*#081f4b/i, 'Deep Sea');
  assert.match(light, /--platform-rule-bottom:\s*#eb4a26/i, 'Noon, light ground only');
  assert.doesNotMatch(dark, /#eb4a26/i, 'no Noon on the dark ground');
});

test('alerts name their status in words, not a glyph', () => {
  for (const [selector, word] of [['.alert-error::before', '"Error"'], ['.alert-warning::before', '"Warning"'], ['.alert-success::before', '"Done"'], ['.alert-info::before', '"Note"']]) {
    assert.equal(declarations(selector).content, word, selector);
  }
});

test('the password field and its Show/Hide toggle are one control with one focus ring', () => {
  const group = declarations(`.${themeClass(properties(), 'kcInputGroup')}`);
  assert.equal(group.border, '1px solid var(--platform-border-control)', 'the group carries the >= 3:1 boundary');
  const css = fs.readFileSync(path.join(LOGIN, 'resources', 'css', 'styles.css'), 'utf8');
  assert.match(css, /\.platform-input-group:has\(> input:focus-visible\)\s*\{[^}]*outline:\s*2px solid var\(--platform-focus\)/, 'the ring moves to the group');
  assert.match(css, /@supports selector\(:has\(\*\)\)/, 'the field keeps its own ring where :has is unsupported');
});
