import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from '../ui/hook-harness.mjs';

const by = (screen, name, value) =>
  findAll(screen.tree(), (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value));

const textOf = (node) =>
  Array.isArray(node) ? node.map(textOf).join('') : typeof node === 'object' && node ? textOf(node.props?.children) : String(node ?? '');

const ok = (items) => ({ items });

const ITEMS = [
  { time: '10:04', text: 'Flow ORD-7 delivered to SAP', tone: 'ok' },
  { time: '10:02', text: 'Flow ORD-6 retried by rule engine', tone: 'warn' },
  { time: '09:58', text: 'Flow ORD-5 failed: schema validation', tone: 'err' },
];

test('activity renders Timeline in caller order (newest-first pass-through)', async () => {
  const page = await mount('screens/activity/ActivityScreen.tsx', 'ActivityScreen', ok(ITEMS), {}, []);
  const tl = page.components('Timeline');
  assert.equal(tl.length, 1);
  assert.deepEqual(tl[0].items, ITEMS);
  const list = findAll(page.tree(), (n) => n.props?.className === 'ip-timeline' || String(n.type) === 'ol');
  const items = findAll(page.tree(), (n) => n.props?.className?.includes?.('ip-tl-item') || String(n.type) === 'li');
  assert.equal(items.length, 3);
  assert.match(textOf(list[0]), /10:04/);
  assert.match(textOf(items[0]), /ORD-7 delivered/);
  assert.match(textOf(items[2]), /schema validation/, 'last item in props order is last in DOM');
  page.unmount();
});

test('activity header buttons route clicks to passed handlers', async () => {
  let filtered = 0;
  let exported = 0;
  const page = await mount(
    'screens/activity/ActivityScreen.tsx',
    'ActivityScreen',
    { ...ok(ITEMS), onFilter: () => { filtered += 1; }, onExport: () => { exported += 1; } },
    {},
    [],
  );
  const filter = by(page, 'data-action', 'filter');
  const download = by(page, 'data-action', 'export');
  assert.equal(filter.length, 1);
  assert.equal(download.length, 1);
  filter[0].props.onClick();
  download[0].props.onClick();
  assert.equal(filtered, 1);
  assert.equal(exported, 1);
  page.unmount();
});

test('activity loading state shows skeleton region, not table content', async () => {
  const page = await mount('screens/activity/ActivityScreen.tsx', 'ActivityScreen', { loading: true }, {}, []);
  assert.equal(page.components('Timeline').length, 0);
  const skeletons = findAll(page.tree(), (n) => String(n.props?.className || '').includes('skeleton'));
  assert.ok(skeletons.length > 0, 'expected skeleton blocks');
  page.unmount();
});

test('activity empty state names what will appear', async () => {
  const page = await mount('screens/activity/ActivityScreen.tsx', 'ActivityScreen', ok([]), {}, []);
  assert.equal(page.components('Timeline').length, 0);
  const empty = by(page, 'data-empty', 'activity');
  assert.equal(empty.length, 1);
  assert.match(textOf(page.tree()), /No activity/);
  page.unmount();
});

test('activity error shows err callout with retry routed to handler', async () => {
  let retried = 0;
  const page = await mount(
    'screens/activity/ActivityScreen.tsx',
    'ActivityScreen',
    { error: 'refresh failed', onRetry: () => { retried += 1; } },
    {},
    [],
  );
  assert.equal(page.components('Timeline').length, 0);
  const btns = page.components('Button').filter((b) => b.children === 'Retry' || textOf(b.children) === 'Retry');
  assert.equal(btns.length, 1);
  btns[0].onClick?.();
  assert.equal(retried, 1);
  page.unmount();
});