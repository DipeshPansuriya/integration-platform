// Row 327 T119 (lane D, vijay-developer, E12-F2-S2 "I see only my own tenant's activity"): the activity
// view's pure view model - actor label, row mapping, query builder, pager, refusal states - plus a static
// source check of the page (node --test cannot render TSX; same approach as the B10 App.tsx check).
// The server scopes every read to the bound tenant; the screen filters nothing itself.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/activity-view.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const {
  SYSTEM_ACTOR,
  UNKNOWN_ACTOR,
  actorLabel,
  activityRows,
  activityQuery,
  pagerState,
  refusalState,
  REFUSAL_COPY,
  eventIdFromHash
} = await import('../../src/pages/activity-view.ts');

const event = (id, at, actor = 'a1b2c3d4-0000-4000-8000-000000000001') => ({
  id,
  actor,
  action: 'flow.published',
  targetIds: ['f-1', 'v-2'],
  before: '{"status":"APPROVED"}',
  after: '{"status":"PUBLISHED"}',
  at
});

test('the nil-UUID system actor is shown as "System", never as the raw UUID', () => {
  assert.equal(SYSTEM_ACTOR, '00000000-0000-0000-0000-000000000000');
  assert.equal(actorLabel(SYSTEM_ACTOR), 'System');
});

test('a null, missing or blank actor shows the "Unknown" placeholder, never a blank cell', () => {
  assert.equal(UNKNOWN_ACTOR, 'Unknown');
  assert.equal(actorLabel(null), 'Unknown');
  assert.equal(actorLabel(undefined), 'Unknown');
  assert.equal(actorLabel(''), 'Unknown');
  assert.equal(actorLabel('   '), 'Unknown');
});

test('a normal actor passes through as the API returned it', () => {
  assert.equal(actorLabel('a1b2c3d4-0000-4000-8000-000000000001'), 'a1b2c3d4-0000-4000-8000-000000000001');
});

test('rows keep the server order (newest first) and show only the returned fields, as text', () => {
  const items = [
    event('30', '2026-10-17T10:00:00Z'),
    event('20', '2026-10-16T10:00:00Z', SYSTEM_ACTOR),
    event('10', '2026-10-15T10:00:00Z', null)
  ];
  const rows = activityRows(items);
  assert.deepEqual(rows.map((r) => r.id), ['30', '20', '10'], 'no client-side re-sort');
  assert.deepEqual(rows.map((r) => r.actor), ['a1b2c3d4-0000-4000-8000-000000000001', 'System', 'Unknown']);
  assert.deepEqual(rows[0], {
    id: '30',
    actor: 'a1b2c3d4-0000-4000-8000-000000000001',
    action: 'flow.published',
    targets: 'f-1, v-2',
    before: '{"status":"APPROVED"}',
    after: '{"status":"PUBLISHED"}',
    at: '2026-10-17T10:00:00Z'
  });
  assert.equal(items[0].actor, 'a1b2c3d4-0000-4000-8000-000000000001', 'the input is not mutated');
});

test('null before/after/targets show as empty text, nothing invented', () => {
  const [row] = activityRows([{ id: '1', actor: SYSTEM_ACTOR, action: 'x', targetIds: null, before: null, after: null, at: 't' }]);
  assert.equal(row.targets, '');
  assert.equal(row.before, '');
  assert.equal(row.after, '');
});

test('the query carries action/from/to/page/size and omits the empty ones', () => {
  const full = new URLSearchParams(activityQuery({ action: 'flow.published', from: '2026-10-01', to: '2026-10-17', page: 2, size: 50 }));
  assert.equal(full.toString(), 'action=flow.published&from=2026-10-01&to=2026-10-17&page=2&size=50');
  const sparse = new URLSearchParams(activityQuery({ action: '  ', from: '', to: '2026-10-17', page: 0, size: 50 }));
  assert.equal(sparse.toString(), 'to=2026-10-17&page=0&size=50');
  assert.deepEqual(activityQuery({ page: 0, size: 50 }), { page: 0, size: 50 });
  assert.deepEqual(activityQuery({ action: ' flow.published ', page: 0, size: 50 }), { action: 'flow.published', page: 0, size: 50 });
});

test('pager: hasMore enables next; page 0 disables previous', () => {
  assert.deepEqual(pagerState(0, true), { previous: false, next: true });
  assert.deepEqual(pagerState(0, false), { previous: false, next: false });
  assert.deepEqual(pagerState(3, false), { previous: true, next: false });
  assert.deepEqual(pagerState(3, true), { previous: true, next: true });
});

test('404 is the plain not-found state (another tenant\'s event gives nothing)', () => {
  assert.equal(refusalState(404), 'not-found');
  assert.equal(REFUSAL_COPY['not-found'], 'Activity event not found.');
});

test('403 is the no-access state', () => {
  assert.equal(refusalState(403), 'no-access');
  assert.equal(REFUSAL_COPY['no-access'], 'You do not have access to this page. Ask an administrator if you need it.');
  assert.equal(refusalState(500), 'failed');
  assert.equal(refusalState(400), 'failed');
});

test('the event id is read from #/activity/<id>; the list route has none', () => {
  assert.equal(eventIdFromHash('#/activity/42'), '42');
  assert.equal(eventIdFromHash('#/activity/a%20b'), 'a b');
  assert.equal(eventIdFromHash('#/activity'), null);
  assert.equal(eventIdFromHash('#/activity/'), null);
  assert.equal(eventIdFromHash('#/queue'), null);
});

test('the page source: labelled filters, the view-model actor label, no raw HTML, no own <main>', () => {
  const page = readFileSync(new URL('../../src/pages/Activity.tsx', import.meta.url), 'utf8');
  for (const field of ['activity-action', 'activity-from', 'activity-to']) {
    const labelled = new RegExp(`<label[^>]*htmlFor="${field}"`).test(page) && page.includes(`id="${field}"`);
    assert.ok(labelled, `filter input #${field} carries a <label htmlFor>`);
  }
  assert.match(page, /actorLabel|activityRows/, 'the actor cell comes from the view model (System / Unknown)');
  assert.doesNotMatch(page, /innerHTML|dangerouslySetInnerHTML/, 'before/after are text, never HTML');
  assert.doesNotMatch(page, /<main(?=[\s>])/, 'the shell owns the one <main>');
  assert.doesNotMatch(page, /tenantId|tenantCode/, 'no client-side tenant filtering - the server scopes');
  const app = readFileSync(new URL('../../src/nav-model.ts', import.meta.url), 'utf8'); // row 575 S1: the menu table
  assert.match(app, /\{ label: 'Activity', nav: 'activity', hash: '#\/activity', page: 'activity', needs: 'READ_MESSAGE_TIMELINE' \}/, 'one nav entry');
  assert.match(readFileSync(new URL('../../src/App.tsx', import.meta.url), 'utf8'), /shown === 'activity' && <Activity \/>/, 'one route branch');
});
