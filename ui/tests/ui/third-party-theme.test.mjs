// B29 lane C (audit C4, C5, C7): third-party theming through tokens, and the 2px header rule.
// Source-text assertions like shell-tokens.test.mjs; run standalone:
//   node --import ./tests/rules/ts-resolve.mjs --test tests/ui/third-party-theme.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const read = (p) => readFileSync(join(src, p), 'utf8');

test('C4: the raw-expression editor carries no hard-coded border or radius', () => {
  const tsx = read('mapping/expression/RawExpressionEditor.tsx');
  assert.ok(!/#8a939e/i.test(tsx), 'hard-coded border colour is gone');
  assert.ok(!/borderRadius/.test(tsx), 'hard-coded radius is gone');
  assert.match(tsx, /import '\.\/cm-theme\.css'/);
  assert.match(tsx, /data-field="raw-expression"/, 'screens specs key on this hook');
  assert.match(tsx, /aria-label': 'Raw expression'/);
});

test('C4: cm-theme.css themes .cm-editor through tokens only, with a visible focus ring', () => {
  assert.ok(existsSync(join(src, 'mapping/expression/cm-theme.css')), 'cm-theme.css exists');
  const css = read('mapping/expression/cm-theme.css');
  assert.ok(!/#[0-9a-f]{3,8}\b/i.test(css), 'no hex colour: every colour is a token');
  assert.match(css, /\.cm-editor\b/);
  assert.match(css, /border:\s*1px solid var\(--color-border-control\)/);
  assert.match(css, /border-radius:\s*var\(--r-1\)/);
  assert.match(css, /background:\s*var\(--color-surface\)/);
  assert.match(css, /caret-color:\s*var\(--color-ink\)/);
  assert.match(css, /\.cm-selectionBackground/);
  assert.match(css, /\.cm-gutters/);
  assert.match(css, /(\.cm-focused|:focus-within)[^{]*\{[^}]*outline:\s*2px solid var\(--color-focus\)/s, '2px focus ring (WCAG 2.4.7)');
});

test('C5: the jdm decision table follows the app theme and dialogs share the --grl scope', async () => {
  const { jdmThemeMode } = await import('../../src/rules/jdm/theme-mode.ts');
  assert.equal(jdmThemeMode('dark'), 'dark');
  assert.equal(jdmThemeMode('light'), 'light');
  assert.equal(jdmThemeMode(undefined), 'light');
  assert.equal(jdmThemeMode('sepia'), 'light');
  const tsx = read('rules/jdm/JdmDecisionTable.tsx');
  assert.match(tsx, /<JdmConfigProvider theme=\{\{ mode \}\}/);
  assert.match(tsx, /MutationObserver/);
  assert.match(tsx, /mountDialogsOnBody/);
  const css = read('rules/rules-editor.css');
  assert.match(css, /:root\[data-theme\][^{]*\{[^}]*--grl-color-primary:/s, '--grl overrides also reach body-mounted dialogs');
});

test('C7/X1: the table header rule is 2px (both the .table and the bare-th rule)', () => {
  const css = read('ui/base.css');
  const rules = [...css.matchAll(/th\s*(?:\)[^{]*)?\{[^}]*border-bottom:\s*([^;]+);/g)].map((m) => m[1]);
  assert.ok(rules.length >= 2, 'both th rules found');
  for (const r of rules) assert.match(r, /^2px solid var\(--color-border-strong\)/);
});
