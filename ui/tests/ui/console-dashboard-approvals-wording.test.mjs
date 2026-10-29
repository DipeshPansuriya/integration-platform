// Row 582: the console dashboard's approvals block kept the old "Waiting for your approval" wording after
// commit b003d237 (PR #125) established the tenant-wide rule "Waiting for approval" everywhere - the file was
// added by PR #124 after the follow-up commit was cut. This unit test pins the console page to the
// tenant-wide wording, matching the old page's own test (home-steps.test.mjs, /Waiting for approval \(2\)/)
// and the console e2e (home-next-steps.docker.spec.mjs).
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/console-dashboard-approvals-wording.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { findAll, mount } from './hook-harness.mjs';

const SPECS = ['../../api/flows', '../../api/systems', '../../api/contracts', '../../api/messages'];
const ok = (data) => async () => ({ ok: true, data });
const sys = (direction) => ({ direction });
const contract = (...statuses) => ({ versions: statuses.map((status) => ({ status })) });
const flow = (status, id = status) => ({ id, code: id, name: `Flow ${id}`, status });

const text = (n) => (Array.isArray(n) ? n.map(text).join('') : typeof n === 'string' ? n : typeof n === 'number' ? String(n) : n && typeof n === 'object' ? text(n.props?.children) : '');
const block = (tree, name) => findAll(tree, (n) => n.props?.['data-home-block'] === name)[0];

test('console home approvals block says "Waiting for approval (N)" - the count is tenant-wide, no "your"', async () => {
  const screen = await mount('console/pages/DashboardPage.tsx', 'DashboardPage', { readsConfiguration: true, roles: ['TENANT_ADMIN'], user: 'A' }, {
    listFlows: ok([flow('SUBMITTED', 'f1')]),
    listSystems: ok([sys('INBOUND'), sys('OUTBOUND')]),
    listContracts: ok([contract('PENDING_APPROVAL')]),
    messageFailures: ok([{ trackingId: 'a' }, { trackingId: 'b' }])
  }, SPECS);
  await screen.settle();
  const approvals = block(screen.tree(), 'approvals');
  assert.ok(approvals, 'the approvals block renders for a configurator-reading role');
  const heading = text(approvals);
  assert.match(heading, /Waiting for approval \(2\)/);
  assert.doesNotMatch(heading, /your/, 'the count is tenant-wide; the wording must not say "your"');
});