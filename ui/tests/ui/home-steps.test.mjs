// Row 575 S2 (guided flow): Home "Next steps" - the setup checklist, flows in progress, approvals waiting and the tracking-id
// search are computed from the list answers Home already fetches. No new API. UX only; the server stays the gate.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/home-steps.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { findAll, mount } from './hook-harness.mjs';

const { todayUtc } = await import('../../src/pages/failures-view.ts');
const { setupSteps, nextStep, flowsInProgress, approvalsWaiting, homeBlocks, trackingHash, TRACKING_ID_HINT } = await import('../../src/pages/home-steps.ts');

const sys = (direction) => ({ direction });
const contract = (...statuses) => ({ versions: statuses.map((status) => ({ status })) });
const flow = (status, id = status) => ({ id, code: id, name: `Flow ${id}`, status });
const states = (steps) => steps.map((s) => s.state);

test('CF/TA with nothing yet: step 1 is next, with the one action [Register a system]', () => {
  const steps = setupSteps({ systems: [], contracts: [], flows: [] });
  assert.equal(steps.length, 6);
  assert.deepEqual(states(steps), ['next', 'todo', 'todo', 'info', 'todo', 'info']);
  assert.equal(nextStep(steps).n, 1);
  assert.equal(steps[0].action.label, 'Register a system');
  assert.equal(steps[0].action.href, '#/systems');
  assert.match(steps[0].note, /No systems yet\. Register the source system first\./);
});

test('only a source registered -> asks for a target; only a target -> asks for the source; both -> done with the count', () => {
  assert.match(setupSteps({ systems: [sys('INBOUND')], contracts: [], flows: [] })[0].note, /target/i);
  assert.match(setupSteps({ systems: [sys('OUTBOUND')], contracts: [], flows: [] })[0].note, /source/i);
  const both = setupSteps({ systems: [sys('INBOUND'), sys('OUTBOUND')], contracts: [], flows: [] });
  assert.equal(both[0].state, 'done');
  assert.equal(both[0].note, '2 registered');
  assert.equal(nextStep(both).n, 2);
});

test('contracts: none -> Import a contract; drafts only -> waits for approval; one approved -> done', () => {
  const base = { systems: [sys('INBOUND'), sys('OUTBOUND')], flows: [] };
  assert.equal(setupSteps({ ...base, contracts: [] })[1].action.label, 'Import a contract');
  const drafts = setupSteps({ ...base, contracts: [contract('DRAFT'), contract('DRAFT', 'RETIRED')] })[1];
  assert.equal(drafts.state, 'next');
  assert.equal(drafts.note, '2 drafts - submit for approval', 'a draft waits for submit, not for approval');
  assert.doesNotMatch(drafts.note, /wait/);
  assert.equal(setupSteps({ ...base, contracts: [contract('DRAFT'), contract('APPROVED')] })[1].state, 'done');
  assert.equal(setupSteps({ ...base, contracts: [contract('PENDING_APPROVAL')] })[1].note, '1 contract waits for approval');
});

test('flow steps come from flow statuses only: none -> Define a flow; DRAFT -> open it; SUBMITTED -> waits; PUBLISHED -> checklist ends', () => {
  const ready = { systems: [sys('INBOUND'), sys('OUTBOUND')], contracts: [contract('APPROVED')] };
  assert.equal(setupSteps({ ...ready, flows: [] })[2].action.label, 'Define a flow');
  const draft = setupSteps({ ...ready, flows: [flow('DRAFT')] });
  assert.deepEqual(states(draft), ['done', 'done', 'done', 'info', 'next', 'info']);
  assert.equal(draft[4].action.label, 'Open the flow');
  assert.match(draft[4].note, /sample/i);
  const submitted = setupSteps({ ...ready, flows: [flow('SUBMITTED')] });
  assert.deepEqual(states(submitted), ['done', 'done', 'done', 'info', 'next', 'info']);
  assert.match(submitted[4].note, /waits for approval/i);
  const published = setupSteps({ ...ready, flows: [flow('PUBLISHED')] });
  assert.deepEqual(states(published), ['done', 'done', 'done', 'info', 'done', 'info']);
  assert.equal(nextStep(published), null, 'the checklist can finish at step 5');
});

