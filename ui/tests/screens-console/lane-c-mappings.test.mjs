import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mount, findAll, textOf, click } from './lane-c-harness.mjs';

const PAGE = 'screens/mappings/MappingsScreen';
const ROWS = [
  { name: 'ORDERS-EXT-MAP', sourceFormat: 'XML', targetFormat: 'JSON', fields: 12, usedBy: 'orders-sync', updated: '28 Oct' },
  { name: 'CUSTOMS-FLAT', sourceFormat: 'JSON', targetFormat: 'XML', fields: 8, usedBy: null, updated: '27 Oct' }
];

const DETAIL = {
  name: 'ORDERS-EXT-MAP', sourceFormat: 'XML', targetFormat: 'JSON', updated: '28 Oct',
  fields: [
    { source: 'orderId', target: 'id', transform: 'string' },
    { source: 'amount', target: 'value.amount', transform: 'number' }
  ],
  canvas: { source: '<order>', target: '{ id }', transform: 'x' }
};

const byLabel = (list, text) => list.find((b) => textOf(b).includes(text));
const region = (tree, name) => findAll(tree, (n) => n.props?.['data-region'] === name);
const hostInputs = (tree, placeholder) =>
  findAll(tree, (n) => n.type === 'input' && n.props?.placeholder === placeholder);
const selects = (tree) => findAll(tree, (n) => n.type === 'select');

async function mountMappings(props = {}) {
  return mount(PAGE, 'default', {
    rows: ROWS, detail: null, detailOpen: false, newOpen: false, saving: false,
    contracts: [{ name: 'ORDERS-EXT', format: 'XML' }],
    ...props
  });
}

test('mappings grid renders rows as a DataTable with mono identifiers and counts', async () => {
  const page = await mountMappings();
  const [dt] = page.components('DataTable');
  assert.ok(dt, 'a DataTable is rendered');
  assert.deepEqual(dt.columns.map((c) => c.key), ['name', 'sourceFormat', 'targetFormat', 'fields', 'usedBy', 'updated']);
  assert.equal(dt.columns.find((c) => c.key === 'name').mono, true, 'mapping name column is mono');
  assert.equal(dt.columns.find((c) => c.key === 'fields').numeric, true, 'fields column is numeric');
  assert.equal(dt.rows.length, ROWS.length);
  assert.ok(byLabel(page.components('Button'), 'New Mapping'), 'header button New Mapping present');
});

test('mappings region states: loading/empty/error with Retry on error', async () => {
  const loading = await mountMappings({ rows: undefined, loading: true });
  assert.ok(region(loading.tree(), 'loading').length >= 1, 'loading region present');
  const empty = await mountMappings({ rows: [] });
  assert.ok(region(empty.tree(), 'empty').length >= 1, 'empty region present');
  let retries = 0;
  const failed = await mountMappings({ rows: undefined, error: 'mappings service unreachable', onRetry: () => { retries++; } });
  assert.ok(region(failed.tree(), 'error').length >= 1, 'error region present');
  click(byLabel(failed.components('Button'), 'Retry'));
  assert.equal(retries, 1, 'Retry calls onRetry');
});

test('clicking a mapping row calls onRowSelect with the row', async () => {
  let picked = null;
  const page = await mountMappings({ onRowSelect: (r) => { picked = r; } });
  page.components('DataTable')[0].onRowClick(ROWS[1]);
  assert.equal(picked, ROWS[1]);
});

test('detail drawer: field map table renders + canvas + footer actions', async () => {
  let tested = 0; let edited = 0; let closed = 0;
  const page = await mountMappings({
    detailOpen: true, detail: DETAIL,
    onDetailClose: () => { closed++; }, onTestMapping: () => { tested++; }, onEditMapping: () => { edited++; }
  });
  const drawers = page.components('Drawer');
  assert.equal(drawers.length, 1, 'only the detail drawer is open');
  assert.equal(drawers[0].title, DETAIL.name);
  const [ft] = page.components('DecisionTable');
  assert.ok(ft, 'a field map DecisionTable is rendered');
  assert.deepEqual(ft.columns.map((c) => c.key), ['source', 'target', 'transform']);
  assert.equal(ft.rows.length, DETAIL.fields.length, 'both field rows render');
  assert.ok(page.components('MappingCanvas').length >= 1, 'a MappingCanvas is rendered');
  click(byLabel(page.components('Button'), 'Test Mapping'));
  assert.equal(tested, 1);
  click(byLabel(page.components('Button'), 'Edit Mapping'));
  assert.equal(edited, 1);
  drawers[0].onClose();
  assert.equal(closed, 1);
});

test('new mapping drawer: format selects, autoMap & sampleData switches, contract select, submit draft', async () => {
  let created = null;
  const page = await mountMappings({ newOpen: true, onMappingCreate: (d) => { created = d; }, contracts: [{ name: 'ORDERS-EXT', format: 'XML' }] });
  const [drawer] = page.components('Drawer');
  assert.equal(drawer.title, 'New Mapping');
  assert.equal(drawer.open, true);
  hostInputs(page.tree(), 'Mapping name')[0].props.onChange({ target: { value: 'NEW-MAP' } });
  for (let i = 0; i < 8; i++) await page.settle();
  const sws = page.components('Switch');
  assert.ok(sws.length >= 2, 'autoMap + sampleData switches present');
  sws.find((s) => (s.label || '').toLowerCase().includes('sample'))?.onChange?.(true);
  hostInputs(page.tree(), 'Mapping name')[0].props.onChange({ target: { value: 'NEW-MAP' } });
  for (let i = 0; i < 8; i++) await page.settle();
  click(page.components('Button').find((b) => textOf(b) === 'Create Mapping'));
  for (let i = 0; i < 8; i++) await page.settle();
  assert.ok(created, 'submit fired');
  assert.equal(created.name, 'NEW-MAP');
  assert.equal(created.sourceFormat, 'XML');
  assert.equal(created.targetFormat, 'JSON');
  assert.equal(created.autoMap, true);
  assert.equal(created.sampleData, true);

  const saving = await mountMappings({ newOpen: true, saving: true, onMappingCreate: () => {} });
  assert.equal(saving.components('Button').find((b) => textOf(b) === 'Create Mapping').disabled, true, 'disabled while saving');
});