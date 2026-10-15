// Row 310 lane D (amit-developer, T102): Rules editor - home header + table with kind badges, editor in
// two columns with the sticky test-panel card, and the jdm-editor --grl-* override block scoped to
// .rules-editor (10-screens s7, 20-build-plan s3 row D + s5, 01-tokens s4.1). The pure badge helper is
// exercised directly; the stylesheet and page contracts the screens specs key off are read from source.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/rules-editor.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const rules = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'rules');
const read = (name) => readFileSync(join(rules, name), 'utf8');
const cssPath = join(rules, 'rules-editor.css');
const css = () => readFileSync(cssPath, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/** Every `selector { body }` of a flat stylesheet (no nested at-rules in this file). */
function cssRules(text) {
  return [...text.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((m) => ({ selector: m[1].trim(), body: m[2] }));
}

// 01-tokens s4.1: the jdm-editor theme vars and the app token each maps to.
const GRL = {
  '--grl-color-primary': 'var(--color-accent)',
  '--grl-color-primary-hover': 'var(--color-accent-hover)',
  '--grl-color-bg-container': 'var(--color-surface)',
  '--grl-color-bg-layout': 'var(--color-canvas)',
  '--grl-color-bg-elevated': 'var(--color-surface-raised)',
  '--grl-color-text': 'var(--color-ink)',
  '--grl-color-text-secondary': 'var(--color-ink-muted)',
  '--grl-color-text-placeholder': 'var(--color-ink-faint)',
  '--grl-color-border': 'var(--color-border)',
  '--grl-color-border-hover': 'var(--color-border-strong)',
  '--grl-color-error': 'var(--color-danger)',
  '--grl-color-success': 'var(--color-success)',
  '--grl-color-warning': 'var(--color-warn-text)',
  '--grl-font-family': 'var(--font-body)',
  '--grl-border-radius': '4px'
};

test('s4.1 rules-editor.css exists and the rules editor imports it', () => {
  assert.ok(existsSync(cssPath), 'src/rules/rules-editor.css');
  assert.match(read('RulesEditor.tsx'), /import '\.\/rules-editor\.css';/);
});

test('s4.1 every --grl-* override maps to its app token', () => {
  const text = css();
  for (const [name, value] of Object.entries(GRL)) {
    const escaped = name.replace(/[-]/g, '\\-');
    assert.match(text, new RegExp(`${escaped}\\s*:\\s*${value.replace(/[()]/g, '\\$&')}\\s*;`), name);
  }
});

test('s4.1 the --grl-* overrides are scoped under .rules-editor only (never global)', () => {
  const grlRules = cssRules(css()).filter((r) => /--grl-/.test(r.body));
  assert.ok(grlRules.length > 0, 'an override block');
  for (const r of grlRules) {
    for (const part of r.selector.split(',')) assert.match(part, /\.rules-editor\b/, `selector "${part.trim()}" is not scoped`);
  }
});

test('kale-design floor: every rule in rules-editor.css is scoped to a rules page and every colour is a token', () => {
  const text = css();
  assert.doesNotMatch(text, /#[0-9a-f]{3,8}\b/i, 'no hex colour');
  assert.doesNotMatch(text, /\b(rgb|rgba|hsl|hsla)\(/i, 'no literal colour function');
  for (const r of cssRules(text)) {
    for (const part of r.selector.split(',')) {
      assert.match(part, /\.rules-editor|\.rules-home|\[data-page="rules-(editor|home)"\]/, `unscoped selector "${part.trim()}"`);
    }
  }
});

test('s7 the decision tables render inside the .rules-editor wrapper', () => {
  const src = read('RulesEditor.tsx');
  const wrapper = src.indexOf('className="rules-editor');
  assert.ok(wrapper > -1, 'a .rules-editor wrapper');
  assert.ok(src.indexOf('<JdmDecisionTable', wrapper) > wrapper, 'tables inside the wrapper');
});

test('s7 sticky test panel: the card is sticky from the stylesheet, not inline, and stays always visible', () => {
  const panel = read('RuleTestPanel.tsx');
  assert.doesNotMatch(panel, /style=\{\{/, 'no inline styles left in the panel');
  assert.doesNotMatch(panel, /hidden|aria-expanded|<details/, 'never behind a toggle');
  assert.match(panel, /className="rules-card rules-test-panel"/);
  const sticky = cssRules(css()).find((r) => /\.rules-test-panel\b/.test(r.selector) && /position\s*:\s*sticky/.test(r.body));
  assert.ok(sticky, 'a sticky rule for the test panel');
  assert.match(sticky.body, /top\s*:/);
  assert.match(sticky.body, /align-self\s*:\s*start/);
});

test('s7 test panel card: labelled "Test this rule set", explanation kept, mono input and verdict, min-height kept', () => {
  const panel = read('RuleTestPanel.tsx');
  assert.match(panel, />Test this rule set</);
  assert.match(panel, /runs the saved rule set on the same evaluation port the runtime uses/i);
  const rules = cssRules(css());
  const mono = rules.filter((r) => /font-family\s*:\s*var\(--font-mono\)/.test(r.body)).map((r) => r.selector).join(' ');
  assert.match(mono, /\[data-test-panel-input\]/);
  assert.match(mono, /\[data-test-panel-verdict\]/);
  const verdict = rules.find((r) => /\[data-test-panel-verdict\]/.test(r.selector) && /min-height/.test(r.body));
  assert.ok(verdict, 'the verdict keeps a min-height so the panel never jumps');
  for (const state of ['done', 'error']) {
    assert.ok(rules.some((r) => r.selector.includes(`[data-state="${state}"]`)), `verdict tint for ${state}`);
  }
});

test('s7 kind badge carries its word: known kinds tint info, unknown kinds neutral, word never empty', async () => {
  const { kindBadge, KIND_LABELS } = await import('../../src/rules/templates.ts');
  assert.deepEqual(kindBadge('VALIDATION'), { word: KIND_LABELS.VALIDATION, tone: 'info' });
  assert.deepEqual(kindBadge('ROUTING'), { word: KIND_LABELS.ROUTING, tone: 'info' });
  assert.deepEqual(kindBadge('LOOKUP'), { word: KIND_LABELS.LOOKUP, tone: 'info' });
  assert.deepEqual(kindBadge('DECISION'), { word: 'DECISION', tone: 'neutral' });
  for (const kind of ['VALIDATION', 'ROUTING', 'LOOKUP', 'DECISION']) assert.ok(kindBadge(kind).word.length > 0, kind);
  const src = read('RulesEditor.tsx');
  assert.match(src, /className=\{`badge badge-\$\{badge\.tone\}`\}/);
  assert.match(src, /\{badge\.word\}/);
});

test('s7 rules home: header with flow select + kind filter, "New rule set" as the primary, rule sets in a table', () => {
  const src = read('RulesEditor.tsx');
  assert.match(src, /data-field="rule-set-kind-filter"/);
  assert.match(src, /data-action="new-rule-set"[\s\S]*?className="btn btn-primary"|className="btn btn-primary"[\s\S]*?data-action="new-rule-set"/);
  assert.match(src, /<table className="table"/);
  for (const head of ['Name', 'Kind', 'Open']) assert.match(src, new RegExp(`<th[^>]*>${head}</th>`), head);
  assert.doesNotMatch(src, /<ul>/, 'the link list is replaced by the table');
});

test('s7 malformed-cell refusal sits above its table with a jump link from the save row', () => {
  const src = read('RulesEditor.tsx');
  const loop = src.indexOf('tables.map(');
  assert.ok(loop > -1);
  const refusalInLoop = src.indexOf('<SaveRefusal', loop);
  const table = src.indexOf('<JdmDecisionTable', loop);
  assert.ok(refusalInLoop > loop && refusalInLoop < table, 'the refusal renders before (above) the offending table');
  assert.match(src, /data-action="jump-to-refusal"/);
  assert.match(src, /role="alert"/);
  assert.match(src, /scrollIntoView/);
});

test('selector contract kept exactly (screens specs key off these)', () => {
  const src = read('RulesEditor.tsx');
  for (const hook of [
    'data-page="rules-home"',
    'data-page="rules-editor"',
    'data-rule-set-id={ruleSetId}',
    'data-action="save-rule-set"',
    'data-action="create-rule-set"',
    'data-field="rule-set-flow"',
    'data-field="rule-set-kind"',
    'data-field="rule-set-name"',
    'data-rule-set-row={ruleSet.id}',
    'data-refusal="rule-set-create"',
    'data-refusal="rule-set-not-found"',
    'data-refusal="rule-set-unreadable"',
    'data-refusal="rule-table-invalid"',
    'data-refusal="rule-set-save"',
    'data-refusal-reason',
    'data-cell-node=',
    'data-cell-row=',
    'data-cell-column=',
    'data-saved="true"'
  ]) {
    assert.ok(src.includes(hook), hook);
  }
  const panel = read('RuleTestPanel.tsx');
  for (const hook of ['data-test-panel', 'data-test-panel-input', 'data-test-panel-run', 'data-test-panel-verdict', "data-state={result?.state ?? 'idle'}"]) {
    assert.ok(panel.includes(hook), hook);
  }
});

test('s7 a11y: h2 screen heading, h3 per table (JdmDecisionTable), kept load states verbatim', () => {
  const src = read('RulesEditor.tsx');
  assert.match(src, /Loading the rule set\.\.\./);
  assert.match(src, /Rule set not found\./);
  assert.match(src, /<h2[^>]*>Rules<\/h2>/);
  assert.match(src, /<label[\s\S]{0,80}Kind filter|aria-label="Filter by kind"/);
});
