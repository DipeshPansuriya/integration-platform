// Row 562: a 403 on the queue / failures / activity / delivery-report views shows ONE clear, accessible
// "You do not have access to this page" state (role=alert), never a raw refusal code, and throws nothing
// and logs no console error from the page's own code. Role scoping stays the backend's; this is UX only.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/no-access-403.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from './hook-harness.mjs';

const byData = (name, value) => (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value);
const text = (node) => (Array.isArray(node) ? node.map(text).join('') : typeof node === 'string' ? node : node && typeof node === 'object' ? text(node.props?.children) : '');
const { NO_ACCESS_COPY } = await import('../../src/ui/no-access.ts');
const REFUSED = { ok: false, status: 403, refusal: 'ROLE_NOT_GRANTED', error: 'forbidden' };

/** Mounts under a console.error / console.warn spy; returns the screen and the logged calls. */
async function mountQuiet(page, exportName, api, specifiers) {
  const logged = [];
  const { error, warn } = console;
  console.error = (...a) => logged.push(a);
  console.warn = (...a) => logged.push(a);
  try {
    const screen = await mount(page, exportName, {}, api, specifiers);
    await screen.settle();
    await screen.settle();
    return { screen, logged };
  } finally {
    console.error = error;
    console.warn = warn;
  }
}

function assertNoAccess(screen, logged, refusalName) {
  const alerts = findAll(screen.tree(), byData('data-no-access'));
  assert.equal(alerts.length, 1, 'exactly one no-access state');
  assert.equal(alerts[0].props.role, 'alert', 'announced to assistive tech');
  assert.match(text(alerts[0]), /You do not have access to this page/);
  assert.equal(text(alerts[0]), NO_ACCESS_COPY);
  assert.doesNotMatch(text(alerts[0]), /ROLE_NOT_GRANTED|forbidden|403|HTTP/i, 'no raw code or status');
  if (refusalName) assert.equal(alerts[0].props['data-refusal'], refusalName, 'the existing data-refusal hook is kept');
  assert.deepEqual(logged, [], 'no console error or warning');
}

test('NO_ACCESS_COPY is the plain sentence', () => {
  assert.match(NO_ACCESS_COPY, /^You do not have access to this page\./);
});

test('queue: 403 shows the no-access alert and no rows', async () => {
  const { screen, logged } = await mountQuiet('pages/QueueDashboard.tsx', 'QueueDashboard', { queueSummary: async () => REFUSED }, ['../api/queue']);
  assertNoAccess(screen, logged, 'queue-summary-refused');
  assert.equal(findAll(screen.tree(), byData('data-queue-row')).length, 0);
});

test('queue: a non-403 refusal keeps the backend wording, not the no-access state', async () => {
  const { screen } = await mountQuiet('pages/QueueDashboard.tsx', 'QueueDashboard', { queueSummary: async () => ({ ok: false, status: 400, error: 'bad date' }) }, ['../api/queue']);
  assert.equal(findAll(screen.tree(), byData('data-no-access')).length, 0);
  assert.equal(findAll(screen.tree(), byData('data-refusal', 'queue-summary-refused')).length, 1);
});

test('failures: 403 on the list shows the no-access alert, no table rows', async () => {
  const { screen, logged } = await mountQuiet('pages/Failures.tsx', 'Failures', {
    listFlows: async () => REFUSED,
    messageFailures: async () => REFUSED
  }, ['../api/flows', '../api/messages']);
  assertNoAccess(screen, logged, 'failures-refused');
  assert.equal(findAll(screen.tree(), byData('data-failure-row')).length, 0);
});

test('failures: a rejected flows read does not throw an unhandled rejection', async () => {
  const unhandled = [];
  const onUnhandled = (e) => unhandled.push(e);
  process.on('unhandledRejection', onUnhandled);
  try {
    await mountQuiet('pages/Failures.tsx', 'Failures', {
      listFlows: () => Promise.reject(new Error('net')),
      messageFailures: async () => ({ ok: true, data: [] })
    }, ['../api/flows', '../api/messages']);
    await new Promise((r) => setImmediate(r));
  } finally {
    process.off('unhandledRejection', onUnhandled);
  }
  assert.deepEqual(unhandled, []);
});

test('activity list: 403 shows the no-access alert', async () => {
  globalThis.window = { location: { hash: '#/activity' }, addEventListener() {}, removeEventListener() {} };
  const { screen, logged } = await mountQuiet('pages/Activity.tsx', 'Activity', { listActivity: async () => REFUSED, readActivity: async () => REFUSED }, ['../api/activity']);
  assertNoAccess(screen, logged, 'activity-no-access');
  delete globalThis.window;
});

test('activity event: 403 on one event shows the same no-access alert', async () => {
  globalThis.window = { location: { hash: '#/activity/ev-1' }, addEventListener() {}, removeEventListener() {} };
  const { screen, logged } = await mountQuiet('pages/Activity.tsx', 'Activity', { listActivity: async () => REFUSED, readActivity: async () => REFUSED }, ['../api/activity']);
  assertNoAccess(screen, logged, 'activity-no-access');
  delete globalThis.window;
});

test('delivery report: 403 shows the no-access alert, not the message-timeline wording', async () => {
  const { screen, logged } = await mountQuiet('pages/DeliveryReport.tsx', 'default', { deliveryReport: async () => REFUSED }, ['../api/reports']);
  assertNoAccess(screen, logged, 'delivery-report-refused');
  assert.equal(findAll(screen.tree(), byData('data-report-row')).length, 0);
});

test('delivery report: 401 keeps the sign-in wording; 500 stays the error state', async () => {
  const a = await mountQuiet('pages/DeliveryReport.tsx', 'default', { deliveryReport: async () => ({ ok: false, status: 401 }) }, ['../api/reports']);
  assert.equal(findAll(a.screen.tree(), byData('data-no-access')).length, 0);
  assert.match(text(findAll(a.screen.tree(), byData('data-refusal'))[0]), /session has ended/);
  const b = await mountQuiet('pages/DeliveryReport.tsx', 'default', { deliveryReport: async () => ({ ok: false, status: 500 }) }, ['../api/reports']);
  assert.equal(findAll(b.screen.tree(), byData('data-refusal', 'delivery-report-error')).length, 1);
});
