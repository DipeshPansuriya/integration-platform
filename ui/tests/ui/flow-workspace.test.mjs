// Row 575 S4 (guided flow, spec 4.4): the flow workspace - a six-step setup stepper computed for THIS flow from the two reads it
// already needs (the flow and its routes), tabs as links, a "waiting for the first message" poll after publish, and the fixed
// Versions link. Honest wording: a step the data cannot show is "Not tracked here" / "Checked when you publish", never a tick.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/flow-workspace.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { findAll, mount } from './hook-harness.mjs';

const { flowStepper, flowTabs } = await import('../../src/pages/flow-stepper.ts');
const { createFirstMessagePoll, POLL_INTERVAL_MS, POLL_MAX_MS } = await import('../../src/pages/first-message-poll.ts');

const ID = '0b6c2d1e-7f8a-4b9c-8d0e-1f2a3b4c5d6e';
const flow = (status, over = {}) => ({ id: ID, code: 'F1', name: 'Nested appointment', ordering: 'PER_KEY', status, sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'k', ...over });
const route = (id, mappingId = null) => ({ id, mappingId, name: `to ${id}` });
const ADMIN = { canApprove: true, isTenantAdmin: true, canReadMessages: true };
const CONFIGURATOR = { canApprove: false, isTenantAdmin: false, canReadMessages: false };
const states = (s) => s.steps.map((x) => x.state);

// ---- the stepper -------------------------------------------------------------------------------------------------------
test('a saved flow with no routes: mapping is next, no action to invent', () => {
  const s = flowStepper({ flow: flow('DRAFT'), routes: [], roles: ADMIN });
  assert.deepEqual(states(s), ['done', 'info', 'next', 'info', 'todo', 'info']);
  assert.equal(s.steps.length, 6);
  assert.equal(s.next.key, 'mapping');
  assert.match(s.next.note, /No routes listed yet/);
});

test('routes without a mappingId: Mapping is next, with an Open mapping link to the first unmapped route', () => {
  const s = flowStepper({ flow: flow('DRAFT'), routes: [route('r1', 'm1'), route('r2')], roles: ADMIN });
  assert.equal(s.next.key, 'mapping');
  assert.equal(s.next.note, '1 of 2 routes have a mapping.');
  assert.deepEqual(s.next.action, { label: 'Open mapping', href: '#/mappings/r2' });
});

test('every route has a mapping: Mapping done, Approve is next (submit for a draft)', () => {
  const s = flowStepper({ flow: flow('DRAFT'), routes: [route('r1', 'm1')], roles: ADMIN });
  assert.deepEqual(states(s), ['done', 'info', 'done', 'info', 'next', 'info']);
  assert.match(s.next.note, /Submit the flow for approval/);
  assert.equal(s.next.action.href, `#/versions/${ID}`);
});

test('SUBMITTED: approvers and tenant admins get the link, a configurator only reads that it waits', () => {
  const routes = [route('r1', 'm1')];
  const admin = flowStepper({ flow: flow('SUBMITTED'), routes, roles: ADMIN });
  assert.match(admin.next.note, /waits for approval/i);
  assert.equal(admin.next.action.href, `#/versions/${ID}`);
  const cf = flowStepper({ flow: flow('SUBMITTED'), routes, roles: CONFIGURATOR });
  assert.match(cf.next.note, /waits for an approver/i);
  assert.equal(cf.next.action, undefined);
});

test('APPROVED: publish is next; PUBLISHED: approve is done and the checklist ends (go live is a pointer, never next)', () => {
  const routes = [route('r1', 'm1')];
  assert.match(flowStepper({ flow: flow('APPROVED'), routes, roles: ADMIN }).next.note, /publish it/i);
  const published = flowStepper({ flow: flow('PUBLISHED'), routes, roles: ADMIN });
  assert.deepEqual(states(published), ['done', 'info', 'done', 'info', 'done', 'info']);
  assert.equal(published.next, null);
});

test('honesty: contracts, test and go live never tick; go live names who can issue the key', () => {
  for (const status of ['DRAFT', 'SUBMITTED', 'APPROVED', 'PUBLISHED']) {
    const s = flowStepper({ flow: flow(status), routes: [route('r1', 'm1')], roles: ADMIN });
    for (const key of ['contracts', 'test', 'golive']) {
      const step = s.steps.find((x) => x.key === key);
      assert.equal(step.state, 'info', `${status} ${key}`);
      assert.notEqual(step.mark, '✓');
    }
    assert.equal(s.steps.find((x) => x.key === 'test').stateWord, 'Checked when you publish');
    assert.equal(s.steps.find((x) => x.key === 'golive').stateWord, 'Not tracked here');
    assert.doesNotMatch(s.steps.find((x) => x.key === 'test').note, /passed/i);
  }
  const forAdmin = flowStepper({ flow: flow('PUBLISHED'), routes: [], roles: ADMIN }).steps[5];
  assert.match(forAdmin.note, /Go live tab/);
  const forCf = flowStepper({ flow: flow('PUBLISHED'), routes: [], roles: CONFIGURATOR }).steps[5];
  assert.match(forCf.note, /Ask your tenant admin to issue the source key/);
  assert.equal(forCf.action, undefined);
});