test('honesty: step 3 only says a flow exists; step 4 never ticks or claims a passed sample; step 6 is never next or done', () => {
  const ready = { systems: [sys('INBOUND'), sys('OUTBOUND')], contracts: [contract('APPROVED')] };
  for (const status of ['DRAFT', 'SUBMITTED', 'APPROVED', 'PUBLISHED']) {
    const steps = setupSteps({ ...ready, flows: [flow(status)] });
    assert.equal(steps[2].note, '1 flow exists', 'no claim about the mapping');
    assert.equal(steps[2].label, 'Flow');
    assert.equal(steps[3].state, 'info');
    assert.equal(steps[3].stateWord, 'Checked when you publish');
    assert.doesNotMatch(steps[3].note, /passed/i);
    assert.notEqual(steps[3].mark, '✓');
    assert.equal(steps[5].state, 'info');
    assert.equal(steps[5].stateWord, 'Not tracked here');
    assert.match(steps[5].note, /Go live tab/);
  }
  assert.equal(setupSteps({ ...ready, flows: [flow('DRAFT'), flow('DRAFT', 'x')] })[2].note, '2 flows exist');
});

test('every step carries a word and a mark, never colour alone', () => {
  const steps = setupSteps({ systems: [], contracts: [], flows: [] });
  for (const step of steps) assert.ok(step.label.length > 0 && step.mark.length > 0 && step.stateWord.length > 0);
  assert.deepEqual(steps.map((x) => x.stateWord), ['Next', 'To do', 'To do', 'Checked when you publish', 'To do', 'Not tracked here']);
});

test('flows in progress: the next word per status, published ones are left out, link opens the flow', () => {
  const rows = flowsInProgress([flow('DRAFT', 'a'), flow('SUBMITTED', 'b'), flow('APPROVED', 'c'), flow('PUBLISHED', 'd')]);
  assert.deepEqual(rows.map((r) => [r.id, r.next]), [
    ['a', 'Run the sample and submit'], ['b', 'Waits for approval'], ['c', 'Publish'],
  ]);
  assert.equal(rows[0].href, '#/flow/a');
});

test('approvals waiting = submitted flow versions + contract versions that wait for approval', () => {
  assert.deepEqual(approvalsWaiting([flow('SUBMITTED'), flow('SUBMITTED', 'x'), flow('DRAFT')], [contract('PENDING_APPROVAL'), contract('APPROVED')]), { flows: 2, contracts: 1, total: 3 });
  assert.equal(approvalsWaiting([], []).total, 0);
});

test('homeBlocks per role (spec 4.3): Set up TA CF; Approvals TA AP; Messages TA SU RO; roles not loaded: all', () => {
  assert.deepEqual(homeBlocks(['TENANT_ADMIN']), { setup: true, approvals: true, messages: true });
  assert.deepEqual(homeBlocks(['CONFIGURATOR']), { setup: true, approvals: false, messages: false });
  assert.deepEqual(homeBlocks(['APPROVER']), { setup: false, approvals: true, messages: false });
  assert.deepEqual(homeBlocks(['SUPPORT']), { setup: false, approvals: false, messages: true });
  assert.deepEqual(homeBlocks(['READ_ONLY']), { setup: false, approvals: false, messages: true });
  assert.deepEqual(homeBlocks([]), { setup: false, approvals: false, messages: false });
  assert.deepEqual(homeBlocks(null), { setup: true, approvals: true, messages: true });
});

test('trackingHash: a UUID opens the message; anything else is worded, nothing navigates', () => {
  const id = '3f2b8c1e-5d4a-4e6b-9a7c-0d1e2f3a4b5c';
  assert.equal(trackingHash(` ${id} `), `#/messages/${id}`);
  for (const bad of ['', 'abc', id.slice(1), `${id}x`]) assert.equal(trackingHash(bad), null);
  assert.equal(TRACKING_ID_HINT, 'Enter a tracking id (36 characters)');
});

