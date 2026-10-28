import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from '../ui/hook-harness.mjs';
import { successBand, STALE_BANDS } from '../../src/screens/reports/reports-vm.ts';

const by = (screen, name, value) =>
  findAll(screen.tree(), (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value));

const textOf = (node) =>
  Array.isArray(node) ? node.map(textOf).join('') : typeof node === 'object' && node ? textOf(node.props?.children) : String(node ?? '');

const ROWS = [
  { flow: 'ORD-7', sent: 120, delivered: 120, failed: 0, successPct: 100 },
  { flow: 'INV-2', sent: 50, delivered: 49, failed: 1, successPct: 99.8 },
  { flow: 'ASN-1', sent: 20, delivered: 17, failed: 3, successPct: 95 },
];

test('reports renders four KPI cards with expected tones', async () => {
  const page = await mount(
    'screens/reports/ReportsScreen.tsx',
    'ReportsScreen',
    { range: '24H', rows: ROWS },
    {},
    [],
  );
  const kpis = page.components('KpiCard');
  assert.equal(kpis.length, 4);
  const tones = kpis.map((k) => k.tone).sort();
  assert.deepEqual(tones, ['accent', 'err', 'ok', 'ok'], 'ok, ok, accent, err');
  const labels = kpis.map((k) => k.label);
  assert.match(labels.join('|'), /Success/);
  assert.match(labels.join('|'), /Delivered/);
  assert.match(labels.join('|'), /latency/i);
  assert.match(labels.join('|'), /Failed/i);
  page.unmount();
});

test('reports range callbacks fire with the chosen value', async () => {
  let seen = null;
  const page = await mount(
    'screens/reports/ReportsScreen.tsx',
    'ReportsScreen',
    { range: '24H', rows: ROWS, onRangeChange: (v) => { seen = v; } },
    {},
    [],
  );
  const seg = page.components('Segmented');
  assert.equal(seg.length, 1, 'Segmented present');
  // DS Segmented renders plain <button aria-pressed> options with the range as label text.
  const btnText = (tag) => String(tag ?? '').trim();
  const opt = (label) =>
    findAll(page.tree(), (n) => String(n.type) === 'button' && btnText(textOf(n.props?.children)) === label);
  const current = opt('24H').find((b) => b.props['aria-pressed'] === true);
  assert.ok(current, "current range ('24H') is marked aria-pressed");
  const btn7d = opt('7D');
  assert.equal(btn7d.length, 1, 'a 7D range button is rendered');
  btn7d[0].props.onClick();
  assert.equal(seen, '7D');
  page.unmount();
});

test('successBand: interim thresholds (>=99.9 ok, 99.8 warn, below err)', () => {
  assert.equal(successBand(100), 'ok');
  assert.equal(successBand(99.9), 'ok');
  assert.equal(successBand(99.8), 'warn');
  assert.equal(successBand(95), 'err');
  assert.deepEqual(STALE_BANDS, { ok: 99.9, warn: 99.8, err: 0 }, 'constant thresholds documented');
});

test('reports table headers and mono flow cells', async () => {
  const page = await mount('screens/reports/ReportsScreen.tsx', 'ReportsScreen', { range: '24H', rows: ROWS }, {}, []);
  const table = page.components('DataTable');
  assert.equal(table.length, 1);
  const cols = table[0].columns;
  assert.deepEqual(cols.map((c) => c.key), ['flow', 'sent', 'delivered', 'failed', 'successPct']);
  assert.equal(cols.find((c) => c.key === 'flow').mono, true, 'flow column is mono');
  assert.equal(cols.find((c) => c.key === 'successPct').numeric, true, 'success % numeric');
  const head = textOf(page.tree());
  assert.match(head, /Delivery History/);
  assert.match(head, /Success %/);
  const ids = findAll(page.tree(), (n) => String(n.props?.className || '') === 'ip-id');
  assert.ok(ids.length >= 1, 'mono flow values wrapped in ip-id');
  page.unmount();
});

test('reports download button routes to handler and is the primary action', async () => {
  let downloaded = 0;
  const page = await mount(
    'screens/reports/ReportsScreen.tsx',
    'ReportsScreen',
    { range: '24H', rows: ROWS, onDownload: () => { downloaded += 1; } },
    {},
    [],
  );
  const btns = page.components('Button');
  assert.ok(btns.length >= 1);
  const dlBtn = by(page, 'data-action', 'download');
  assert.equal(dlBtn.length, 1, 'exactly one download button');
  assert.ok(
    String(dlBtn[0].props?.className || '').includes('ip-btn--primary'),
    'download is the primary header action (DS primary variant class)',
  );
  dlBtn[0].props.onClick();
  assert.equal(downloaded, 1);
  page.unmount();
});

test('reports loading / empty / error regions', async () => {
  const loading = await mount('screens/reports/ReportsScreen.tsx', 'ReportsScreen', { loading: true }, {}, []);
  assert.equal(loading.components('DataTable').length, 0);
  assert.ok(findAll(loading.tree(), (n) => String(n.props?.className || '').includes('skeleton')).length > 0);
  loading.unmount();

  const empty = await mount('screens/reports/ReportsScreen.tsx', 'ReportsScreen', { range: '7D', rows: [] }, {}, []);
  assert.equal(empty.components('DataTable').length, 0);
  const emptyRegion = by(empty, 'data-empty', 'report');
  assert.equal(emptyRegion.length, 1);
  assert.match(textOf(empty.tree()), /No delivery data/);
  empty.unmount();

  let retried = 0;
  const err = await mount(
    'screens/reports/ReportsScreen.tsx',
    'ReportsScreen',
    { range: '24H', rows: ROWS, error: 'load failed', onRetry: () => { retried += 1; } },
    {},
    [],
  );
  const retriedBtn = by(err, 'data-action', 'retry');
  assert.equal(retriedBtn.length, 1, 'error callout carries a Retry action');
  retriedBtn[0].props.onClick();
  assert.equal(retried, 1);
  const stale = by(err, 'data-stale', 'true');
  assert.equal(stale.length >= 1, true, 'last good rows kept and marked stale');
  err.unmount();
});