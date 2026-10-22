// Row 372 / B28 (lane B): the five operator screens' new pure view helpers and the render rules that
// the UI audit (2026-10-22, Lane B) found missing. No DOM, no docker - node:test + the hook harness.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/operator-screens.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from './hook-harness.mjs';

const byData = (name, value) => (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value);
const D1 = 'aa11bb22-cc33-4d44-8e55-ff6677889900';
const FLOW = '0b6c2d1e-7f8a-4b9c-8d0e-1f2a3b4c5d6e';

// B3 - delivery report: an empty day is a worded prompt, never the error state.
const { reportStatus, NO_DAY_COPY } = await import('../../src/pages/delivery-report-view.ts');
test('B3 an empty or malformed day is a prompt, whatever the last load said', () => {
  assert.equal(reportStatus('', 'error', 0), 'prompt');
  assert.equal(reportStatus('', 'ready', 3), 'prompt');
  assert.equal(reportStatus('2026-1-2', 'ready', 3), 'prompt');
  assert.match(NO_DAY_COPY, /day/i);
});
test('B3 a valid day maps the load to loading / refused / error / empty / ready', () => {
  assert.equal(reportStatus('2026-10-22', 'loading', 0), 'loading');
  assert.equal(reportStatus('2026-10-22', 'refused', 0), 'refused');
  assert.equal(reportStatus('2026-10-22', 'error', 0), 'error');
  assert.equal(reportStatus('2026-10-22', 'ready', 0), 'empty');
  assert.equal(reportStatus('2026-10-22', 'ready', 2), 'ready');
});

test('B3 clearing the date keeps the control, shows a worded prompt and does not enter the error state', async () => {
  const calls = [];
  const screen = await mount('pages/DeliveryReport.tsx', 'default', {}, {
    deliveryReport: async (q) => { calls.push(q); return { ok: true, data: [] }; }
  }, ['../api/reports']);
  await screen.settle();
  const [input] = findAll(screen.tree(), byData('data-day-filter'));
  input.props.onChange({ target: { value: '' } });
  await screen.settle();
  assert.equal(findAll(screen.tree(), byData('data-day-filter')).length, 1, 'the only filter stays');
  assert.equal(findAll(screen.tree(), byData('data-refusal')).length, 0, 'no error alert');
  assert.equal(findAll(screen.tree(), byData('data-day-prompt')).length, 1, 'a worded prompt');
  assert.equal(calls.length, 1, 'no request for an empty day');
});

test('B3 a rejected request leaves the error state (no unhandled rejection) and a stale answer is ignored', async () => {
  let first;
  let n = 0;
  const screen = await mount('pages/DeliveryReport.tsx', 'default', {}, {
    deliveryReport: () => (++n === 1 ? new Promise((r) => { first = r; }) : Promise.reject(new Error('net')))
  }, ['../api/reports']);
  await screen.settle();
  const [input] = findAll(screen.tree(), byData('data-day-filter'));
  input.props.onChange({ target: { value: '2026-10-01' } });
  await screen.settle();
  await screen.settle(); // the harness re-renders on settle: the second pass shows the answer that landed after the first
  assert.equal(findAll(screen.tree(), byData('data-refusal', 'delivery-report-error')).length, 1, 'catch -> error state');
  first({ ok: true, data: [{ interfaceId: 'i', targetId: 't', successes: 1, failuresTransient: 0, failuresAmbiguous: 0, failuresPermanent: 0, retriesMade: 0, retriedThenSucceeded: 0, stillStuck: 0 }] });
  await screen.settle();
  assert.equal(findAll(screen.tree(), byData('data-report-row')).length, 0, 'the stale first answer never lands');
});

// B2 - operator action: confirm step is opt-in, one press asks, the next sends.
const { pressStep, confirmLabel, confirmPrompt } = await import('../../src/pages/operator-actions.ts');
test('B2 pressStep: without confirm a press sends; with confirm the first asks and the second sends; busy/done ignore', () => {
  assert.equal(pressStep({ confirm: false, asking: false, busy: false, done: false }), 'send');
  assert.equal(pressStep({ confirm: true, asking: false, busy: false, done: false }), 'ask');
  assert.equal(pressStep({ confirm: true, asking: true, busy: false, done: false }), 'send');
  assert.equal(pressStep({ confirm: true, asking: true, busy: true, done: false }), 'ignore');
  assert.equal(pressStep({ confirm: false, asking: false, busy: false, done: true }), 'ignore');
});
test('B2 the confirm words name the action and its effect', () => {
  const replay = { kind: 'replay', deliveryId: D1, label: 'Replay' };
  assert.equal(confirmLabel(replay), 'Confirm replay');
  assert.match(confirmPrompt(replay), /send.*again|re-send/i);
});
test('B2 a replay button with confirm asks on the first press and posts on the second', async () => {
  let posts = 0;
  const screen = await mount('pages/OperatorActionButton.tsx', 'OperatorActionButton', {
    action: { kind: 'replay', deliveryId: D1, label: 'Replay' }, confirm: true
  }, { replayDelivery: async () => { posts++; return { ok: true, status: 200 }; }, retryDelivery: async () => assert.fail('no') }, ['../api/operator']);
  const button = () => findAll(screen.tree(), byData('data-delivery-id'))[0];
  button().props.onClick();
  await screen.settle();
  assert.equal(posts, 0, 'first press only asks');
  assert.equal(findAll(screen.tree(), byData('data-confirm-prompt')).length, 1);
  button().props.onClick();
  await screen.settle();
  assert.equal(posts, 1);
});

