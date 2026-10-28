// Row 310 lane A (sanjay-developer, T87): the Dashboard's pure count/selection helper and the
// sign-in state's session mapping (10-screens §1/§2, 00-portal-ia §4/§5). Counts come ONLY from the
// list answers the console already fetches (listFlows / listSystems / listContracts) - no new API.
// "Pending approval" is tenant-scoped with honest copy (Q3 default, m-5517d7): the flow list carries
// no author, so "my" is not expressible in phase 1.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/dashboard-counts.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';

const {
  flowCounts,
  contractsInDraft,
  systemsByDirection,
  recentVersions,
  tileName,
  statusBadge,
  greetingFor,
  sessionStateOf
} = await import('../../src/pages/dashboard-counts.ts');

const flows = [
  { id: 'f1', code: 'a', name: 'A', ordering: 'NONE', status: 'DRAFT' },
  { id: 'f2', code: 'b', name: 'B', ordering: 'NONE', status: 'SUBMITTED' },
  { id: 'f3', code: 'c', name: 'C', ordering: 'NONE', status: 'PUBLISHED' },
  { id: 'f4', code: 'd', name: 'D', ordering: 'NONE', status: 'DRAFT' },
  { id: 'f5', code: 'e', name: 'E', ordering: 'NONE', status: 'APPROVED' },
  { id: 'f6', code: 'f', name: 'F', ordering: 'NONE', status: 'SUBMITTED' },
  { id: 'f7', code: 'g', name: 'G', ordering: 'NONE', status: 'SUBMITTED' }
];

test('flow counts: SUBMITTED = pending approval, DRAFT = drafts to finish', () => {
  assert.deepEqual(flowCounts(flows), { pendingApproval: 3, drafts: 2 });
  assert.deepEqual(flowCounts([]), { pendingApproval: 0, drafts: 0 });
});

test('contracts in draft: a contract counts once when any of its versions is a DRAFT', () => {
  const contracts = [
    { id: 'c1', versions: [{ status: 'DRAFT' }, { status: 'DRAFT' }] },
    { id: 'c2', versions: [{ status: 'APPROVED' }] },
    { id: 'c3', versions: [{ status: 'APPROVED' }, { status: 'DRAFT' }] },
    { id: 'c4', versions: [{ status: 'PENDING_APPROVAL' }] },
    { id: 'c5' }
  ];
  assert.equal(contractsInDraft(contracts), 2);
  assert.equal(contractsInDraft([]), 0);
});

test('systems: total with the inbound / outbound split', () => {
  const systems = [{ direction: 'INBOUND' }, { direction: 'OUTBOUND' }, { direction: 'OUTBOUND' }];
  assert.deepEqual(systemsByDirection(systems), { total: 3, inbound: 1, outbound: 2 });
});

test('recent versions: at most 5 rows, the last listed first (the list carries no timestamp)', () => {
  const rows = recentVersions(flows);
  assert.equal(rows.length, 5);
  assert.deepEqual(rows.map((r) => r.id), ['f7', 'f6', 'f5', 'f4', 'f3']);
  assert.deepEqual(recentVersions(flows.slice(0, 2)).map((r) => r.id), ['f2', 'f1']);
  assert.equal(flows[0].id, 'f1', 'the input list is not mutated');
});

test('tile names are the programmatic names: count + words, singular, and the worded zero', () => {
  assert.equal(tileName('pending', 3), '3 versions pending approval');
  assert.equal(tileName('pending', 1), '1 version pending approval');
  assert.equal(tileName('pending', 0), 'No versions pending approval');
  assert.equal(tileName('drafts', 2), '2 drafts to finish');
  assert.equal(tileName('drafts', 1), '1 draft to finish');
  assert.equal(tileName('systems', 12), '12 systems registered');
  assert.equal(tileName('systems', 1), '1 system registered');
  assert.equal(tileName('contracts', 5), '5 contracts in draft');
  assert.equal(tileName('contracts', 0), 'No contracts in draft');
  assert.equal(tileName('systems', null), 'Systems registered: count not available');
});

test('status badges always carry the word (never colour alone) - 01-tokens status map', () => {
  assert.deepEqual(statusBadge('DRAFT'), { word: 'Draft', tone: 'warn' });
  assert.deepEqual(statusBadge('SUBMITTED'), { word: 'Submitted', tone: 'info' });
  assert.deepEqual(statusBadge('APPROVED'), { word: 'Approved', tone: 'success' });
  assert.deepEqual(statusBadge('PUBLISHED'), { word: 'Published', tone: 'success' });
  assert.deepEqual(statusBadge('RETIRED'), { word: 'Retired', tone: 'neutral' });
});

test('greeting by the local hour, with the session user when known', () => {
  assert.equal(greetingFor(9, 'ops.user'), 'Good morning, ops.user');
  assert.equal(greetingFor(14, 'ops.user'), 'Good afternoon, ops.user');
  assert.equal(greetingFor(20, undefined), 'Good evening');
});

test('session state from the GET /api/v1/me answer: 200 signed in, 401 signed out, anything else an error', () => {
  assert.equal(sessionStateOf(200, { userId: 'u' }), 'signed-in');
  assert.equal(sessionStateOf(401, undefined), 'signed-out');
  assert.equal(sessionStateOf(503, undefined), 'error');
  assert.equal(sessionStateOf(200, undefined), 'error', 'a 200 without a body cannot confirm who you are');
  assert.equal(sessionStateOf(null, undefined), 'error', 'no answer at all (network) is an error');
});

// row 310 B10 (sanjay-developer): /me carries only userId (a uuid, no display name), so the raw id
// never reaches the screen - the account chip reads 'Account', the greeting stays neutral.
test('B10 App.tsx never renders me.userId (chip falls back to Account, Dashboard gets no user)', async () => {
  const { readFileSync } = await import('node:fs');
  const app = readFileSync(new URL('../../src/App.tsx', import.meta.url), 'utf8');
  assert.doesNotMatch(app, /\{\s*me\??\.userId/, 'me.userId must not be rendered into JSX');
  assert.doesNotMatch(app, /user=\{\s*me\??\.userId/, 'the Dashboard user prop must not carry me.userId');
  const chip = app.match(/<summary[^>]*data-action="account"[^>]*>([\s\S]*?)<\/summary>/);
  assert.ok(chip, 'the account chip keeps its [data-action="account"] hook');
  // V4 4d: the chip grew an avatar circle + label + chevron; the hook and the neutral label stay.
  assert.match(chip[1], /\bAccount\b/, 'the chip shows the neutral label');
});
