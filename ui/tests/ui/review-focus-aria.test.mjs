// Row 372 review M6 / M2 / M7 / L1 / L2: focus survives the confirm steps, busy buttons stay focusable
// (aria-disabled, never native disabled), the settings cell is a labelled group, Versions follows its route
// and keeps its pending state until every queued action ends. Hook harness: refs faked, handlers called.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/review-focus-aria.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from './hook-harness.mjs';

globalThis.window = { location: { hash: '' }, addEventListener() {}, removeEventListener() {} };
const el = () => ({ focused: 0, focus() { this.focused += 1; } });
const by = (screen, name, value) => findAll(screen.tree(), (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value));
const deferred = () => { let resolve; const promise = new Promise((r) => { resolve = r; }); return { promise, resolve }; };

// ---------- M6 ----------
test('M6 Contracts Approve: arming focuses "Yes, approve"; Cancel gives focus back to Approve', async () => {
  const contract = { contractId: 'c1', system: 'S1', role: 'TARGET_REQUEST', versions: [{ id: 'v1', versionLabel: '1.0', status: 'PENDING_APPROVAL' }] };
  const screen = await mount('pages/contracts/ContractList.tsx', 'ContractList', {
    contracts: [contract], refusal: null,
    actions: { compared: new Set(), onCompare() {}, onSubmit() {}, onApprove() {}, harnessApprover: true }
  }, {}, []);
  const approve = el();
  by(screen, 'data-action', 'approve-version')[0].props.ref.current = approve;
  by(screen, 'data-action', 'approve-version')[0].props.onClick();
  await screen.settle();
  assert.equal(by(screen, 'data-action', 'confirm-approve')[0].props.autoFocus, true, 'Yes, approve takes focus when it appears');
  by(screen, 'data-action', 'cancel-approve')[0].props.onClick();
  await screen.settle();
  assert.equal(approve.focused, 1, 'Cancel returns focus to Approve');
  assert.equal(by(screen, 'data-action', 'approve-as-approver').length, 1, 'harness approver button unchanged: one click, no confirm (contracts.docker.spec.mjs:204)');
});

test('M6 Replay: Cancel on the confirm step returns focus to the Replay button', async () => {
  const screen = await mount('pages/OperatorActionButton.tsx', 'OperatorActionButton',
    { action: { kind: 'replay', deliveryId: 'd1', label: 'Replay' }, confirm: true },
    { replayDelivery: async () => ({ ok: true }), retryDelivery: async () => ({ ok: true }) }, ['../api/operator']);
  const main = el();
  by(screen, 'data-action', 'replay')[0].props.ref.current = main;
  by(screen, 'data-action', 'replay')[0].props.onClick();
  await screen.settle();
  by(screen, 'data-confirm-cancel')[0].props.onClick();
  await screen.settle();
  assert.equal(main.focused, 1);
});

// ---------- Versions: M2 / L1 / L2 ----------
const FLOWS = [
  { id: 'A', code: 'A', name: 'Flow A', status: 'DRAFT' },
  { id: 'B', code: 'B', name: 'Flow B', status: 'DRAFT' }
];
const DETAIL = { sourceSystem: 's', destinations: [], businessKeyRule: null, ordering: null };
async function versions(selectId = null) {
  const reads = [];
  const api = {
    listFlows: async () => ({ ok: true, data: FLOWS }),
    loadHarnessConfig: async () => undefined,
    hasHarnessLogin: () => false,
    readFlow: () => { const d = deferred(); reads.push(d); return d.promise; },
    saveFlowDraft: async () => ({ ok: true }),
    submitFlow: async () => ({ ok: true }),
    approveFlow: async () => ({ ok: true }),
    publishFlow: async () => ({ ok: true })
  };
  const screen = await mount('pages/Versions.tsx', 'Versions', { selectId }, api, ['../api/flows', '../api/harness-config']);
  await screen.settle();
  return { screen, reads };
}
const action = (screen, name) => by(screen, 'data-action', name)[0];

