// Lane C - RulesScreen + New Rule drawer + rule detail drawer (decision table + tester), rendered under
// node --test with the lane-c harness (presentational screen, props only: no fetch, no routing).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mount, findAll, textOf, click } from './lane-c-harness.mjs';

const PAGE = 'screens/rules/RulesScreen';
const NAME = 'RulesScreen';

const ROWS = [
  { name: 'PRIORITY-HIGH', conditions: 2, outputs: 1, hitRate: 42, updated: '28 Oct' },
  { name: 'VAT-EXEMPT', conditions: 1, outputs: 2, hitRate: 12, updated: '27 Oct' }
];

const DETAIL = {
  name: 'PRIORITY-HIGH', updated: '28 Oct',
  columns: [
    { key: 'region', header: 'Region' },
    { key: 'band', header: 'Order band' }
  ],
  rows: [
    { region: 'EU', band: 'gold', output: 'Priority express' },
    { region: 'US', band: 'gold', output: 'Priority standard' }
  ]
};

const byLabel = (list, text) => list.find((b) => textOf(b).includes(text));
const region = (tree, name) => findAll(tree, (n) => n.props?.['data-region'] === name);
const inputsWith = (tree, placeholder) =>
  findAll(tree, (n) => n.type === 'input' && n.props?.placeholder === placeholder);

async function mountRules(props = {}) {
  return mount(PAGE, 'default', {
    rows: ROWS,
    detail: null,
    detailOpen: false,
    newOpen: false,
    saving: false,
    ...props
  });
}

test('rules grid renders rows as a DataTable with mono identifiers and numeric hit rate', async () => {
  const page = await mountRules();
  const [dt] = page.components('DataTable');
  assert.ok(dt, 'a DataTable is rendered');
  assert.deepEqual(dt.columns.map((c) => c.key), ['name', 'conditions', 'outputs', 'hitRate', 'updated']);
  assert.equal(dt.columns.find((c) => c.key === 'name').mono, true, 'rule name column is mono');
  const cols = dt.columns.filter((c) => ['conditions', 'outputs', 'hitRate'].includes(c.key));
  assert.ok(cols.every((c) => c.numeric === true), 'count and hit-rate columns are numeric');
  assert.equal(dt.rows.length, ROWS.length);
  assert.ok(byLabel(page.components('Button'), 'New Rule'), 'header button New Rule present');
});

test('rules region states: loading/empty/error with Retry on error', async () => {
  const loading = await mountRules({ rows: undefined, loading: true });
  assert.ok(region(loading.tree(), 'loading').length >= 1, 'loading region present');
  assert.equal(region(loading.tree(), 'table').length, 0, 'no table while loading');

  const empty = await mountRules({ rows: [] });
  assert.ok(region(empty.tree(), 'empty').length >= 1, 'empty region present');

  let retries = 0;
  const failed = await mountRules({ rows: undefined, error: 'rules service unreachable', onRetry: () => { retries++; } });
  assert.ok(region(failed.tree(), 'error').length >= 1, 'error region present');
  click(byLabel(failed.components('Button'), 'Retry'));
  assert.equal(retries, 1, 'Retry calls onRetry');
});

test('clicking a rule row calls onRowSelect with the row', async () => {
  let picked = null;
  const page = await mountRules({ onRowSelect: (row) => { picked = row; } });
  page.components('DataTable')[0].onRowClick(ROWS[1]);
  assert.equal(picked, ROWS[1]);
});

test('detail drawer: decision table renders columns and rows; footer Close + Edit Rule primary', async () => {
  let closed = 0;
  let edited = 0;
  const page = await mountRules({
    detailOpen: true, detail: DETAIL,
    onDetailClose: () => { closed++; }, onEditRule: () => { edited++; }
  });
  const drawers = page.components('Drawer');
  assert.equal(drawers.length, 1, 'only the detail drawer is open');
  assert.equal(drawers[0].title, 'PRIORITY-HIGH');
  const [table] = page.components('DecisionTable');
  assert.ok(table, 'a DecisionTable is rendered');
  assert.deepEqual(table.columns.map((c) => c.key), ['region', 'band', 'output']);
  assert.equal(table.rows.length, DETAIL.rows.length, 'both decision rows render');
  assert.equal(table.rows[0].output, 'Priority express', 'row output value visible');
  click(byLabel(page.components('Button'), 'Edit Rule'));
  assert.equal(edited, 1);
  drawers[0].onClose();
  assert.equal(closed, 1);
});

