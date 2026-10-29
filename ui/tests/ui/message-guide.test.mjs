// Row 575 S3 (guided flow, spec 4.5): one tracking view. Pure guide (stage strip + "what to do next" table), the detail screen's
// breadcrumb / strip / panel / copy button / flow link, and the Messages landing (tracking-id search). UX only; the server
// decides every action (Retry/Replay keep their 409/403 guards). No API change.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/message-guide.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { findAll, mount } from './hook-harness.mjs';

const { nextStepFor, stageStrip, canOperate } = await import('../../src/pages/message-guide.ts');

const FLOW = '0b6c2d1e-7f8a-4b9c-8d0e-1f2a3b4c5d6e';
const M1 = '3f2b8a52-6c1d-4e0f-9a7b-1c2d3e4f5a6b';
const stage = (name, ms = 10, since = null) => ({ stage: name, startedAt: '2026-10-28T11:02:01Z', finishedAt: '2026-10-28T11:02:01.010Z', durationMs: ms, sincePreviousMs: since });
const delivery = (state, outcome = null) => ({ deliveryId: 'd1', targetId: 'T1', durationMs: 5, outcome, state });
const tl = (over = {}) => ({ trackingId: M1, flowId: FLOW, state: 'ROUTED', acceptedAt: '2026-10-28T11:02:01Z', stages: [stage('INTAKE')], totalMs: 40, failingStage: null, deliveries: [], ...over });
const ALLOW = { canOpenFlow: true, canOperate: true };

// ---- nextStepFor: one table, every row of spec 4.5 -------------------------------------------------------------------
test('route failed: the route words, with Open the flow only when allowed', () => {
  const t = tl({ state: 'ROUTE_FAILED', failingStage: 'ROUTE' });
  const guide = nextStepFor(t, ALLOW);
  assert.match(guide.words, /No destination matched this message/);
  assert.deepEqual(guide.links, [{ label: 'Open the flow', href: `#/flow/${FLOW}` }]);
  assert.deepEqual(nextStepFor(t, { ...ALLOW, canOpenFlow: false }).links, []);
});

test('transform failure: the mapping words', () => {
  const guide = nextStepFor(tl({ failingStage: 'TRANSFORM' }), ALLOW);
  assert.match(guide.words, /mapping could not convert/i);
});

test('delivery retrying: the platform tries again by itself; Retry now is named for operators only', () => {
  const t = tl({ deliveries: [delivery('RETRY_WAIT')] });
  assert.match(nextStepFor(t, ALLOW).words, /waiting to be sent again/);
  assert.match(nextStepFor(t, ALLOW).words, /retries by itself/);
  // RETRY_WAIT also covers waits with no attempt (bulkhead, circuit open, stale claim): never a claim about the target.
  assert.doesNotMatch(nextStepFor(t, ALLOW).words, /target did not answer|target is down|target was down/i);
  assert.match(nextStepFor(t, ALLOW).words, /Retry now/);
  assert.doesNotMatch(nextStepFor(t, { ...ALLOW, canOperate: false }).words, /Retry now/);
  assert.match(nextStepFor(t, { ...ALLOW, canOperate: false }).words, /tenant admin or support/i);
});

test('dead-lettered: permanent, transient, ambiguous and unknown outcomes each get their words', () => {
  const words = (outcome) => nextStepFor(tl({ failingStage: 'DELIVER', deliveries: [delivery('DEAD_LETTERED', outcome)] }), ALLOW).words;
  assert.match(words('PERMANENT'), /target refused the message/i);
  assert.match(words('TRANSIENT'), /temporary reason and the retries ran out/i);
  assert.doesNotMatch(words('TRANSIENT'), /target was down/i);
  assert.match(words('AMBIGUOUS'), /may have received it/i);
  assert.match(words('AMBIGUOUS'), /duplicate/i);
  assert.match(words(null), /gave up/i);
  for (const o of ['PERMANENT', 'TRANSIENT', 'AMBIGUOUS']) assert.match(words(o), /Replay/);
  const readOnly = nextStepFor(tl({ failingStage: 'DELIVER', deliveries: [delivery('DEAD_LETTERED', 'PERMANENT')] }), { ...ALLOW, canOperate: false });
  assert.doesNotMatch(readOnly.words.split('Replay needs')[0], /then Replay/);
  assert.match(readOnly.words, /tenant admin or support/i);
});

test('no failure: delivered says nothing to do; still moving says check again; neither claims more than the data shows', () => {
  assert.match(nextStepFor(tl({ state: 'ROUTED', deliveries: [delivery('DELIVERED', 'SUCCESS')] }), ALLOW).words, /delivered/i);
  assert.match(nextStepFor(tl({ state: 'ACCEPTED' }), ALLOW).words, /still being processed/i);
  assert.match(nextStepFor(tl({ deliveries: [delivery('SENDING')] }), ALLOW).words, /still being processed/i);
});