test('a flow without a source or destinations: step 1 is next', () => {
  const s = flowStepper({ flow: flow('DRAFT', { sourceSystem: '', destinations: [] }), routes: [], roles: ADMIN });
  assert.equal(s.next.key, 'systems');
});

test('every step carries a mark and a word', () => {
  for (const step of flowStepper({ flow: flow('DRAFT'), routes: [], roles: ADMIN }).steps) assert.ok(step.mark && step.stateWord && step.label);
});

// ---- tabs --------------------------------------------------------------------------------------------------------------
test('tabs: Setup, Mapping, Rules, Versions as links; Messages only for roles that read messages', () => {
  const routes = [route('r1', 'm1')];
  const admin = flowTabs({ flowId: ID, routes, roles: ADMIN });
  assert.deepEqual(admin.map((t) => t.key), ['setup', 'mapping', 'rules', 'versions', 'messages']);
  assert.deepEqual(admin.map((t) => t.href), [`#/flow/${ID}`, '#/mappings/r1', `#/rules?flow=${ID}`, `#/versions/${ID}`, `#/failures?flow=${ID}`]);
  assert.equal(admin[0].current, true);
  assert.ok(admin.slice(1).every((t) => !t.current));
  assert.deepEqual(flowTabs({ flowId: ID, routes, roles: CONFIGURATOR }).map((t) => t.key), ['setup', 'mapping', 'rules', 'versions']);
  assert.equal(flowTabs({ flowId: ID, routes: [], roles: ADMIN })[1].href, '#/mappings', 'no routes: the mappings list');
});

// ---- the first-message poll --------------------------------------------------------------------------------------------
function clock() {
  let now = 0;
  const timers = [];
  return {
    now: () => now,
    setTimer: (fn, ms) => { const t = { fn, at: now + ms, id: timers.length + 1 }; timers.push(t); return t.id; },
    clearTimer: (id) => { const i = timers.findIndex((t) => t.id === id); if (i >= 0) timers.splice(i, 1); },
    async advance(ms) {
      const target = now + ms;
      for (;;) {
        timers.sort((a, b) => a.at - b.at);
        const t = timers[0];
        if (!t || t.at > target) break;
        timers.shift();
        now = t.at;
        await t.fn();
      }
      now = target;
    },
    pending: () => timers.length,
  };
}
function poll(results, over = {}) {
  const c = clock();
  const seen = [];
  let calls = 0;
  const p = createFirstMessagePoll({
    check: async () => { calls += 1; return results[Math.min(calls - 1, results.length - 1)]; },
    onState: (s) => seen.push(s),
    now: c.now, setTimer: c.setTimer, clearTimer: c.clearTimer, isHidden: () => false, ...over,
  });
  return { c, seen, p, calls: () => calls };
}

test('poll: checks every 15 s, flips to arrived and stops', async () => {
  assert.equal(POLL_INTERVAL_MS, 15000);
  const { c, seen, p, calls } = poll(['none', 'none', 'arrived']);
  p.start();
  await c.advance(15000 * 3);
  assert.equal(calls(), 3);
  assert.deepEqual(seen, ['waiting', 'arrived']);
  await c.advance(60000);
  assert.equal(calls(), 3, 'no more checks after arrival');
  assert.equal(c.pending(), 0);
});

test('poll: stops after 10 minutes with a worded state', async () => {
  assert.equal(POLL_MAX_MS, 600000);
  const { c, seen, p, calls } = poll(['none']);
  p.start();
  await c.advance(POLL_MAX_MS + 60000);
  assert.equal(seen.at(-1), 'stopped-time');
  assert.ok(calls() <= POLL_MAX_MS / POLL_INTERVAL_MS + 1);
  assert.equal(c.pending(), 0);
});

test('poll: three failures in a row stop it (no retry storm: the next tick only)', async () => {
  const { c, seen, p, calls } = poll(['error', 'error', 'error', 'none']);
  p.start();
  await c.advance(15000 * 10);
  assert.equal(calls(), 3);
  assert.equal(seen.at(-1), 'stopped-errors');
});

test('poll: a success resets the failure count', async () => {
  const { c, seen, p } = poll(['error', 'error', 'none', 'error', 'error', 'arrived']);
  p.start();
  await c.advance(15000 * 6);
  assert.equal(seen.at(-1), 'arrived');
});

test('poll: a hidden tab stops it; start() again resumes with a fresh window', async () => {
  let hidden = false;
  const { c, seen, p, calls } = poll(['none'], { isHidden: () => hidden });
  p.start();
  await c.advance(15000);
  hidden = true;
  await c.advance(15000);
  assert.equal(seen.at(-1), 'stopped-hidden');
  const before = calls();
  await c.advance(60000);
  assert.equal(calls(), before);
  hidden = false;
  p.start();
  assert.equal(seen.at(-1), 'waiting');
  await c.advance(15000);
  assert.ok(calls() > before);
});

