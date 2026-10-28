// Lane C - ContractsScreen + Import Contract drawer + Contract detail drawer, rendered under node --test
// with the lane-c harness (presentational screen, props only: no fetch, no routing).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mount, findAll, childElements, textOf, click } from './lane-c-harness.mjs';

const PAGE = 'screens/contracts/ContractsScreen';
const NAME = 'ContractsScreen';

const ROWS = [
  { name: 'ORDERS-EXT', version: '2.1', format: 'XML', flowsUsing: 7, updated: '28 Oct' },
  { name: 'CUSTOMS-JSON', version: '1.4', format: 'JSON', flowsUsing: 3, updated: '27 Oct' },
  { name: 'INVOIC-EDIFACT', version: '3.0', format: 'EDIFACT', flowsUsing: 0, updated: '20 Oct' }
];

const DETAIL = {
  name: 'ORDERS-EXT', version: '2.1', format: 'XML', updated: '28 Oct',
  usedBy: ['orders-sync', 'orders-cancel'],
  schemaCode: '<xs:schema>...orders...</xs:schema>', schemaLanguage: 'xml',
  validationRules: [{ field: 'orderId', rule: 'required' }]
};

const byLabel = (list, text) => list.find((b) => textOf(b).includes(text));
const region = (tree, name) => findAll(tree, (n) => n.props?.['data-region'] === name);

async function mountGrid(props = {}) {
  return mount(PAGE, 'default', {
    rows: ROWS,
    detail: null,
    detailOpen: false,
    importOpen: false,
    importErrors: null,
    importFileName: null,
    importUploading: false,
    ...props
  });
}

test('contracts grid renders rows as a DataTable with mono identifiers and a format StatusBadge per row', async () => {
  const page = await mountGrid();
  const [dt] = page.components('DataTable');
  assert.ok(dt, 'a DataTable is rendered');
  const labels = dt.columns.map((c) => c.key);
  assert.deepEqual(labels, ['name', 'version', 'format', 'flowsUsing', 'updated']);
  assert.equal(dt.columns.find((c) => c.key === 'name').mono, true, 'contract name column is mono');
  assert.equal(typeof dt.columns.find((c) => c.key === 'format').render, 'function', 'format column renders a StatusBadge');
  const format = dt.columns.find((c) => c.key === 'format').render(ROWS[0]);
  assert.equal(page.components('StatusBadge').length, ROWS.length, 'one format badge per row');
  assert.equal(textOf(format), 'XML');
  assert.equal(dt.rows.length, ROWS.length);
});

test('contracts region states: loading/empty/error are rendered per region with a Retry on error', async () => {
  const loading = await mountGrid({ rows: undefined, loading: true });
  assert.ok(region(loading.tree(), 'loading').length >= 1, 'loading region present');
  assert.equal(region(loading.tree(), 'table').length, 0, 'no table while loading');

  const empty = await mountGrid({ rows: [] });
  assert.ok(region(empty.tree(), 'empty').length >= 1, 'empty region present');

  let retries = 0;
  const failed = await mountGrid({ rows: undefined, error: 'schema service unreachable', onRetry: () => { retries++; } });
  assert.ok(region(failed.tree(), 'error').length >= 1, 'error region present');
  assert.ok(textOf(failed.tree()).includes('schema service unreachable'), 'error message shown');
  click(byLabel(failed.components('Button'), 'Retry'));
  assert.equal(retries, 1, 'Retry calls onRetry');
});

test('clicking a contract row calls onRowSelect with the row; header primary is Import Contract', async () => {
  let picked = null;
  const page = await mountGrid({ onRowSelect: (row) => { picked = row; } });
  const [dt] = page.components('DataTable');
  dt.onRowClick(ROWS[1]);
  assert.equal(picked, ROWS[1]);
  // one primary ("accent") button per header, placed in the header actions
  const primary = page.components('Button').filter((b) => b.variant !== undefined);
  assert.ok(byLabel(page.components('Button'), 'Import Contract'), 'header button Import Contract present');
  assert.equal(primary.filter((b) => textOf(b).includes('Import Contract')).length, 1);
});