test('no raw enum or HTTP code appears in any guide words', () => {
  const cases = [
    tl({ state: 'ROUTE_FAILED', failingStage: 'ROUTE' }), tl({ failingStage: 'TRANSFORM' }),
    tl({ deliveries: [delivery('RETRY_WAIT')] }), tl({ failingStage: 'DELIVER', deliveries: [delivery('DEAD_LETTERED', 'PERMANENT')] }),
    tl({ state: 'ACCEPTED' }), tl({ deliveries: [delivery('DELIVERED')] }),
  ];
  for (const c of cases) assert.doesNotMatch(nextStepFor(c, ALLOW).words, /DEAD_LETTERED|RETRY_WAIT|ROUTE_FAILED|PERMANENT|TRANSIENT|HTTP|\b[45]\d\d\b/);
});

test('canOperate: tenant admin and support (REPLAY_DEAD_LETTER); roles not loaded: yes', () => {
  assert.equal(canOperate(['TENANT_ADMIN']), true);
  assert.equal(canOperate(['SUPPORT']), true);
  for (const r of [['READ_ONLY'], ['CONFIGURATOR'], ['APPROVER'], []]) assert.equal(canOperate(r), false);
  assert.equal(canOperate(null), true);
});

// ---- stageStrip --------------------------------------------------------------------------------------------------------
test('stage strip: Received -> Routed -> Mapped -> Delivered in pipeline order, a word and a mark per step', () => {
  const strip = stageStrip(tl({ stages: [stage('INTAKE')] }));
  assert.deepEqual(strip.items.map((i) => i.label), ['Received', 'Routed', 'Mapped', 'Delivered']);
  assert.deepEqual(strip.items.map((i) => i.stage), ['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER']);
  assert.equal(strip.items[0].word, 'Done');
  assert.equal(strip.items[0].mark, '✓');
  assert.equal(strip.items[1].word, 'Not recorded');
  for (const i of strip.items) assert.ok(i.word.length > 0 && i.mark.length > 0);
  assert.equal(strip.current, 0, 'the last recorded step is the current one');
});

test('stage strip: the failing stage is marked failed and current; later steps read Not reached, never 0 ms', () => {
  const strip = stageStrip(tl({ state: 'ROUTE_FAILED', failingStage: 'ROUTE', stages: [stage('INTAKE'), stage('ROUTE')] }));
  assert.equal(strip.items[1].word, 'Failed');
  assert.equal(strip.items[1].mark, '✗');
  assert.equal(strip.current, 1);
  assert.equal(strip.items[2].word, 'Not reached');
  assert.equal(strip.items[3].word, 'Not reached');
  assert.ok(!strip.items.some((i) => /\b0 ms\b/.test(i.detail)));
});

test('stage strip: nothing recorded -> no current step', () => {
  assert.equal(stageStrip(tl({ stages: [] })).current, null);
});

// ---- the detail screen -------------------------------------------------------------------------------------------------
const API = ['../api/messages', '../api/flows'];
const text = (n) => (Array.isArray(n) ? n.map(text).join('') : typeof n === 'string' ? n : typeof n === 'number' ? String(n) : n && typeof n === 'object' ? text(n.props?.children) : '');
const where = (name, value) => (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value);

async function detail(timeline, props = {}, flows = [{ id: FLOW, code: 'F1', name: 'Nested appointment', ordering: 'PER_KEY', status: 'PUBLISHED' }]) {
  const screen = await mount('pages/MessageDetail.tsx', 'MessageDetail', { trackingId: M1, failedHint: null, ...props }, {
    messageTimeline: async () => ({ ok: true, data: timeline }),
    listFlows: async () => ({ ok: true, data: flows }),
  }, API);
  await screen.settle();
  await screen.settle();
  return screen;
}

test('detail: a breadcrumb Messages > id replaces "Back to failures"', async () => {
  const screen = await detail(tl());
  const [nav] = findAll(screen.tree(), (n) => n.props?.['aria-label'] === 'Breadcrumb');
  assert.ok(nav, 'breadcrumb nav');
  const links = findAll(nav, (n) => typeof n.props?.href === 'string').map((n) => n.props.href);
  assert.deepEqual(links, ['#/messages']);
  assert.match(text(nav), /Messages/);
  assert.equal(findAll(screen.tree(), where('data-action', 'back-to-failures')).length, 0);
  assert.equal(findAll(screen.tree(), (n) => n.type === 'h1').length, 1);
});

