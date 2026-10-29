// Row 575 S5 (guided flow, plan S5): the Approvals list - flow versions that are SUBMITTED and contracts with a version that waits for
// approval, from the lists Home already reads (listFlows, listContracts). Open to Approver and Tenant admin only; approving itself
// is unchanged (the rows link to the existing screens). Honest: a contract row says it has a version waiting, and links to the
// Contracts screen, which does not take a filter.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/approvals-view.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { findAll, mount } from './hook-harness.mjs';

const { approvalRows } = await import('../../src/pages/approvals-view.ts');
const { approvalsWaiting } = await import('../../src/pages/home-steps.ts');
const { canOpen, navFor } = await import('../../src/nav-model.ts');

const flow = (status, id, name = `Flow ${id}`) => ({ id, code: `C-${id}`, name, ordering: 'PER_KEY', status });
const contract = (id, role, ...statuses) => ({ id, code: `K-${id}`, name: `Contract ${id}`, role, system: 'ERP', versions: statuses.map((status, i) => ({ id: `${id}v${i}`, status, versionLabel: `v${i + 1}` })) });

test('rows: submitted flows and contracts with a version waiting for approval, nothing else', () => {
  const rows = approvalRows(
    [flow('SUBMITTED', 'a'), flow('DRAFT', 'b'), flow('APPROVED', 'c'), flow('PUBLISHED', 'd'), flow('SUBMITTED', 'e', '')],
    [contract('k1', 'TARGET_REQUEST', 'DRAFT', 'PENDING_APPROVAL'), contract('k2', 'SOURCE_REQUEST', 'DRAFT'), contract('k3', 'SOURCE_REQUEST', 'APPROVED')]
  );
  assert.deepEqual(rows.map((r) => [r.kind, r.key]), [['flow', 'a'], ['flow', 'e'], ['contract', 'k1']]);
  assert.equal(rows[0].name, 'Flow a');
  assert.equal(rows[1].name, 'C-e', 'the code stands in for a missing name');
});

test('rows link to the existing approve screens and say what waits, in words', () => {
  const [f, c] = approvalRows([flow('SUBMITTED', 'a')], [contract('k1', 'TARGET_REQUEST', 'PENDING_APPROVAL')]);
  assert.equal(f.href, '#/versions/a');
  assert.equal(f.what, 'Flow version');
  assert.equal(f.statusWord, 'Submitted');
  assert.equal(c.href, '#/contracts');
  assert.equal(c.what, 'Contract');
  assert.equal(c.statusWord, 'Waits for approval');
  assert.match(c.detail, /ERP/);
  assert.match(c.detail, /Target request/i, 'the role in words, not the enum');
  assert.match(c.detail, /v1/);
  for (const r of [f, c]) assert.doesNotMatch(`${r.name} ${r.detail} ${r.statusWord}`, /SUBMITTED|PENDING_APPROVAL|TARGET_REQUEST/);
});

test('the list count equals the Home block count (one source of truth)', () => {
  const flows = [flow('SUBMITTED', 'a'), flow('SUBMITTED', 'b'), flow('DRAFT', 'c')];
  const contracts = [contract('k1', 'TARGET_REQUEST', 'PENDING_APPROVAL', 'PENDING_APPROVAL'), contract('k2', 'SOURCE_REQUEST', 'DRAFT')];
  assert.equal(approvalRows(flows, contracts).length, approvalsWaiting(flows, contracts).total);
  assert.equal(approvalRows([], []).length, 0);
});

test('a contract with two versions waiting is ONE row that counts them', () => {
  const [c] = approvalRows([], [contract('k1', 'TARGET_REQUEST', 'PENDING_APPROVAL', 'PENDING_APPROVAL')]);
  assert.match(c.detail, /2 versions/);
});

test('only an approver or a tenant admin sees and opens Approvals; configurator and read-only get NoAccess', () => {
  for (const role of ['APPROVER', 'TENANT_ADMIN']) {
    assert.equal(canOpen([role], 'approvals'), true, role);
    assert.ok(navFor([role]).some((i) => i.nav === 'approvals'), role);
  }
  for (const role of ['CONFIGURATOR', 'READ_ONLY', 'SUPPORT']) {
    assert.equal(canOpen([role], 'approvals'), false, role);
    assert.ok(!navFor([role]).some((i) => i.nav === 'approvals'), role);
  }
});

