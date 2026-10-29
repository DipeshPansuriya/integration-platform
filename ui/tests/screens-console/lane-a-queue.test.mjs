// Lane A - Queue screen tests. Presentational contract: KPIs and items as typed props;
// pause-all / retry-failed go through the lane-local confirm dialog before callbacks.
import test from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from './lane-a-harness.mjs';

function text(node) {
  if (node == null || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(text).join('');
  if (typeof node === 'object') {
    if (node.props != null) return text(node.props.children);
    if ('children' in node) return text(node.children);
  }
  return '';
}

function spy() {
  const calls = [];
  const fn = (...args) => { calls.push(args); };
  fn.calls = calls;
  return fn;
}

const KPIS = [
  { value: '1,204', label: 'Waiting', tone: 'accent' },
  { value: '38', label: 'Delayed', tone: 'warn' },
  { value: '6', label: 'Poisoned', tone: 'err' },
];
const ITEMS = [
  { id: 'msg-1001', flow: 'orders-to-billing', priority: 'High', queuedAt: '09:12:03', status: 'Waiting' },
  { id: 'msg-1002', flow: 'stock-to-warehouse', priority: 'Normal', queuedAt: '09:15:44', status: 'Waiting' },
  { id: 'msg-1003', flow: 'orders-to-billing', priority: 'Low', queuedAt: '09:16:02', status: 'Delayed' },
];

const screen = async (overrides = {}) => {
  const view = await mount('screens/queue/QueueScreen.tsx', 'QueueScreen', {
    kpis: KPIS,
    items: ITEMS,
    ...overrides,
  }, {}, []);
  await view.settle();
  return view;
};

const byTest = (view, value) => findAll(view.tree(), (n) => n && n.props && n.props['data-test'] === value);
const buttons = (view) => view.components('Button').map((b, i) => ({ i, ...b }));

test('queue: KPI cards render with size lg', async () => {
  const view = await screen();
  const cards = view.components('KpiCard');
  assert.equal(cards.length, 3);
  KPIS.forEach((k, i) => {
    assert.equal(cards[i].props.label, k.label);
    assert.equal(cards[i].props.tone, k.tone);
    assert.equal(cards[i].props.size, 'lg');
  });
});

test('queue: header actions are Pause All (secondary) then Retry Failed (primary)', async () => {
  const view = await screen();
  assert.deepEqual(buttons(view).map((b) => text(b.props)), ['Pause All', 'Retry Failed', 'Auto-refresh: OFF']);
  const primaries = buttons(view).filter((b) => b.props.variant === 'primary');
  assert.equal(primaries.length, 1);
  assert.equal(text(primaries[0].props), 'Retry Failed');
});

test('queue: subtitle carries the waiting count', async () => {
  const view = await screen();
  assert.equal(view.components('PageHeader')[0].props.subtitle, '3 messages waiting');
});

test('queue: items table keeps the five typed columns and fires status badges', async () => {
  const view = await screen();
  const table = view.components('DataTable')[0];
  assert.equal(table.props.rows, ITEMS);
  assert.deepEqual(table.props.columns.map((c) => c.key), ['id', 'flow', 'priority', 'queuedAt', 'status']);
  assert.equal(table.props.caption, '3 messages in the queue');
  assert.equal(view.components('StatusBadge').length, 6);
  assert.equal(view.components('IconButton').length, 1);
  assert.equal(view.components('IconButton')[0].props.label, 'Refresh queue');
});

test('queue: aside IconButton fires onRefresh', async () => {
  const onRefresh = spy();
  const view = await screen({ onRefresh });
  view.components('IconButton')[0].props.onClick();
  assert.deepEqual(onRefresh.calls, [[]]);
});

test('queue: aside auto-refresh reflects the prop and toggles it', async () => {
  const [firedOn, firedOff] = [spy(), spy()];
  const on = await screen({ autoRefresh: true, onAutoRefreshChange: firedOn });
  const toggleOn = buttons(on).find((b) => text(b.props).startsWith('Auto-refresh:'));
  assert.ok(text(toggleOn.props).endsWith('ON'));
  assert.equal(toggleOn.props['aria-pressed'], true);
  toggleOn.props.onClick();
  assert.deepEqual(firedOn.calls, [[false]]);

  const off = await screen({ autoRefresh: false, onAutoRefreshChange: firedOff });
  const toggleOff = buttons(off).find((b) => text(b.props).startsWith('Auto-refresh:'));
  assert.ok(text(toggleOff.props).endsWith('OFF'));
  toggleOff.props.onClick();
  assert.deepEqual(firedOff.calls, [[true]]);
});

test('queue: Pause All opens the confirm dialog, Cancel keeps callbacks silent', async () => {
  const onPauseAll = spy();
  const view = await screen({ onPauseAll });
  const header = buttons(view).find((b) => text(b.props) === 'Pause All' && b.props.variant === 'secondary');
  header.props.onClick();
  await view.settle();
  const dialog = view.components('ConfirmDialog')[0];
  assert.equal(dialog.props.open, true);
  assert.equal(dialog.props.title, 'Pause all queues');
  assert.equal(findAll(view.tree(), (n) => n && n.props && n.props.role === 'dialog').length, 1);
  const cancel = buttons(view).find((b) => text(b.props) === 'Cancel');
  cancel.props.onClick();
  await view.settle();
  assert.equal(view.components('ConfirmDialog')[0].props.open, false);
  assert.deepEqual(onPauseAll.calls, []);
});

test('queue: confirming Pause All as danger fires onPauseAll once and closes the dialog', async () => {
  const onPauseAll = spy();
  const view = await screen({ onPauseAll });
  buttons(view).find((b) => text(b.props) === 'Pause All' && b.props.variant === 'secondary').props.onClick();
  await view.settle();
  const confirm = buttons(view).find((b) => text(b.props) === 'Pause All' && b.props.variant === 'danger');
  assert.ok(confirm);
  confirm.props.onClick();
  await view.settle();
  assert.deepEqual(onPauseAll.calls, [[]]);
  assert.equal(view.components('ConfirmDialog')[0].props.open, false);
});

test('queue: Retry Failed confirms through the dialog before firing onRetryFailed', async () => {
  const onRetryFailed = spy();
  const view = await screen({ onRetryFailed });
  const header = buttons(view).filter((b) => text(b.props) === 'Retry Failed');
  assert.equal(header.length, 1);
  header[0].props.onClick();
  await view.settle();
  const copies = buttons(view).filter((b) => text(b.props) === 'Retry Failed');
  assert.equal(copies.length, 2);
  copies[copies.length - 1].props.onClick();
  await view.settle();
  assert.deepEqual(onRetryFailed.calls, [[]]);
  assert.equal(view.components('ConfirmDialog')[0].props.open, false);
});

test('queue: empty items show the empty state with a Refresh CTA and no table', async () => {
  const view = await screen({ items: [] });
  assert.equal(view.components('PageHeader')[0].props.subtitle, 'No messages waiting');
  assert.equal(byTest(view, 'empty').length, 1);
  assert.ok(text(byTest(view, 'empty')[0]).includes('The queue is empty.'));
  assert.equal(view.components('DataTable').length, 0);
  const cta = buttons(view).find((b) => text(b.props) === 'Refresh' && b.props.variant === 'secondary');
  assert.ok(cta);
});

test('queue: loading shows the skeleton and no KPI cards', async () => {
  const view = await screen({ loading: true });
  assert.equal(byTest(view, 'skeleton').length, 1);
  assert.equal(view.components('KpiCard').length, 0);
  assert.equal(view.components('DataTable').length, 0);
});

test('queue: error shows the error note and its Retry fires onReload', async () => {
  const onReload = spy();
  const view = await screen({ error: 'Queue service unreachable', onReload });
  assert.equal(byTest(view, 'error').length, 1);
  assert.equal(view.components('Callout')[0].props.tone, 'err');
  buttons(view).find((b) => text(b.props) === 'Retry').props.onClick();
  assert.deepEqual(onReload.calls, [[]]);
});