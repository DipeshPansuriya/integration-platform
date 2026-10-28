// Lane A - Dashboard screen tests. Presentational contract: data via typed props, no api
// or routing; loading/empty/error as props; primary button reserved for New Flow (last).
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

const STATS = [
  { label: 'Messages today', value: '12,408', delta: '+4%', deltaDirection: 'up', tone: 'accent' },
  { label: 'Success rate', value: '99.2%', tone: 'ok' },
  { label: 'Failed messages', value: '98', tone: 'err' },
  { label: 'Active flows', value: '42', tone: 'ok' },
];
const THROUGHPUT = { label: 'Messages per hour', data: [10, 20, 15, 30, 25, 40, 35], max: 50, height: 190 };
const TOP_FLOWS = [
  { id: 'f-1', name: 'orders-to-billing', status: 'Running', lastRun: '09:14', messages: 4102 },
  { id: 'f-2', name: 'stock-to-warehouse', status: 'Running', lastRun: '09:12', messages: 2380 },
  { id: 'f-3', name: 'returns-to-rma', status: 'Blocked', lastRun: '09:02', messages: 1210 },
  { id: 'f-4', name: 'invoice-sync', status: 'Errored', lastRun: '08:58', messages: 840 },
  { id: 'f-5', name: 'customer-sync', status: 'Running', lastRun: '09:15', messages: 512 },
  { id: 'f-6', name: 'tax-audit-feed', status: 'Draft', lastRun: '-', messages: 0 },
];
const STATUS_MIX = [
  { label: 'Running', value: 32, tone: 'ok' },
  { label: 'Blocked', value: 4, tone: 'warn' },
  { label: 'Errored', value: 2, tone: 'err' },
  { label: 'Draft', value: 9, tone: 'muted' },
];
const SYSTEMS = [
  { name: 'Payment gateway', latency: '82 ms', status: 'Healthy' },
  { name: 'Warehouse ERP', latency: '310 ms', status: 'Slow' },
  { name: 'Tax service', latency: '-', status: 'Down' },
];
const QUICK = [
  { key: 'qa-retry', icon: 'refresh', title: 'Retry failed', description: 'Requeue failed messages', tone: 'warn' },
  { key: 'qa-new', icon: 'plus', title: 'New flow', description: 'Start a blank flow from a template' },
];

const screen = async (overrides = {}) => {
  const view = await mount('screens/dashboard/DashboardScreen.tsx', 'DashboardScreen', {
    stats: STATS,
    throughput: THROUGHPUT,
    topFlows: TOP_FLOWS,
    statusMix: STATUS_MIX,
    systems: SYSTEMS,
    quickActions: QUICK,
    ...overrides,
  }, {}, []);
  await view.settle();
  return view;
};

const byTest = (view, value) => findAll(view.tree(), (n) => n && n.props && n.props['data-test'] === value);
const buttons = (view) => view.components('Button').map((b, i) => ({ i, ...b }));

test('dashboard: header title, Refresh then primary New Flow as the last action', async () => {
  const view = await screen();
  const header = view.components('PageHeader')[0];
  assert.equal(header.props.title, 'Dashboard');
  const btns = buttons(view);
  assert.deepEqual(btns.map((b) => text(b.props)), ['Refresh', 'New Flow']);
  const primaries = btns.filter((b) => b.props.variant === 'primary');
  assert.equal(primaries.length, 1);
  assert.equal(text(primaries[0].props), 'New Flow');
});

test('dashboard: primary New Flow fires onNewFlow', async () => {
  const onNewFlow = spy();
  const view = await screen({ onNewFlow });
  const primary = buttons(view).find((b) => b.props.variant === 'primary');
  primary.props.onClick();
  assert.deepEqual(onNewFlow.calls, [[]]);
});

test('dashboard: Refresh header button fires onRefresh', async () => {
  const onRefresh = spy();
  const view = await screen({ onRefresh });
  const refresh = buttons(view).find((b) => text(b.props) === 'Refresh' && b.props.variant === 'secondary');
  refresh.props.onClick();
  assert.deepEqual(onRefresh.calls, [[]]);
});

