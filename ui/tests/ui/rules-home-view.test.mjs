// Lane A (B27, audit A9): the Rules home never turns a failed fetch into "No rule sets on this flow yet."
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/rules-home-view.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const { homeListState, homeLoadError } = await import('../../src/rules/rules-home-view.ts');
const page = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'rules', 'RulesEditor.tsx'), 'utf8');

test('A9: a load error wins over the empty copy; empty, empty-for-kind and rows are told apart', () => {
  assert.equal(homeListState({ error: 'x', total: 0, shown: 0 }), 'error');
  assert.equal(homeListState({ error: 'x', total: 3, shown: 3 }), 'error');
  assert.equal(homeListState({ error: null, total: 0, shown: 0 }), 'empty');
  assert.equal(homeListState({ error: null, total: 2, shown: 0 }), 'empty-kind');
  assert.equal(homeListState({ error: null, total: 2, shown: 2 }), 'rows');
});

test('A9: a failed answer is a worded sentence naming what could not load; an ok answer is no error', () => {
  assert.equal(homeLoadError('flows', { ok: true }), null);
  assert.match(homeLoadError('flows', { ok: false, status: 500 }), /Could not load the flows/);
  assert.match(homeLoadError('rule sets', { ok: false, status: 403 }), /Could not load the rule sets.*permission/);
});

test('wiring: the home page shows the error with Retry and no longer swallows the failed fetches', () => {
  assert.match(page, /data-load-error="rules-home"/);
  assert.match(page, /data-action="retry-load"/);
  assert.match(page, /homeListState\(/);
  assert.doesNotMatch(page, /setRuleSets\(result\.ok \? result\.data : \[\]\)/);
  assert.doesNotMatch(page, /if \(!result\.ok\) return;\s*\n\s*setFlows/);
});