test('import drawer: dropzone upload forwards files to onSchemaUpload; validation errors render in place', async () => {
  let uploaded = null;
  const page = await mountGrid({
    importOpen: true,
    onSchemaUpload: (files) => { uploaded = files; },
    importErrors: [{ path: 'order.line[3]', message: 'maxLength exceeded' }]
  });
  const [dz] = page.components('Dropzone');
  assert.ok(dz, 'Dropzone rendered inside the import drawer');
  const file = { name: 'orders.xsd' };
  dz.onFiles([file]);
  assert.deepEqual(uploaded, [file], 'onFiles reaches onSchemaUpload');

  const callouts = page.components('Callout');
  assert.ok(callouts.some((c) => textOf(c).includes('order.line[3]') && textOf(c).includes('maxLength exceeded')), 'validation error rendered in place in the drawer');
  // import drawer is the only open drawer - it is open, detail stays closed
  const drawers = page.components('Drawer');
  assert.equal(drawers.length, 1);
  assert.equal(drawers[0].open, true);
  assert.equal(drawers[0].title, 'Import Contract');
});

test('import drawer: footer Cancel/Import - Import submits the draft and disabled while uploading', async () => {
  let submitted = null;
  const closed = await mountGrid({ importOpen: true, importUploading: true, onImportSubmit: (d) => { submitted = d; } });
  const cancel = byLabel(closed.components('Button'), 'Cancel');
  const imp = byLabel(closed.components('Button'), 'Import');
  assert.ok(imp, 'Import button present');
  assert.equal(imp.disabled, true, 'Import is disabled while uploading');
  assert.ok(byLabel(closed.components('Button'), 'Close') === undefined, 'no stray footer close in import drawer');

  const page = await mountGrid({
    importOpen: true,
    onImportSubmit: (d) => { submitted = d; }
  });
  const [nameInput] = page.components('Input').filter((i) => i.placeholder === 'Contract name');
  assert.ok(nameInput, 'contract name input present');
  nameInput.onChange({ target: { value: 'ORDERS-V3' } });
  await page.settle();
  click(byLabel(page.components('Button'), 'Import'));
  assert.equal(submitted.name, 'ORDERS-V3', 'typed name reaches the payload');
  assert.ok(typeof submitted.version === 'string' && submitted.version.length > 0, 'version defaulted');
  assert.equal(typeof submitted.format, 'string', 'format present');
  assert.equal(submitted.autoGenerateRules, true, 'autoGenerateRules default on');
  click(byLabel(page.components('Button'), 'Cancel'));
  assert.equal(submitted && submitted.__cancelled === undefined, true);
});

test('import drawer Cancel/overlay close calls onImportClose once', async () => {
  let closes = 0;
  const page = await mountGrid({ importOpen: true, onImportClose: () => { closes++; } });
  click(byLabel(page.components('Button'), 'Cancel'));
  page.components('Drawer')[0].onClose();
  assert.equal(closes, 2);
});

test('detail drawer: renders contract info, used-by tags, schema preview and validation rules', async () => {
  let closed = 0;
  let downloaded = 0;
  let edited = 0;
  const page = await mountGrid({
    detailOpen: true,
    detail: DETAIL,
    onDetailClose: () => { closed++; },
    onDownloadSchema: () => { downloaded++; },
    onEditContract: () => { edited++; }
  });
  const drawers = page.components('Drawer');
  assert.equal(drawers.length, 1);
  assert.equal(drawers[0].title, 'ORDERS-EXT');
  assert.equal(drawers[0].open, true);
  assert.ok(page.components('TagList')[0]?.tags.includes('orders-sync'), 'used-by tags rendered');
  assert.ok(page.components('CodePreview')[0]?.code.includes('xs:schema'), 'schema preview rendered');
  assert.ok(page.components('KeyValue').length >= 3, 'info + validation rules key-values rendered');
  click(byLabel(page.components('Button'), 'Download'));
  click(byLabel(page.components('Button'), 'Edit Contract'));
  assert.equal(downloaded, 1);
  assert.equal(edited, 1);
  drawers[0].onClose();
  assert.equal(closed, 1);
});

test('detail drawer label adapts: JSON contract downloads a JSON schema, not an XSD', async () => {
  const page = await mountGrid({ detailOpen: true, detail: { ...DETAIL, format: 'JSON' } });
  assert.ok(byLabel(page.components('Button'), 'Download JSON'), 'Download JSON present');
  assert.equal(byLabel(page.components('Button'), 'XSD'), undefined);
});