test('detail: the stage strip is an ordered list with aria-current="step" on the failing stage', async () => {
  const screen = await detail(tl({ state: 'ROUTE_FAILED', failingStage: 'ROUTE', stages: [stage('INTAKE'), stage('ROUTE')] }));
  const [ol] = findAll(screen.tree(), where('data-stage-strip'));
  assert.equal(ol.type, 'ol');
  const items = findAll(ol, (n) => n.type === 'li');
  assert.equal(items.length, 4);
  const current = items.filter((n) => n.props['aria-current'] === 'step');
  assert.equal(current.length, 1);
  assert.match(text(current[0]), /Routed/);
  assert.match(text(current[0]), /Failed/);
});

test('detail: the "What to do next" panel carries the words and the flow link, with no live role', async () => {
  const screen = await detail(tl({ state: 'ROUTE_FAILED', failingStage: 'ROUTE', stages: [stage('INTAKE'), stage('ROUTE')] }), { canReadConfiguration: true, canOperate: true });
  const [panel] = findAll(screen.tree(), where('data-next-step'));
  assert.equal(panel.props.role, undefined, 'no live role on the panel: it would re-announce on every silent refresh');
  assert.match(text(panel), /What to do next/);
  assert.match(text(panel), /No destination matched/);
  assert.ok(findAll(panel, (n) => n.props?.href === `#/flow/${FLOW}`).length === 1);
});

test('detail: the flow is shown by name and linked only with READ_CONFIGURATION (fixes D7)', async () => {
  const withRole = await detail(tl(), { canReadConfiguration: true });
  const [dd] = findAll(withRole.tree(), where('data-flow-id'));
  assert.match(text(dd), /Nested appointment/);
  assert.equal(findAll(dd, (n) => n.props?.href === `#/flow/${FLOW}`).length, 1);

  let flowCalls = 0;
  const support = await mount('pages/MessageDetail.tsx', 'MessageDetail', { trackingId: M1, failedHint: null, canReadConfiguration: false }, {
    messageTimeline: async () => ({ ok: true, data: tl() }),
    listFlows: async () => { flowCalls += 1; return { ok: true, data: [] }; },
  }, API);
  await support.settle();
  await support.settle();
  const [sdd] = findAll(support.tree(), where('data-flow-id'));
  assert.equal(findAll(sdd, (n) => typeof n.props?.href === 'string').length, 0, 'no link for a role that cannot read configuration');
  assert.equal(flowCalls, 0, 'and no configuration call');
});

test('detail: a flow lookup that fails keeps the id as text, no error banner', async () => {
  const screen = await mount('pages/MessageDetail.tsx', 'MessageDetail', { trackingId: M1, failedHint: null, canReadConfiguration: true }, {
    messageTimeline: async () => ({ ok: true, data: tl() }),
    listFlows: async () => ({ ok: false, status: 403, error: 'no' }),
  }, API);
  await screen.settle();
  await screen.settle();
  const [dd] = findAll(screen.tree(), where('data-flow-id'));
  assert.match(text(dd), new RegExp(FLOW));
  assert.equal(findAll(screen.tree(), (n) => n.props?.role === 'alert').length, 0);
});

test('detail: a Copy id button copies the tracking id and announces it', async () => {
  let copied = null;
  const original = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  Object.defineProperty(globalThis, 'navigator', { value: { clipboard: { writeText: async (v) => { copied = v; } } }, configurable: true });
  try {
    const screen = await detail(tl());
    const [btn] = findAll(screen.tree(), where('data-action', 'copy-id'));
    assert.ok(btn);
    await btn.props.onClick();
    await screen.settle();
    assert.equal(copied, M1);
    const [live] = findAll(screen.tree(), where('data-copy-status'));
    assert.equal(live.props.role, 'status');
    assert.match(text(live), /Copied/);
  } finally {
    if (original) Object.defineProperty(globalThis, 'navigator', original);
    else delete globalThis.navigator;
  }
});

test('detail: the existing Retry/Replay buttons and summary hooks stay', async () => {
  const screen = await detail(tl({ failingStage: 'DELIVER', deliveries: [delivery('DEAD_LETTERED', 'PERMANENT')] }));
  assert.equal(findAll(screen.tree(), where('data-delivery-action', 'd1')).length, 1);
  for (const hook of ['data-tracking-id', 'data-flow-id', 'data-message-state', 'data-timeline']) assert.ok(findAll(screen.tree(), where(hook)).length >= 1, hook);
});