// ---- the Home screen: calls per role and 403 handling -------------------------------------------------------------------
const SPECS = ['../api/flows', '../api/systems', '../api/contracts', '../api/messages'];
const ok = (data) => async () => ({ ok: true, data });
const refused = async () => ({ ok: false, status: 403, refusal: 'ROLE_NOT_GRANTED', error: 'forbidden' });
function stubs(over = {}) {
  const calls = [];
  const wrap = (name, fn) => async (...a) => { calls.push(name); return fn(...a); };
  return {
    calls,
    api: {
      listFlows: wrap('flows', over.flows ?? ok([flow('SUBMITTED', 'f1')])),
      listSystems: wrap('systems', over.systems ?? ok([sys('INBOUND'), sys('OUTBOUND')])),
      listContracts: wrap('contracts', over.contracts ?? ok([contract('PENDING_APPROVAL')])),
      messageFailures: wrap('failures', over.failures ?? ok([{ trackingId: 'a' }, { trackingId: 'b' }])),
    },
  };
}
const text = (n) => (Array.isArray(n) ? n.map(text).join('') : typeof n === 'string' ? n : typeof n === 'number' ? String(n) : n && typeof n === 'object' ? text(n.props?.children) : '');
const block = (screen, name) => findAll(screen.tree(), (n) => n.props?.['data-home-block'] === name)[0];
const alerts = (screen) => findAll(screen.tree(), (n) => n.props?.role === 'alert');
async function home(roles, over) {
  const s = stubs(over);
  const readsConfiguration = roles === null || roles.some((r) => r !== 'SUPPORT');
  const screen = await mount('pages/Dashboard.tsx', 'Dashboard', { roles, readsConfiguration }, s.api, SPECS);
  await screen.settle();
  return { ...s, screen };
}

test('API calls on Home per role: TA 4, CF 3, AP 3, SU 1 (failures only), RO 4', async () => {
  const count = async (roles) => (await home(roles)).calls.length;
  assert.equal(await count(['TENANT_ADMIN']), 4);
  assert.equal(await count(['CONFIGURATOR']), 3);
  assert.equal(await count(['APPROVER']), 3);
  assert.equal(await count(['SUPPORT']), 1);
  assert.equal(await count(['READ_ONLY']), 4);
});

test('SU home makes 0 calls to flows|systems|contracts and shows no alert', async () => {
  const { calls, screen } = await home(['SUPPORT']);
  assert.deepEqual(calls, ['failures']);
  assert.equal(alerts(screen).length, 0);
  assert.ok(block(screen, 'messages'));
  assert.equal(block(screen, 'setup'), undefined);
  assert.equal(block(screen, 'approvals'), undefined);
});

test('TA sees all three blocks with real counts; every count links to a list', async () => {
  const { screen } = await home(['TENANT_ADMIN']);
  const setup = block(screen, 'setup'), approvals = block(screen, 'approvals'), messages = block(screen, 'messages');
  assert.ok(setup && approvals && messages);
  assert.match(text(approvals), /Waiting for your approval \(2\)/);
  assert.match(text(messages), /2 failed/);
  const hrefs = (b) => findAll(b, (n) => typeof n.props?.href === 'string').map((n) => n.props.href);
  assert.ok(hrefs(approvals).includes('#/versions'));
  assert.ok(!hrefs(approvals).some((h) => h.includes('status=')), 'no ignored ?status= query');
  assert.ok(hrefs(messages).includes(`#/failures?date=${todayUtc(new Date())}`), 'the Failures screen default day (UTC)');
});

test('CF sees only Set up; AP only Approvals', async () => {
  const cf = (await home(['CONFIGURATOR'])).screen;
  assert.ok(block(cf, 'setup'));
  assert.equal(block(cf, 'approvals'), undefined);
  assert.equal(block(cf, 'messages'), undefined);
  const ap = (await home(['APPROVER'])).screen;
  assert.ok(block(ap, 'approvals'));
  assert.equal(block(ap, 'setup'), undefined);
});

test('a block whose API answers 403 is omitted, not shown as an error', async () => {
  const { screen } = await home(['TENANT_ADMIN'], { failures: refused });
  assert.equal(block(screen, 'messages'), undefined);
  assert.equal(alerts(screen).length, 0);
});

test('Set up shows the one next step with its action', async () => {
  const { screen } = await home(['CONFIGURATOR'], { systems: ok([]), contracts: ok([]), flows: ok([]) });
  const setup = block(screen, 'setup');
  assert.match(text(setup), /Register a system/);
  assert.ok(findAll(setup, (n) => n.props?.href === '#/systems').length >= 1);
});