// ---- the screen --------------------------------------------------------------------------------------------------------
const SPECS = ['../api/flows', '../api/contracts'];
const text = (n) => (Array.isArray(n) ? n.map(text).join('') : typeof n === 'string' ? n : typeof n === 'number' ? String(n) : n && typeof n === 'object' ? text(n.props?.children) : '');
const where = (name, value) => (n) => n.props?.[name] !== undefined && (value === undefined || n.props[name] === value);
const ok = (data) => async () => ({ ok: true, data });

async function screenWith(api) {
  const screen = await mount('pages/Approvals.tsx', 'Approvals', {}, api, SPECS);
  await screen.settle();
  await screen.settle();
  return screen;
}

test('screen: a table of what waits, each row with an Open link, a caption and one h1', async () => {
  const screen = await screenWith({
    listFlows: ok([flow('SUBMITTED', 'a')]),
    listContracts: ok([contract('k1', 'TARGET_REQUEST', 'PENDING_APPROVAL')]),
  });
  const root = screen.tree();
  assert.equal(findAll(root, (n) => n.type === 'h1').length, 1);
  assert.match(text(findAll(root, where('data-page', 'approvals'))[0]), /Waiting for your approval \(2\)/);
  const rows = findAll(root, where('data-approval-row'));
  assert.equal(rows.length, 2);
  const hrefs = rows.map((r) => findAll(r, (n) => n.type === 'a')[0].props.href);
  assert.deepEqual(hrefs, ['#/versions/a', '#/contracts']);
  assert.equal(findAll(root, (n) => n.type === 'caption').length, 1);
});

test('screen: empty state is worded and offers where to look next', async () => {
  const screen = await screenWith({ listFlows: ok([flow('DRAFT', 'a')]), listContracts: ok([]) });
  const root = screen.tree();
  assert.match(text(root), /Nothing waits for approval/);
  assert.equal(findAll(root, where('data-approval-row')).length, 0);
  assert.match(text(root), /Waiting for your approval \(0\)/);
});

test('screen: a 403 on a list shows NoAccess, not a half list and not a generic error', async () => {
  const screen = await screenWith({ listFlows: async () => ({ ok: false, status: 403 }), listContracts: ok([]) });
  const root = screen.tree();
  assert.equal(findAll(root, where('data-no-access', 'true')).length, 1);
  assert.equal(findAll(root, where('data-approval-row')).length, 0);
});

test('screen: a failed read is a worded alert with Retry; nothing is shown as zero', async () => {
  const screen = await screenWith({ listFlows: async () => ({ ok: false, status: 500 }), listContracts: ok([]) });
  const root = screen.tree();
  const alerts = findAll(root, (n) => n.props?.role === 'alert');
  assert.equal(alerts.length, 1);
  assert.match(text(alerts[0]), /could not be loaded/i);
  assert.equal(findAll(root, where('data-action', 'retry-approvals')).length, 1);
  assert.doesNotMatch(text(root), /Nothing waits for approval/);
});

test('screen: makes exactly the two list reads and nothing else', async () => {
  const calls = [];
  await screenWith({
    listFlows: async () => { calls.push('flows'); return { ok: true, data: [] }; },
    listContracts: async () => { calls.push('contracts'); return { ok: true, data: [] }; },
  });
  assert.deepEqual(calls.sort(), ['contracts', 'flows']);
});

test('App.tsx renders the Approvals screen; the stub landing is gone', () => {
  const app = readFileSync(fileURLToPath(new URL('../../src/App.tsx', import.meta.url)), 'utf8');
  // W6: no <Screen> envelope around pages that render their own heading (it would add a second h1).
  assert.ok(app.includes("shown === 'approvals' && <Approvals />"));
  assert.ok(!app.includes('ApprovalsLanding'));
});