test('poll: stop() cancels the timer', async () => {
  const { c, p, calls } = poll(['none']);
  p.start();
  p.stop();
  await c.advance(60000);
  assert.equal(calls(), 0);
  assert.equal(c.pending(), 0);
});

// ---- the screen --------------------------------------------------------------------------------------------------------
const SPECS = ['../api/flows', '../mapping/routesList', '../api/queue', '../api/client'];
const text = (n) => (Array.isArray(n) ? n.map(text).join('') : typeof n === 'string' ? n : typeof n === 'number' ? String(n) : n && typeof n === 'object' ? text(n.props?.children) : '');
const where = (name, value) => (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value);

async function workspace(status, roles = ADMIN, over = {}) {
  const calls = [];
  const screen = await mount('pages/FlowWorkspace.tsx', 'FlowWorkspace', { flowId: ID, roles }, {
    readFlow: async () => { calls.push('flow'); return over.readFlow ? over.readFlow() : { ok: true, data: flow(status) }; },
    loadRoutes: async () => { calls.push('routes'); return { state: 'ok', routes: [route('r1', 'm1')] }; },
    queueSummary: async () => { calls.push('queue'); return { ok: true, data: [] }; },
    api: {},
  }, SPECS);
  await screen.settle();
  await screen.settle();
  return { screen, calls };
}

test('screen: at most two reads render the stepper; the status is a worded badge; tabs and steps are present', async () => {
  const { screen, calls } = await workspace('DRAFT');
  assert.deepEqual(calls.sort(), ['flow', 'routes']);
  const root = screen.tree();
  assert.equal(findAll(root, where('data-flow-step')).length, 6);
  assert.match(text(findAll(root, where('data-flow-status'))[0]), /Draft/);
  assert.equal(findAll(root, where('data-flow-tab')).length, 5);
});

test('screen: the breadcrumb is Flows > name; the stepper is an ordered list with the next step marked', async () => {
  const { screen } = await workspace('SUBMITTED');
  const root = screen.tree();
  const [nav] = findAll(root, (n) => n.props?.['aria-label'] === 'Breadcrumb');
  assert.match(text(nav), /Flows/);
  assert.match(text(nav), /Nested appointment/);
  const [ol] = findAll(root, where('data-flow-stepper'));
  assert.equal(ol.type, 'ol');
  assert.equal(findAll(ol, (n) => n.props?.['aria-current'] === 'step').length, 1);
});

test('screen: every workspace link is a guarded leave (the unsaved-edit dialog asks before it)', async () => {
  const { screen } = await workspace('DRAFT');
  const links = findAll(screen.tree(), (n) => n.type === 'a' && typeof n.props.href === 'string');
  assert.ok(links.length >= 6);
  for (const a of links) assert.equal(a.props['data-leaves-editor'], 'true', a.props.href);
});

test('screen: a failed read is a worded line, not a blank page or an alert for a 403', async () => {
  const { screen } = await workspace('DRAFT', ADMIN, { readFlow: async () => ({ ok: false, status: 403 }) });
  assert.equal(findAll(screen.tree(), where('data-flow-step')).length, 0);
  assert.equal(findAll(screen.tree(), (n) => n.props?.role === 'alert').length, 0);
});

test('screen: a published flow shows the waiting panel only for roles that read messages', async () => {
  const withRole = await workspace('PUBLISHED', ADMIN);
  assert.equal(findAll(withRole.screen.tree(), where('data-first-message')).length, 1);
  assert.ok(withRole.calls.includes('queue'));
  const without = await workspace('PUBLISHED', CONFIGURATOR);
  assert.equal(findAll(without.screen.tree(), where('data-first-message')).length, 0);
  assert.ok(!without.calls.includes('queue'));
  const draft = await workspace('DRAFT', ADMIN);
  assert.equal(findAll(draft.screen.tree(), where('data-first-message')).length, 0);
});

// ---- wiring ------------------------------------------------------------------------------------------------------------
const read = (p) => readFileSync(fileURLToPath(new URL(`../../src/${p}`, import.meta.url)), 'utf8');

test('FlowDefinition: Draft-saved link goes to this flow\'s versions; the leave guard also catches workspace links', () => {
  const src = read('pages/FlowDefinition.tsx');
  assert.ok(!src.includes('<a href="#/versions">Submit it for approval</a>'), 'the bare #/versions link is fixed');
  assert.ok(src.includes('#/versions/${'));
  assert.ok(src.includes("hasAttribute('data-leaves-editor')"), 'links inside the page opt in to the guard');
  assert.ok(src.includes('<FlowWorkspace'), 'the workspace renders in the editor');
});

test('RulesEditor: #/rules?flow=<id> pre-selects that flow', () => {
  const src = read('rules/RulesEditor.tsx');
  assert.ok(src.includes("get('flow')"));
});

test('App.tsx hands the editor the role facts for the workspace', () => {
  const app = read('App.tsx');
  assert.ok(/<FlowDefinition[^>]*roles=\{roles\}/.test(app));
});
