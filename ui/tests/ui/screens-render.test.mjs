// Row 327 T112 (lane D - vijay-developer): the message detail and failures SCREENS rendered under node
// (tests/ui/hook-harness.mjs: esbuild + a tiny hook runtime, API stubbed - no DOM, no docker).
//   V1 (row 328, WCAG 1.3.1): every group of the message summary <dl> has ONE form - a <div> holding
//      exactly one <dt> and one <dd> - never bare dt/dd pairs mixed with div-wrapped ones.
//   V3 (row 328 D1): when the filter changes, the old filter's rows leave the table the moment the new
//      fetch starts - 0 rows and data-load="loading" while the second answer is still held.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/screens-render.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { childElements, findAll, mount } from './hook-harness.mjs';

const API = ['../api/messages', '../api/flows'];
const FLOW = '0b6c2d1e-7f8a-4b9c-8d0e-1f2a3b4c5d6e';
const M1 = '3f2b8a52-6c1d-4e0f-9a7b-1c2d3e4f5a6b';
const D1 = 'aa11bb22-cc33-4d44-8e55-ff6677889900';
const D2 = 'bb22cc33-dd44-4e55-8f66-0077889900aa';

const byData = (name, value) => (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value);

function timeline(deliveries) {
  return {
    trackingId: M1,
    flowId: FLOW,
    state: 'ROUTED',
    acceptedAt: '2026-10-17T12:00:00Z',
    stages: [{ stage: 'INTAKE', startedAt: '2026-10-17T12:00:00Z', finishedAt: '2026-10-17T12:00:00.040Z', durationMs: 40, sincePreviousMs: null }],
    totalMs: 40,
    failingStage: null,
    deliveries
  };
}

async function summaryGroups(deliveries) {
  const screen = await mount('pages/MessageDetail.tsx', 'MessageDetail', { trackingId: M1, failedHint: null }, {
    messageTimeline: async () => ({ ok: true, data: timeline(deliveries) })
  }, API);
  await screen.settle();
  const [dl] = findAll(screen.tree(), byData('data-message-summary'));
  assert.ok(dl, 'the summary <dl> renders once the timeline answers');
  assert.equal(dl.type, 'dl');
  return childElements(dl);
}

for (const [name, deliveries] of [
  ['no delivery', []],
  ['one delivery', [{ deliveryId: D1, targetId: 't-1', durationMs: 50, outcome: 'SUCCESS', state: 'DELIVERED' }]],
  ['two deliveries', [
    { deliveryId: D1, targetId: 't-1', durationMs: 50, outcome: 'SUCCESS', state: 'DELIVERED' },
    { deliveryId: D2, targetId: 't-2', durationMs: null, outcome: null, state: 'PENDING' }
  ]]
]) {
  test(`V1 @a11y the summary <dl> groups are all one form - <div><dt/><dd/></div> (${name})`, async () => {
    const groups = await summaryGroups(deliveries);
    for (const group of groups) {
      assert.equal(group.type, 'div', `a direct <dl> child is a <${group.type}>, not a div-wrapped group`);
      assert.deepEqual(childElements(group).map((c) => c.type), ['dt', 'dd'], 'each group holds exactly one dt then one dd');
    }
    assert.equal(groups.length, 5 + Math.max(1, deliveries.length), 'tracking id, flow, state, accepted at, total, one per delivery ("Not reached" when none)');
  });
}

test('V1 the summary keeps every data hook the screen specs read (dd values, one group per delivery)', async () => {
  const groups = await summaryGroups([{ deliveryId: D1, targetId: 't-1', durationMs: 50, outcome: 'SUCCESS', state: 'DELIVERED' }]);
  const dds = findAll(groups, (n) => n.type === 'dd');
  assert.equal(dds.length, 6);
  for (const hook of ['data-tracking-id', 'data-flow-id', 'data-message-state']) {
    assert.equal(dds.filter((dd) => dd.props[hook] !== undefined).length, 1, hook);
  }
  assert.ok(dds.some((dd) => dd.props['data-total-ms'] === '40'));
  assert.ok(dds.some((dd) => dd.props['data-response-ms'] === '50'));
  assert.equal(findAll(groups, (n) => n.props?.['data-target-response'] === D1).length, 1);
});

