// Row 372 review M9 / M8 / L3: a malformed address escape never blanks the shell, the SSO button recovers when
// the browser restores the page from its back/forward cache, and the Activity / Delivery report read failures use
// the shared plain words instead of "(HTTP n)".
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/review-routes-login.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { findAll, mount } from './hook-harness.mjs';

const { safeDecode } = await import('../../src/safe-decode.ts');
const by = (screen, name, value) => findAll(screen.tree(), (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value));
const textOf = (node) => (Array.isArray(node) ? node.map(textOf).join('') : typeof node === 'object' && node ? textOf(node.props?.children) : String(node ?? ''));

// ---------- M9 ----------
test('M9 safeDecode decodes a good escape and falls back to the raw value on a malformed one', () => {
  assert.equal(safeDecode('a%20b'), 'a b');
  assert.equal(safeDecode('%E0%A4%A'), '%E0%A4%A', 'a malformed escape no longer throws URIError');
  assert.equal(safeDecode('%'), '%');
  assert.equal(safeDecode('plain'), 'plain');
});

test('M9 App.parseHash decodes every route id through safeDecode', () => {
  const app = readFileSync(new URL('../../src/App.tsx', import.meta.url), 'utf8');
  assert.doesNotMatch(app, /decodeURIComponent\(/, 'no raw decodeURIComponent left in the router');
  assert.equal((app.match(/safeDecode\(/g) ?? []).length, 3, 'versions, rules and mappings ids');
});

// ---------- M8 ----------
test('M8 Login: a page restored from the back/forward cache resets the redirecting state', async () => {
  const listeners = {};
  const assigned = [];
  globalThis.window = {
    location: { assign: (u) => assigned.push(u), hash: '' },
    addEventListener: (type, fn) => { listeners[type] = fn; },
    removeEventListener: (type) => { delete listeners[type]; }
  };
  const screen = await mount('pages/Login.tsx', 'Login', { state: 'signed-out', onRetry() {} }, { LOGIN_ENTRY: '/login' }, ['../api/client']);
  const sso = () => by(screen, 'data-action', 'sso')[0];
  sso().props.onClick();
  await screen.settle();
  assert.equal(sso().props['aria-disabled'], true);
  assert.equal(assigned.length, 1);
  assert.equal(typeof listeners.pageshow, 'function', 'a pageshow listener is registered');

  listeners.pageshow({ persisted: false }); // an ordinary first load must not reset a live redirect
  await screen.settle();
  assert.equal(sso().props['aria-disabled'], true);

  listeners.pageshow({ persisted: true }); // Back from the identity provider
  await screen.settle();
  assert.equal(sso().props['aria-disabled'], false);
  assert.equal(textOf(sso()), 'Continue with SSO');
  screen.unmount();
  assert.equal(listeners.pageshow, undefined, 'the listener is removed on unmount');
});

// ---------- L3 ----------
test('L3 Delivery report: a server failure is worded, not "(HTTP 500)"', async () => {
  globalThis.window = { location: { hash: '' }, addEventListener() {}, removeEventListener() {} };
  const screen = await mount('pages/DeliveryReport.tsx', 'default', {}, { deliveryReport: async () => ({ ok: false, status: 500 }) }, ['../api/reports']);
  await screen.settle();
  const alert = by(screen, 'data-refusal', 'delivery-report-error')[0];
  assert.ok(alert, 'the failure renders');
  assert.doesNotMatch(textOf(alert), /HTTP|\b500\b/);
  assert.match(textOf(alert), /Try again/);
});

test('L3 Activity: a server failure is worded, not "(HTTP 500)"', async () => {
  globalThis.window = { location: { hash: '#/activity' }, addEventListener() {}, removeEventListener() {} };
  const screen = await mount('pages/Activity.tsx', 'Activity', {}, { listActivity: async () => ({ ok: false, status: 500 }), readActivity: async () => ({ ok: false, status: 500 }) }, ['../api/activity']);
  await screen.settle();
  const alert = by(screen, 'data-refusal', 'activity-failed')[0];
  assert.ok(alert, 'the failure renders');
  assert.doesNotMatch(textOf(alert), /HTTP|\b500\b/);
  assert.match(textOf(alert), /Try again/);
});
