// Row 575 S1, Karan's two Lows. Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/row575-security-lows.test.mjs
// (1) a malformed escape in #/messages/<id> must not throw while the router reads the address;
// (2) Home makes no configuration calls and shows no 403 banner for a role without READ_CONFIGURATION (UX only; the server stays the gate).
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { findAll, mount } from './hook-harness.mjs';

const { parseMessageRoute } = await import('../../src/pages/message-timeline.ts');

test('parseMessageRoute: a malformed escape keeps the raw text instead of throwing', () => {
  assert.doesNotThrow(() => parseMessageRoute('%E0%A4%A'));
  assert.equal(parseMessageRoute('%E0%A4%A').trackingId, '%E0%A4%A');
  assert.deepEqual(parseMessageRoute('abc%20def?failed=ROUTE'), { trackingId: 'abc def', failedHint: 'ROUTE' });
});

const SPECS = ['../api/flows', '../api/systems', '../api/contracts'];
function counting() {
  const calls = [];
  const refuse = (name) => async () => { calls.push(name); return { ok: false, status: 403, refusal: 'ROLE_NOT_GRANTED', error: 'forbidden' }; };
  return { calls, api: { listFlows: refuse('flows'), listSystems: refuse('systems'), listContracts: refuse('contracts') } };
}
const alerts = (screen) => findAll(screen.tree(), (n) => n.props?.role === 'alert');

test('Home: roles known and without READ_CONFIGURATION -> no flows/systems/contracts calls and no banner', async () => {
  const { calls, api } = counting();
  const screen = await mount('pages/Dashboard.tsx', 'Dashboard', { readsConfiguration: false }, api, SPECS);
  await screen.settle();
  assert.deepEqual(calls, []);
  assert.equal(alerts(screen).length, 0);
});

test('Home: a role that reads configuration still loads the three lists (a 403 answer is omitted without a banner - row 575 S2)', async () => {
  const { calls, api } = counting();
  const screen = await mount('pages/Dashboard.tsx', 'Dashboard', {}, api, SPECS);
  await screen.settle();
  assert.deepEqual([...calls].sort(), ['contracts', 'flows', 'systems']);
  assert.equal(alerts(screen).length, 0);
});

test('App.tsx hands the Dashboard readsConfiguration from the role table', () => {
  const app = readFileSync(fileURLToPath(new URL('../../src/App.tsx', import.meta.url)), 'utf8');
  assert.ok(/<Dashboard[^>]*readsConfiguration=\{canOpen\(roles, 'flows'\)\}/.test(app));
});