test('App.tsx hands Home the roles', () => {
  const app = readFileSync(fileURLToPath(new URL('../../src/App.tsx', import.meta.url)), 'utf8');
  assert.ok(/<Dashboard[^>]*roles=\{roles\}/.test(app));
});

test('the failures day is the UTC day, exactly like the Failures screen; the heading says so', async () => {
  let asked = null;
  const s2 = stubs({ failures: async (params) => { asked = params; return { ok: true, data: [] }; } });
  const screen = await mount('pages/Dashboard.tsx', 'Dashboard', { roles: ['SUPPORT'], readsConfiguration: false }, s2.api, SPECS);
  await screen.settle();
  assert.deepEqual(asked, { date: todayUtc(new Date()) });
  assert.match(text(block(screen, 'messages')), /Needs attention today \(UTC\)/);
  assert.equal(todayUtc(new Date('2026-10-28T23:30:00-05:30')), '2026-10-29', 'a user behind/ahead of UTC still gets the UTC day');
  const src = readFileSync(fileURLToPath(new URL('../../src/pages/Dashboard.tsx', import.meta.url)), 'utf8');
  assert.ok(!src.includes('dayOf') && src.includes('todayUtc('));
});

test('failures answer an error (not 403): the block stays with a worded line, no banner', async () => {
  const { screen } = await home(['SUPPORT'], { failures: async () => ({ ok: false, status: 500, error: 'boom' }) });
  assert.match(text(block(screen, 'messages')), /Failed messages could not be loaded./);
  assert.equal(alerts(screen).length, 0);
});

test('a 403 on any configuration list hides its blocks and raises no banner', async () => {
  for (const which of ['flows', 'systems', 'contracts']) {
    const { screen } = await home(['CONFIGURATOR'], { [which]: refused });
    assert.equal(block(screen, 'setup'), undefined, which);
    assert.equal(alerts(screen).length, 0, which);
  }
  const ap = await home(['APPROVER'], { contracts: refused });
  assert.equal(block(ap.screen, 'approvals'), undefined);
  assert.equal(alerts(ap.screen).length, 0);
});

test('a real list failure (500) still raises the banner', async () => {
  const { screen } = await home(['CONFIGURATOR'], { systems: async () => ({ ok: false, status: 500, error: 'boom' }) });
  assert.equal(alerts(screen).length, 1);
});

test('the tracking hint: live region always present, empty until a bad id is submitted; a good id navigates', async () => {
  globalThis.window = { location: { hash: '' } };
  try {
    const { screen } = await home(['SUPPORT']);
    const hint = () => findAll(block(screen, 'messages'), (n) => n.props?.id === 'home-tracking-hint')[0];
    const form = () => findAll(block(screen, 'messages'), (n) => typeof n.props?.onSubmit === 'function')[0];
    const input = () => findAll(block(screen, 'messages'), (n) => n.props?.id === 'home-tracking')[0];
    assert.equal(hint().props.role, 'status');
    assert.equal(text(hint()), '');
    input().props.onChange({ target: { value: 'abc' } });
    await screen.settle();
    form().props.onSubmit({ preventDefault() {} });
    await screen.settle();
    assert.equal(text(hint()), TRACKING_ID_HINT);
    assert.equal(globalThis.window.location.hash, '');
    const id = '3f2b8c1e-5d4a-4e6b-9a7c-0d1e2f3a4b5c';
    input().props.onChange({ target: { value: id } });
    await screen.settle();
    form().props.onSubmit({ preventDefault() {} });
    await screen.settle();
    assert.equal(globalThis.window.location.hash, '#/messages/' + id);
    assert.equal(text(hint()), '');
  } finally {
    delete globalThis.window;
  }
});

test('step 6 is a pointer, not a call to action: no primary button, and a published tenant has no "next" step on Home', async () => {
  const published = { flows: ok([flow('PUBLISHED', 'p1')]), contracts: ok([contract('APPROVED')]) };
  const { screen } = await home(['TENANT_ADMIN'], published);
  const setup = block(screen, 'setup');
  const golive = findAll(setup, (n) => n.props?.['data-step'] === 'golive')[0];
  assert.match(text(golive), /Not tracked here/);
  assert.equal(findAll(golive, (n) => n.props?.className === 'btn btn-primary').length, 0);
  assert.equal(findAll(setup, (n) => n.props?.['aria-current'] === 'step').length, 0);
});