test('dashboard: stat tiles render with their tones', async () => {
  const view = await screen();
  const tiles = view.components('StatTile');
  assert.equal(tiles.length, 4);
  STATS.forEach((s, i) => {
    assert.equal(tiles[i].props.label, s.label);
    assert.equal(tiles[i].props.tone, s.tone);
    assert.equal(tiles[i].props.value, s.value);
  });
});

test('dashboard: Segmented period control follows props and fires onPeriodChange', async () => {
  const onPeriodChange = spy();
  const view = await screen({ onPeriodChange });
  const seg = view.components('Segmented')[0];
  assert.deepEqual(seg.props.options, ['1H', '24H', '7D', '30D']);
  assert.equal(seg.props.value, '24H');
  seg.props.onChange('7D');
  assert.deepEqual(onPeriodChange.calls, [['7D']]);
});

test('dashboard: throughput LineChart gets series props', async () => {
  const view = await screen();
  const chart = view.components('LineChart')[0];
  assert.deepEqual(chart.props.data, THROUGHPUT.data);
  assert.equal(chart.props.max, THROUGHPUT.max);
  assert.equal(chart.props.label, THROUGHPUT.label);
});

test('dashboard: status mix DonutChart gets segments', async () => {
  const view = await screen();
  const donut = view.components('DonutChart')[0];
  assert.deepEqual(donut.props.segments, STATUS_MIX);
});

test('dashboard: Top Flows table slices to five rows with mono name and right-aligned numerics', async () => {
  const view = await screen();
  const table = view.components('DataTable')[0];
  assert.equal(table.props.rows.length, 5);
  assert.deepEqual(table.props.columns.map((c) => c.key), ['name', 'status', 'lastRun', 'messages']);
  const messages = table.props.columns.find((c) => c.key === 'messages');
  assert.equal(messages.align, 'right');
  assert.equal(messages.numeric, true);
  const name = table.props.columns.find((c) => c.key === 'name');
  assert.equal(name.mono, true);
});

test('dashboard: one status badge per sliced row', async () => {
  const view = await screen();
  assert.equal(view.components('StatusBadge').length, 5);
});

test('dashboard: quick actions render and fire onQuickAction with the action key', async () => {
  const onQuickAction = spy();
  const view = await screen({ onQuickAction });
  const actions = view.components('QuickAction');
  assert.equal(actions.length, 2);
  actions[0].props.onClick();
  assert.deepEqual(onQuickAction.calls, [['qa-retry']]);
  assert.equal(view.components('HealthRow').length, SYSTEMS.length);
});

test('dashboard: no top flows shows the empty state with a secondary New Flow CTA', async () => {
  const view = await screen({ topFlows: [] });
  const empty = byTest(view, 'empty');
  assert.equal(empty.length, 1);
  assert.ok(text(empty[0]).includes('No top flows yet.'));
  const cta = buttons(view).filter((b) => text(b.props) === 'New Flow' && b.props.variant === 'secondary');
  assert.equal(cta.length, 1);
});

test('dashboard: loading shows the skeleton and no data components', async () => {
  const view = await screen({ loading: true });
  assert.equal(byTest(view, 'skeleton').length, 1);
  assert.equal(view.components('StatTile').length, 0);
  assert.equal(view.components('LineChart').length, 0);
  assert.equal(view.components('DataTable').length, 0);
});

test('dashboard: error shows the error note and its Retry fires onReload', async () => {
  const onReload = spy();
  const view = await screen({ error: 'Network dropped', onReload });
  assert.equal(byTest(view, 'error').length, 1);
  assert.equal(view.components('Callout')[0].props.tone, 'err');
  const retry = buttons(view).find((b) => text(b.props) === 'Retry');
  retry.props.onClick();
  assert.deepEqual(onReload.calls, [[]]);
});

test('dashboard: stale flag renders the stale line in the error note', async () => {
  const view = await screen({ error: 'Network dropped', stale: true });
  assert.equal(byTest(view, 'stale').length, 1);
  assert.equal(view.components('StatTile').length, 0);
});