// B1/B7 - failures view helpers.
const fv = await import('../../src/pages/failures-view.ts');
const f = (id, stage = 'DELIVER') => ({ trackingId: id, deliveryId: id + '-d', flowId: FLOW, targetId: 't', failingStage: stage, errorClass: 'PERMANENT', lastError: 'x', origin: 'TARGET', failedAt: '2026-10-17T12:05:00Z' });
test('B2 keepActed: a refreshed list drops healed rows but keeps the acted row in place', () => {
  const prev = [f('a'), f('b'), f('c')];
  const fresh = [f('a'), f('c'), f('d')];
  const keys = [fv.failureKey(prev[1])];
  assert.deepEqual(fv.keepActed(prev, fresh, keys).map((x) => x.trackingId), ['a', 'b', 'c', 'd']);
  assert.deepEqual(fv.keepActed(prev, fresh, []).map((x) => x.trackingId), ['a', 'c', 'd']);
});
test('B7 relativeAge words the gap and never goes negative', () => {
  const now = new Date('2026-10-22T12:00:00Z');
  assert.equal(fv.relativeAge('2026-10-22T11:59:40Z', now), 'just now');
  assert.equal(fv.relativeAge('2026-10-22T11:55:00Z', now), '5 min ago');
  assert.equal(fv.relativeAge('2026-10-22T09:00:00Z', now), '3 h ago');
  assert.equal(fv.relativeAge('2026-10-19T12:00:00Z', now), '3 d ago');
  assert.equal(fv.relativeAge('2026-10-22T12:05:00Z', now), 'just now');
  assert.equal(fv.relativeAge('junk', now), '');
});
test('B7 origin tone: platform and target differ, unknown is neutral', () => {
  assert.equal(fv.originTone('PLATFORM'), 'warn');
  assert.equal(fv.originTone('TARGET'), 'info');
  assert.equal(fv.originTone('?'), 'neutral');
});
test('B4 the failures link carries flow and day and round-trips through the hash reader', () => {
  const hash = fv.failuresHash(FLOW, '2026-10-22');
  assert.equal(hash, `#/failures?flow=${FLOW}&date=2026-10-22`);
  assert.deepEqual(fv.failuresParamsFromHash(hash), { flowId: FLOW, date: '2026-10-22' });
  assert.deepEqual(fv.failuresParamsFromHash('#/failures'), { flowId: '', date: null });
  assert.deepEqual(fv.failuresParamsFromHash('#/failures?flow=junk&date=nope'), { flowId: '', date: null });
});

// B4 - queue status
const { queueStatus } = await import('../../src/pages/queue-view.ts');
test('B4 queueStatus: loading until answered; a refusal shows no rows; empty is its own state', () => {
  assert.equal(queueStatus({ loaded: false, refusal: null, rowCount: 0 }), 'loading');
  assert.equal(queueStatus({ loaded: true, refusal: 'no', rowCount: 4 }), 'refused');
  assert.equal(queueStatus({ loaded: true, refusal: null, rowCount: 0 }), 'empty');
  assert.equal(queueStatus({ loaded: true, refusal: null, rowCount: 2 }), 'ready');
});
test('B4 queue: a reload that is refused clears the stale rows and the failed count links to the failures screen', async () => {
  let n = 0;
  const row = { flowId: FLOW, flowCode: 'F1', day: '2026-10-22', queued: 1, processed: 2, converted: 3, failed: 4 };
  const screen = await mount('pages/QueueDashboard.tsx', 'QueueDashboard', {}, {
    queueSummary: async () => (++n === 1 ? { ok: true, data: [row] } : { ok: false, status: 403, refusal: 'denied' })
  }, ['../api/queue']);
  await screen.settle();
  const [link] = findAll(screen.tree(), byData('data-failed-link'));
  assert.equal(link.props.href, `#/failures?flow=${FLOW}&date=2026-10-22`);
  const [reload] = findAll(screen.tree(), byData('data-action', 'reload-queue'));
  reload.props.onClick();
  await screen.settle();
  assert.equal(findAll(screen.tree(), byData('data-queue-row')).length, 0, 'no stale rows beside a refusal');
  assert.equal(findAll(screen.tree(), (x) => x.props?.role === 'alert' && x.props['data-refusal'] === 'queue-summary-refused').length, 1);
});

// B6 - message state badge
const { stateBadge } = await import('../../src/pages/message-timeline.ts');
test('B6 stateBadge words the enum and tints by outcome', () => {
  assert.deepEqual(stateBadge('ROUTED'), { word: 'Routed', tone: 'success' });
  assert.deepEqual(stateBadge('ROUTE_FAILED'), { word: 'Route failed', tone: 'danger' });
  assert.deepEqual(stateBadge('DEAD_LETTERED'), { word: 'Dead lettered', tone: 'danger' });
  assert.deepEqual(stateBadge('ACCEPTED'), { word: 'Accepted', tone: 'info' });
  assert.deepEqual(stateBadge('WEIRD_THING'), { word: 'Weird thing', tone: 'neutral' });
});