test('V3 @negative a new filter clears the old rows while its answer is still held (0 rows, data-load="loading")', async () => {
  const calls = [];
  const failure = {
    trackingId: M1,
    deliveryId: D1,
    flowId: FLOW,
    targetId: 't-1',
    failingStage: 'DELIVER',
    errorClass: 'PERMANENT',
    lastError: 'HTTP 422',
    origin: 'TARGET',
    failedAt: '2026-10-17T12:05:00Z'
  };
  const screen = await mount('pages/Failures.tsx', 'Failures', {}, {
    listFlows: async () => ({ ok: true, data: [{ id: FLOW, code: 'F1', name: 'Orders' }] }),
    messageFailures: (query) => {
      calls.push(query);
      // the first answer arrives; the second is HELD (never settles during the test)
      return calls.length === 1 ? Promise.resolve({ ok: true, data: [failure] }) : new Promise(() => {});
    }
  }, API);
  await screen.settle();
  const page = () => findAll(screen.tree(), byData('data-page', 'failures'))[0];
  const rows = () => findAll(screen.tree(), byData('data-failure-row'));
  assert.equal(page().props['data-load'], 'ready');
  assert.equal(rows().length, 1, 'positive control: the first answer shows its row');

  const [select] = findAll(screen.tree(), byData('data-field', 'failures-flow'));
  select.props.onChange({ target: { value: FLOW } });
  await screen.settle();

  assert.equal(calls.length, 2, 'the new filter started a second fetch');
  assert.equal(page().props['data-load'], 'loading');
  assert.equal(rows().length, 0, "the old filter's row never stays under the new filter's caption and columns");
});

test('T165 M2 the detail screen offers Replay for a dead-lettered delivery and Retry now only while it waits to retry', async () => {
  const screen = await mount('pages/MessageDetail.tsx', 'MessageDetail', { trackingId: M1, failedHint: null }, {
    messageTimeline: async () => ({ ok: true, data: timeline([
      { deliveryId: D1, targetId: 't-1', durationMs: 50, outcome: 'TRANSIENT', state: 'DEAD_LETTERED' },
      { deliveryId: D2, targetId: 't-2', durationMs: 20, outcome: 'TRANSIENT', state: 'RETRY_WAIT' }
    ]) })
  }, [...API, '../api/operator']);
  await screen.settle();
  const actions = findAll(screen.tree(), byData('data-delivery-id')).map((b) => [b.props['data-delivery-id'], b.props['data-action']]);
  assert.deepEqual(actions, [[D1, 'replay'], [D2, 'retry']]);
});

test('T165 M4 @a11y a busy or finished action keeps keyboard focus: aria-disabled, never disabled, one POST only', async () => {
  let calls = 0;
  let release;
  const screen = await mount('pages/OperatorActionButton.tsx', 'OperatorActionButton', {
    action: { kind: 'replay', deliveryId: D1, label: 'Replay' }
  }, {
    replayDelivery: () => { calls++; return new Promise((r) => { release = r; }); },
    retryDelivery: async () => assert.fail('retry was not pressed')
  }, ['../api/operator']);
  const button = () => findAll(screen.tree(), byData('data-action'))[0];
  const press = async () => { button().props.onClick(); await screen.settle(); };
  assert.equal(button().props['aria-disabled'], undefined, 'ready: a plain button');

  await press();
  assert.equal(calls, 1);
  assert.equal(button().props.disabled, undefined, 'native disabled drops the focused button out of the tab order');
  assert.equal(button().props['aria-disabled'], 'true', 'in flight');
  await press();
  assert.equal(calls, 1, 'a press while in flight sends nothing');

  release({ ok: true, status: 200, data: { outcome: 'MOVED_TO_DUE', deliveryId: D1, state: 'DUE' } });
  await screen.settle();
  assert.equal(button().props.disabled, undefined);
  assert.equal(button().props['aria-disabled'], 'true', 'moved: it cannot be sent twice');
  await press();
  assert.equal(calls, 1, 'a press after the move sends nothing');
});