// ---- the Messages landing ----------------------------------------------------------------------------------------------
test('landing: a labelled tracking-id search; a bad id is worded and does not navigate; a good id opens the message', async () => {
  globalThis.window = { location: { hash: '#/messages' } };
  try {
    const screen = await mount('pages/GuidedLandings.tsx', 'MessagesLanding', {}, {}, []);
    await screen.settle();
    const input = () => findAll(screen.tree(), (n) => n.props?.id === 'messages-tracking')[0];
    const form = () => findAll(screen.tree(), (n) => typeof n.props?.onSubmit === 'function')[0];
    const hint = () => findAll(screen.tree(), (n) => n.props?.id === 'messages-tracking-hint')[0];
    assert.ok(findAll(screen.tree(), (n) => n.props?.htmlFor === 'messages-tracking').length === 1);
    assert.equal(text(hint()), '');
    input().props.onChange({ target: { value: 'abc' } });
    await screen.settle();
    form().props.onSubmit({ preventDefault() {} });
    await screen.settle();
    assert.equal(text(hint()), 'Enter a tracking id (36 characters)');
    assert.equal(globalThis.window.location.hash, '#/messages');
    input().props.onChange({ target: { value: ` ${M1} ` } });
    await screen.settle();
    form().props.onSubmit({ preventDefault() {} });
    await screen.settle();
    assert.equal(globalThis.window.location.hash, `#/messages/${M1}`);
  } finally {
    delete globalThis.window;
  }
});

test('landing: tabs are links - Results (current), Delivered (#/delivered) and Needs attention (#/failures); Results says plainly what it cannot do yet', async () => {
  const screen = await mount('pages/GuidedLandings.tsx', 'MessagesLanding', {}, {}, []);
  await screen.settle();
  const [tabs] = findAll(screen.tree(), (n) => n.props?.['data-message-tabs'] !== undefined);
  const links = findAll(tabs, (n) => n.type === 'a');
  assert.deepEqual(links.map((a) => a.props.href), ['#/messages', '#/delivered', '#/failures']);
  assert.equal(links[0].props['aria-current'], 'page');
  assert.equal(links[1].props['aria-current'], undefined);
  assert.equal(links[2].props['aria-current'], undefined);
  assert.equal(findAll(screen.tree(), (n) => n.props?.['data-nav'] !== undefined).length, 0, 'no second data-nav="failures" next to the menu entry');
  assert.match(text(screen.tree()), /tracking id/i);
  assert.doesNotMatch(text(screen.tree()), /business key.*(search|find)\b.*\bnow\b/i);
});

test('App.tsx hands the detail screen the role facts', () => {
  const app = readFileSync(fileURLToPath(new URL('../../src/App.tsx', import.meta.url)), 'utf8');
  assert.ok(/<MessageDetail[^>]*canReadConfiguration=\{canOpen\(roles, 'flows'\)\}/.test(app));
  assert.ok(/<MessageDetail[^>]*canOperate=\{canOperate\(roles\)\}/.test(app));
});

test('detail: Retry/Replay buttons render only for roles that can operate (tenant admin, support); others read the words', async () => {
  const dead = tl({ failingStage: 'DELIVER', deliveries: [delivery('DEAD_LETTERED', 'PERMANENT')] });
  const readOnly = await detail(dead, { canOperate: false });
  assert.equal(findAll(readOnly.tree(), where('data-delivery-action')).length, 0);
  assert.equal(findAll(readOnly.tree(), where('data-action', 'replay')).length, 0);
  assert.match(text(findAll(readOnly.tree(), where('data-next-step'))[0]), /Only a tenant admin or support user can replay it/);
  const operator = await detail(dead, { canOperate: true });
  assert.equal(findAll(operator.tree(), where('data-delivery-action', 'd1')).length, 1);
  const retrying = await detail(tl({ deliveries: [delivery('RETRY_WAIT')] }), { canOperate: false });
  assert.equal(findAll(retrying.tree(), where('data-delivery-action')).length, 0);
});

test('stage strip: a step that was not seen shows only its word, no repeated detail line', async () => {
  const strip = stageStrip(tl({ state: 'ROUTE_FAILED', failingStage: 'ROUTE', stages: [stage('INTAKE'), stage('ROUTE')] }));
  assert.equal(strip.items[2].word, 'Not reached');
  assert.equal(strip.items[2].detail, '');
  assert.equal(strip.items[3].detail, '');
  assert.ok(strip.items[0].detail.length > 0, 'a seen step keeps its duration');
  const screen = await detail(tl({ state: 'ROUTE_FAILED', failingStage: 'ROUTE', stages: [stage('INTAKE'), stage('ROUTE')] }));
  const items = findAll(findAll(screen.tree(), where('data-stage-strip'))[0], (n) => n.type === 'li');
  assert.equal(findAll(items[2], (n) => typeof n.props?.className === 'string' && n.props.className.includes('stage-step-detail')).length, 0);
  assert.equal(findAll(items[0], (n) => typeof n.props?.className === 'string' && n.props.className.includes('stage-step-detail')).length, 1);
});