test('M2 Versions lifecycle buttons use aria-disabled while an action runs (never native disabled) and ignore presses', async () => {
  const { screen, reads } = await versions();
  action(screen, 'save-draft').props.onClick();
  await screen.settle();
  for (const name of ['save-draft', 'submit-for-approval']) {
    assert.equal(action(screen, name).props['aria-disabled'], 'true', name);
    assert.equal(action(screen, name).props.disabled, undefined, `${name} stays focusable`);
  }
  action(screen, 'submit-for-approval').props.onClick();
  await screen.settle();
  assert.equal(reads.length, 1, 'the second press is ignored');
});

test('L2 pending stays set until every queued action has ended', async () => {
  const { screen, reads } = await versions();
  const first = action(screen, 'save-draft').props.onClick;
  const second = action(screen, 'submit-for-approval').props.onClick; // both closures see nothing pending
  first();
  second();
  await screen.settle();
  reads[0].resolve({ ok: true, data: DETAIL });
  await screen.settle();
  assert.equal(reads.length, 2, 'the second action started after the first');
  assert.equal(by(screen, 'aria-disabled', 'true').length > 0, true, 'still busy: one action left');
  reads[1].resolve({ ok: true, data: DETAIL });
  await screen.settle();
  assert.equal(by(screen, 'aria-disabled', 'true').length, 0, 'nothing is busy any more');
});

test('L1 navigating #/versions/A to #/versions/B on the mounted page re-selects B', async () => {
  const { screen } = await versions('A');
  const current = () => by(screen, 'data-flow-row').find((n) => n.props['aria-current'] === 'true')?.props['data-flow-row'];
  assert.equal(current(), 'A');
  screen.setProps({ selectId: 'B' });
  await screen.settle();
  assert.equal(current(), 'B');
});

// ---------- Systems: M7 / M2 ----------
const SYSTEM = { id: 's1', code: 'SYS1', name: 'One', direction: 'OUTBOUND', type: 'API', settings: { baseUrl: 'https://x' } };
async function systems() {
  const tests = [];
  const api = {
    listSystems: async () => ({ ok: true, data: [SYSTEM] }),
    readSystem: async () => ({ ok: true, data: SYSTEM }),
    testConnection: () => { const d = deferred(); tests.push(d); return d.promise; },
    TYPE_LABELS: {},
    readMyRoles: async () => null
  };
  const screen = await mount('pages/Systems.tsx', 'Systems', {}, api, ['../api/systems']);
  await screen.settle();
  return { screen, tests };
}

test('M7 the settings cell aria-label sits on a role=group (a role-less span ignores it)', async () => {
  const { screen } = await systems();
  const cell = findAll(screen.tree(), (n) => n.props?.className === 'systems-settings-text')[0];
  assert.ok(cell, 'the settings span renders');
  assert.equal(cell.props.role, 'group');
  assert.match(cell.props['aria-label'], /^Settings of SYS1:/);
  assert.equal(cell.props.tabIndex, 0, 'still focusable for scroll');
});

test('M2 Systems Test connection: aria-disabled while it runs, focus kept, a second press ignored', async () => {
  const { screen, tests } = await systems();
  by(screen, 'data-action', 'test-connection')[0].props.onClick();
  await screen.settle();
  const button = by(screen, 'data-action', 'test-connection')[0];
  assert.equal(button.props['aria-disabled'], 'true');
  assert.equal(button.props.disabled, undefined);
  button.props.onClick();
  await screen.settle();
  assert.equal(tests.length, 1);
});

test('M2 base.css styles aria-disabled buttons like disabled ones', async () => {
  const { readFileSync } = await import('node:fs');
  const css = readFileSync(new URL('../../src/ui/base.css', import.meta.url), 'utf8');
  assert.match(css, /\.btn\[aria-disabled="true"\]/);
  assert.match(css, /button\[aria-disabled="true"\]/);
});
