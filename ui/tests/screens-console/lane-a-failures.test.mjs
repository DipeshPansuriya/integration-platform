// Lane A - Failures screen tests. Pure vm helpers plus the presentational contract:
// Action column hidden entirely without canOperate (row-582), exhausted failures escalate
// instead of retrying, and both Retry All / Escalate confirm through the dialog.
import test from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from './lane-a-harness.mjs';

const vm = await import('../../src/screens/failures/failures-vm.ts');

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

const ROWS = [
  { id: 'm1', code: 'ERR-4101', message: 'Invoice push timed out', flow: 'orders-to-billing', time: '09:12', retriesUsed: 1, retriesMax: 3 },
  { id: 'm2', code: 'ERR-4202', message: 'Field mapping rejected', flow: 'stock-to-warehouse', time: '09:15', retriesUsed: 3, retriesMax: 3 },
  { id: 'm3', code: 'ERR-4303', message: 'Rate limited', flow: 'orders-to-billing', time: '09:16', retriesUsed: 0, retriesMax: 3 },
];

const screen = async (overrides = {}) => {
  const view = await mount('screens/failures/FailuresScreen.tsx', 'FailuresScreen', {
    failures: ROWS,
    ...overrides,
  }, {}, []);
  await view.settle();
  return view;
};

const byTest = (view, value) => findAll(view.tree(), (n) => n && n.props && n.props['data-test'] === value);
const buttons = (view) => view.components('Button').map((b, i) => ({ i, ...b }));

test('failures vm: subtitle plurals', () => {
  assert.equal(vm.failuresSubtitle(0), 'No unresolved failures');
  assert.equal(vm.failuresSubtitle(1), '1 unresolved failure');
  assert.equal(vm.failuresSubtitle(4), '4 unresolved failures');
});

test('failures vm: exhaustion and retries label', () => {
  assert.equal(vm.isExhausted({ retriesUsed: 3, retriesMax: 3 }), true);
  assert.equal(vm.isExhausted({ retriesUsed: 1, retriesMax: 3 }), false);
  assert.equal(vm.retriesLabel({ retriesUsed: 1, retriesMax: 3 }), '1/3');
  assert.equal(vm.unresolvedCount(ROWS), 3);
});

test('failures: default screen hides the Action column and shows the count subtitle', async () => {
  const view = await screen();
  assert.equal(view.components('PageHeader')[0].props.subtitle, '3 unresolved failures');
  const table = view.components('DataTable')[0];
  assert.deepEqual(table.props.columns.map((c) => c.key), ['code', 'flow', 'time', 'retries']);
  const retries = table.props.columns.find((c) => c.key === 'retries');
  assert.equal(retries.align, 'right');
  assert.equal(retries.numeric, true);
  assert.equal(retries.render(ROWS[0]), '1/3');
  assert.equal(table.props.rows, ROWS);
  assert.equal(table.props.caption, '3 unresolved failures');
});

test('failures: canOperate adds the Action column', async () => {
  const view = await screen({ canOperate: true });
  const keys = view.components('DataTable')[0].props.columns.map((c) => c.key);
  assert.deepEqual(keys, ['code', 'flow', 'time', 'retries', 'action']);
});

test('failures: Retry fires per row only for non-exhausted failures', async () => {
  const onRetry = spy();
  const view = await screen({ canOperate: true, onRetry });
  const retryButtons = buttons(view).filter((b) => text(b.props) === 'Retry');
  assert.equal(retryButtons.length, 2);
  assert.equal(view.components('Button').some((b) => text(b.props) === 'Escalate'), true);
  retryButtons[0].props.onClick();
  assert.deepEqual(onRetry.calls, [['m1']]);
});

test('failures: escalate flow confirms the exhausted failure through the dialog', async () => {
  const onEscalate = spy();
  const onRetry = spy();
  const view = await screen({ canOperate: true, onEscalate, onRetry });
  const escalate = buttons(view).find((b) => text(b.props) === 'Escalate');
  assert.equal(escalate.props.variant, 'danger');
  escalate.props.onClick();
  await view.settle();
  const dialog = view.components('ConfirmDialog')[0];
  assert.equal(dialog.props.open, true);
  assert.equal(dialog.props.title, 'Escalate this failure');
  assert.ok(dialog.props.body.includes('ERR-4202'));
  const copies = buttons(view).filter((b) => text(b.props) === 'Escalate');
  assert.equal(copies.length, 2);
  copies[copies.length - 1].props.onClick();
  await view.settle();
  assert.deepEqual(onEscalate.calls, [['m2']]);
  assert.deepEqual(onRetry.calls, []);
  assert.equal(view.components('ConfirmDialog')[0].props.open, false);
});

test('failures: Retry All confirms through the dialog before firing onRetryAll', async () => {
  const onRetryAll = spy();
  const view = await screen({ onRetryAll });
  const header = buttons(view).filter((b) => text(b.props) === 'Retry All');
  assert.equal(header.length, 1);
  header[0].props.onClick();
  await view.settle();
  const dialog = view.components('ConfirmDialog')[0];
  assert.equal(dialog.props.open, true);
  assert.equal(dialog.props.title, 'Retry all failures');
  const copies = buttons(view).filter((b) => text(b.props) === 'Retry All');
  assert.equal(copies.length, 2);
  copies[copies.length - 1].props.onClick();
  await view.settle();
  assert.deepEqual(onRetryAll.calls, [[]]);
  assert.equal(view.components('ConfirmDialog')[0].props.open, false);
});

test('failures: empty list disables Retry All and shows the all-clear empty state', async () => {
  const view = await screen({ failures: [] });
  const header = buttons(view).find((b) => text(b.props) === 'Retry All');
  assert.equal(header.props.disabled, true);
  const empty = byTest(view, 'empty');
  assert.equal(empty.length, 1);
  assert.ok(text(empty[0]).includes('No unresolved failures'));
  assert.equal(view.components('DataTable').length, 0);
});

test('failures: unresolvedCount prop overrides the header subtitle', async () => {
  const view = await screen({ unresolvedCount: 9 });
  assert.equal(view.components('PageHeader')[0].props.subtitle, '9 unresolved failures');
});

test('failures: loading shows six skeleton blocks and no table', async () => {
  const view = await screen({ loading: true });
  assert.equal(byTest(view, 'skeleton').length, 1);
  assert.equal(view.components('SkeletonBlock').length, 6);
  assert.equal(view.components('DataTable').length, 0);
});

test('failures: error shows the error note and its Retry fires onReload', async () => {
  const onReload = spy();
  const view = await screen({ error: 'Ledger unreachable', onReload });
  assert.equal(byTest(view, 'error').length, 1);
  assert.equal(view.components('Callout')[0].props.tone, 'err');
  buttons(view).find((b) => text(b.props) === 'Retry').props.onClick();
  assert.deepEqual(onReload.calls, [[]]);
});

test('failures: stale flag renders the stale line', async () => {
  const view = await screen({ error: 'Ledger unreachable', stale: true });
  assert.equal(byTest(view, 'stale').length, 1);
  assert.equal(view.components('DataTable').length, 0);
});