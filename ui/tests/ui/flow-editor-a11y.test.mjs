// Lane A round 2 (B27, audit A8): the flow editor moves focus to the first invalid field, marks the
// required fields, and traps Tab inside the leave dialog.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/flow-editor-a11y.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const { focusSelectorFor, trapTab } = await import('../../src/pages/flow-lifecycle.ts');
const page = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'pages', 'FlowDefinition.tsx'), 'utf8');

test('A8: every validation field maps to the control focus goes to; the request bucket has none', () => {
  assert.equal(focusSelectorFor('source-system'), '[data-field="source-system"]');
  assert.equal(focusSelectorFor('destinations'), '[data-field="destination-1"]');
  assert.equal(focusSelectorFor('business-key-rule'), '[data-field="business-key-rule"]');
  assert.equal(focusSelectorFor('source-schema'), '[data-field="source-schema"]');
  assert.equal(focusSelectorFor('request'), null);
  assert.equal(focusSelectorFor(null), null);
});

test('A8: Tab wraps inside the dialog in both directions', () => {
  assert.equal(trapTab(0, 2, false), 1);
  assert.equal(trapTab(1, 2, false), 0);
  assert.equal(trapTab(0, 2, true), 1);
  assert.equal(trapTab(1, 2, true), 0);
  assert.equal(trapTab(0, 0, false), -1);
});

test('A8 wiring: focus moves on a failing field, required fields are marked, the dialog traps Tab', () => {
  assert.match(page, /focusSelectorFor\(validationField\)/);
  assert.match(page, /aria-required="true"/);
  assert.match(page, /data-required-note/);
  assert.match(page, /trapTab\(/);
  assert.match(page, /onKeyDown=\{onDialogKey\}/);
});