test('rule tester: matching sample data shows "Match: Row n" with an ok callout', async () => {
  const page = await mountRules({ detailOpen: true, detail: DETAIL });
  inputsWith(page.tree(), 'Region')[0].props.onChange({ target: { value: 'US' } });
  await page.settle();
  inputsWith(page.tree(), 'Order band')[0].props.onChange({ target: { value: 'gold' } });
  await page.settle();
  click(byLabel(page.components('Button'), 'Run test'));
  await page.settle();
  await page.settle();
  await page.settle();
  await page.settle();
  await page.settle();
  const callouts = page.components('Callout');
  const hit = callouts.find((c) => (c.title || '').includes('Match: Row 2'));
  assert.ok(hit, 'match callout names the row');
  assert.equal(hit.tone, 'ok');
  const [table] = page.components('DecisionTable');
  assert.equal(table.rows[1].output, 'Priority standard', 'matching row still listed');
});

test('rule tester: non-matching sample data shows a no-match callout with a hint', async () => {
  const page = await mountRules({ detailOpen: true, detail: DETAIL });
  inputsWith(page.tree(), 'Region')[0].props.onChange({ target: { value: 'AP' } });
  await page.settle();
  click(byLabel(page.components('Button'), 'Run test'));
  await page.settle();
  await page.settle();
  await page.settle();
  await page.settle();
  await page.settle();
  const callouts = page.components('Callout');
  const miss = callouts.find((c) => (c.title || '').includes('No match'));
  assert.ok(miss, 'no-match callout rendered');
  assert.equal(miss.tone, 'warn');
  assert.ok(textOf(miss).includes('sample'), 'includes a hint to adjust the sample');
});

test('new rule drawer: add/remove condition inputs and Create Rule submits the draft', async () => {
  let created = null;
  const page = await mountRules({
    newOpen: true, saving: false,
    onRuleCreate: (d) => { created = d; }
  });
  const [drawer] = page.components('Drawer');
  assert.equal(drawer.title, 'New Rule');
  assert.equal(drawer.open, true);
  assert.equal(inputsWith(page.tree(), 'Condition 1').length, 1, 'starts with one condition input');
  click(page.components('Button').find((b) => textOf(b) === '+ Add condition'));
  await page.settle();
  assert.equal(inputsWith(page.tree(), 'Condition 2').length, 1, 'second condition input added');
  const beforeRemove = inputsWith(page.tree(), 'Condition 2').length;
  click(page.components('Button').find((b) => b['aria-label'] === 'Remove Condition 2'));
  await page.settle();
  assert.equal(inputsWith(page.tree(), 'Condition 2').length, beforeRemove - 1, 'remove drops the input');

  inputsWith(page.tree(), 'Rule name')[0].props.onChange({ target: { value: 'FRAUD-GATE' } });
  await page.settle();
  inputsWith(page.tree(), 'Condition 1')[0].props.onChange({ target: { value: 'Region' } });
  await page.settle();
  click(page.components('Button').find((b) => textOf(b) === 'Create Rule'));
  assert.equal(created.name, 'FRAUD-GATE', 'typed name reaches the payload');
  assert.deepEqual(created.conditions, ['Region'], 'condition columns reach the payload');
  assert.ok(typeof created.output === 'string', 'output default present');

  click(byLabel(page.components('Button'), 'Cancel'));
  const saved = await mountRules({ newOpen: true, saving: true, onRuleCreate: () => {} });
  assert.equal(saved.components('Button').find((b) => textOf(b) === 'Create Rule').disabled, true, 'Create Rule disabled while saving');
});