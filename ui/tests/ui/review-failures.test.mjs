// Row 372 review M5 / L4 on the Failures screen (hook harness, API stubbed, no DOM).
//   M5: a slow silent refresh (after Replay) must not overwrite the list of a newer filter / Reload.
//   L4: Copy announces itself in a live region, says so visibly when the clipboard is unavailable, and
//       clears its 1.5 s timer when the screen goes away.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/review-failures.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from './hook-harness.mjs';

const API = ['../api/messages', '../api/flows'];
const FLOW_A = '0b6c2d1e-7f8a-4b9c-8d0e-1f2a3b4c5d6e';
const FLOW_B = '1c7d3e2f-8a9b-4c0d-9e1f-2a3b4c5d6e7f';
const failure = (id) => ({
  trackingId: id, deliveryId: `d-${id}`, flowId: FLOW_A, targetId: 't', failingStage: 'DELIVER', errorClass: 'PERMANENT',
  lastError: 'boom', origin: 'TARGET', failedAt: '2026-10-17T12:05:00Z'
});
const deferred = () => {
  let resolve;
  const promise = new Promise((r) => { resolve = r; });
  return { promise, resolve };
};
const rowsOf = (screen) => findAll(screen.tree(), (n) => n.props?.['data-failure-row'] !== undefined).map((n) => n.props['data-failure-row']);

async function failuresScreen(answers) {
  const calls = [];
  const screen = await mount('pages/Failures.tsx', 'Failures', {}, {
    listFlows: async () => ({ ok: true, data: [{ id: FLOW_A, code: 'A', name: 'A' }, { id: FLOW_B, code: 'B', name: 'B' }] }),
    messageFailures: (query) => {
      calls.push(query);
      return answers[calls.length - 1].promise;
    }
  }, API);
  return { screen, calls };
}

test('M5 a slow silent refresh never overwrites the list of a newer filter', async () => {
  const answers = [deferred(), deferred(), deferred()];
  const { screen, calls } = await failuresScreen(answers);
  answers[0].resolve({ ok: true, data: [failure('A1')] });
  await screen.settle();
  assert.deepEqual(rowsOf(screen), ['A1']);

  screen.components('OperatorActionButton')[0].onDone({ kind: 'replay', deliveryId: 'd-A1', label: 'Replay' }); // accepted replay: silent refresh starts (held)
  await screen.settle();
  assert.equal(calls.length, 2, 'the silent re-read was sent');

  const select = findAll(screen.tree(), (n) => n.props?.['data-field'] === 'failures-flow')[0];
  select.props.onChange({ target: { value: FLOW_B } }); // the person changes the filter: a newer load starts
  await screen.settle();
  assert.equal(calls.length, 3);
  answers[2].resolve({ ok: true, data: [failure('B1')] });
  await screen.settle();
  assert.deepEqual(rowsOf(screen), ['B1']);

  answers[1].resolve({ ok: true, data: [failure('A1'), failure('A2')] }); // the stale refresh answers last
  await screen.settle();
  assert.deepEqual(rowsOf(screen), ['B1'], 'the old filter\'s refresh is dropped');
});

test('M5 a silent refresh that answers in time still updates the list (positive control)', async () => {
  const answers = [deferred(), deferred()];
  const { screen } = await failuresScreen(answers);
  answers[0].resolve({ ok: true, data: [failure('A1')] });
  await screen.settle();
  screen.components('OperatorActionButton')[0].onDone({ kind: 'replay', deliveryId: 'd-A1', label: 'Replay' });
  await screen.settle();
  answers[1].resolve({ ok: true, data: [failure('A1'), failure('A2')] });
  await screen.settle();
  assert.deepEqual(rowsOf(screen), ['A1', 'A2']);
});

function withNavigator(clipboard, fn) {
  const had = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  Object.defineProperty(globalThis, 'navigator', { value: { clipboard }, configurable: true });
  return fn().finally(() => {
    if (had) Object.defineProperty(globalThis, 'navigator', had);
    else delete globalThis.navigator;
  });
}
const copyButton = (screen) => findAll(screen.tree(), (n) => n.props?.['data-copy-id'] !== undefined)[0];
const copyStatus = (screen) => findAll(screen.tree(), (n) => n.props?.['data-copy-status'] !== undefined)[0];
const loaded = async () => {
  const answers = [deferred()];
  const { screen } = await failuresScreen(answers);
  answers[0].resolve({ ok: true, data: [failure('A1')] });
  await screen.settle();
  return screen;
};

test('L4 Copy is announced from a polite live region', async () => {
  await withNavigator({ writeText: async () => undefined }, async () => {
    const screen = await loaded();
    assert.equal(copyStatus(screen).props.role, 'status');
    assert.equal(copyStatus(screen).props.children, '', 'quiet until something is copied');
    copyButton(screen).props.onClick();
    await screen.settle();
    assert.match(String(copyStatus(screen).props.children), /Copied tracking id A1/);
    screen.unmount();
  });
});

test('L4 no clipboard: a visible message says so instead of silence', async () => {
  await withNavigator(undefined, async () => {
    const screen = await loaded();
    copyButton(screen).props.onClick();
    await screen.settle();
    const status = copyStatus(screen);
    assert.match(String(status.props.children), /could not copy|not available/i);
    assert.doesNotMatch(String(status.props.className), /sr-only/, 'visible, not screen-reader-only');
  });
});

test('L4 the 1.5 s reset timer is cleared when the screen unmounts', async () => {
  await withNavigator({ writeText: async () => undefined }, async () => {
    const realSet = globalThis.setTimeout;
    const realClear = globalThis.clearTimeout;
    const cleared = [];
    let issued = null;
    globalThis.setTimeout = (fn, ms) => {
      if (ms === 1500) { issued = { id: 'copy-timer' }; return issued; }
      return realSet(fn, ms);
    };
    globalThis.clearTimeout = (id) => { cleared.push(id); return realClear(id); };
    try {
      const screen = await loaded();
      copyButton(screen).props.onClick();
      await screen.settle();
      assert.ok(issued, 'the reset timer was started');
      screen.unmount();
      assert.ok(cleared.includes(issued), 'unmount cleared it');
    } finally {
      globalThis.setTimeout = realSet;
      globalThis.clearTimeout = realClear;
    }
  });
});
