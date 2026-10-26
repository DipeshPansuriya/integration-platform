// Row 372 / B28 (UI audit X3 + B5/B8): one <h1> page title per routed page, and worded status badges on
// Activity and the Delivery report. Source-contract + pure-helper style (no DOM, no docker).
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/lane-b-badges-headings.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { findAll, mount } from './hook-harness.mjs';

const read = (p) => readFileSync(new URL(`../../src/${p}`, import.meta.url), 'utf8');
const count = (src, re) => (src.match(re) ?? []).length;

// X3 - the page title of every routed page is the one h1 (no h2 page titles).
const PAGES = {
  'pages/FlowsList.tsx': 1,
  'pages/FlowDefinition.tsx': 1,
  'pages/Versions.tsx': 1,
  'pages/Systems.tsx': 1,
  'pages/ContractVersions.tsx': 1,
  'pages/MappingDesigner.tsx': 1,
  'rules/RulesEditor.tsx': 2, // rules-home and rules-editor are two routes of one file: one h1 each
  'pages/Dashboard.tsx': 1,
  'pages/QueueDashboard.tsx': 1,
  'pages/Failures.tsx': 1,
  'pages/DeliveryReport.tsx': 1,
  'pages/MessageDetail.tsx': 1
};
for (const [file, h1s] of Object.entries(PAGES)) {
  test(`X3 ${file} has ${h1s} page-title h1 and no page-title h2 in a head block`, () => {
    const src = read(file);
    assert.equal(count(src, /<h1[\s>]/g), h1s, 'h1 count');
    assert.doesNotMatch(src, /-head">\s*<h2/, 'a head block must not title the page with h2');
  });
}
test('X3 Versions has its h1 as the page title and the selected flow stays h3 below it', () => {
  const src = read('pages/Versions.tsx');
  assert.match(src, /<h1>Versions and approval<\/h1>/);
  assert.match(src, /<h3>\{selected\.name/);
});
test('X3 Activity: the list and the event detail each render exactly one h1 (never both at once)', () => {
  const src = read('pages/Activity.tsx');
  const fn = src.slice(src.indexOf('export function Activity'));
  const detail = fn.slice(fn.indexOf('if (eventId !== null) {'), fn.indexOf('const rows ='));
  const list = fn.slice(fn.indexOf('const rows ='));
  assert.equal(count(detail, /<h1[\s>]/g), 1, 'detail branch');
  assert.equal(count(list, /<h1[\s>]/g), 1, 'list branch');
  assert.match(detail, /return\s*\(/, 'the detail branch returns before the list renders');
});
test('X3 head-title css follows the tag (h1) so the title keeps margin 0', () => {
  for (const f of ['pages/flows.css', 'pages/systems/systems.css', 'pages/contracts/contracts.css', 'mapping/mapping.css', 'rules/rules-editor.css']) {
    const css = read(f);
    assert.match(css, /-head h1\s*\{/, `${f} targets the head h1`);
    assert.doesNotMatch(css, /-head h2\s*\{/, `${f} no longer targets a head h2`);
  }
});

// B5 - Activity: the action is a worded badge, tone from the action name; unknown stays neutral.
const { activityTone } = await import('../../src/pages/activity-view.ts');
test('B5 activityTone: completed -> success, failure-type -> danger, everything else (open-ended) -> neutral', () => {
  assert.equal(activityTone('FLOW_APPROVED'), 'success');
  assert.equal(activityTone('FLOW_PUBLISHED'), 'success');
  assert.equal(activityTone('CONTRACT_VERSION_APPROVED'), 'success');
  assert.equal(activityTone('CREDENTIAL_SOURCE_REVOKED'), 'danger');
  assert.equal(activityTone('DB_TABLE_REVOKED'), 'danger');
  assert.equal(activityTone('SOMETHING_FAILED'), 'danger');
  assert.equal(activityTone('FLOW_DRAFT_SAVED'), 'neutral');
  assert.equal(activityTone('MAPPING_SAVED'), 'neutral');
  assert.equal(activityTone('a-brand-new-action'), 'neutral');
  assert.equal(activityTone(''), 'neutral');
});
test('B5 Activity renders the action as a badge in the list rows and the event detail, text unchanged', () => {
  const src = read('pages/Activity.tsx');
  assert.equal(count(src, /className=\{`badge badge-\$\{activityTone\(/g), 2, 'row and detail');
  assert.match(src, /data-activity-row=/, 'row hook kept');
  assert.match(src, /data-actor-cell/, 'actor hook kept');
});

// B8 - Delivery report: stuck and failed counts above zero are danger badges; zero is plain text.
const { countTone } = await import('../../src/pages/delivery-report-view.ts');
test('B8 countTone: > 0 is danger, zero (and junk) is plain', () => {
  assert.equal(countTone(1), 'danger');
  assert.equal(countTone(250), 'danger');
  assert.equal(countTone(0), 'plain');
  assert.equal(countTone(-1), 'plain');
  assert.equal(countTone(Number.NaN), 'plain');
});
const row = (over) => ({ interfaceId: 'i', targetId: 't', successes: 5, failuresTransient: 0, failuresAmbiguous: 0, failuresPermanent: 0, retriesMade: 0, retriedThenSucceeded: 0, stillStuck: 0, ...over });
const badges = (tree) => findAll(tree, (n) => typeof n.props?.className === 'string' && /\bbadge-danger\b/.test(n.props.className));
test('B8 the delivery report shows failures and still-stuck as danger badges only when above zero; cells stay .num', async () => {
  const stub = (data) => ({ deliveryReport: async () => ({ ok: true, data }) });
  const bad = await mount('pages/DeliveryReport.tsx', 'default', {}, stub([row({ failuresPermanent: 2, stillStuck: 3 })]), ['../api/reports']);
  await bad.settle();
  assert.equal(badges(bad.tree()).length, 2, 'failures and still stuck');
  const num = findAll(bad.tree(), (n) => n.type === 'td' && /\bnum\b/.test(n.props?.className ?? ''));
  assert.equal(num.length, 6, 'all six number cells keep .num (right-aligned tabular)');
  assert.equal(findAll(bad.tree(), (n) => n.props?.['data-report-row'] !== undefined).length, 1, 'row hook kept');
  const ok = await mount('pages/DeliveryReport.tsx', 'default', {}, stub([row({})]), ['../api/reports']);
  await ok.settle();
  assert.equal(badges(ok.tree()).length, 0, 'zero stays plain text');
});
