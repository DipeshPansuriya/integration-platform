// T112 (E13 base N3, lane D - vijay-developer): the failures screen's pure view model.
// GET /api/v1/admin/messages/failures?flowId=&date= (T110) answers failed and dead-lettered work per
// flow and UTC day, newest first. Each row links to the detail screen (passing the failing stage, which
// the timeline cannot tell for a delivery failure), says who failed (PLATFORM = our side, TARGET = the
// target's answer) and shows the last error as text. No DOM, no fetch - unit-tested under node.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/failures-view.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';

const { failureRows, failuresQuery, todayUtc, originWord, emptyText, failuresCaption } = await import(
  '../../src/pages/failures-view.ts'
);

const FLOW = '0b6c2d1e-7f8a-4b9c-8d0e-1f2a3b4c5d6e';
const M1 = '3f2b8a52-6c1d-4e0f-9a7b-1c2d3e4f5a6b';
const M2 = '9a8b7c6d-5e4f-4a3b-9c2d-1e0f2a3b4c5d';
const D1 = 'aa11bb22-cc33-4d44-8e55-ff6677889900';

const answer = [
  {
    trackingId: M1,
    deliveryId: D1,
    flowId: FLOW,
    targetId: 't-1',
    failingStage: 'DELIVER',
    errorClass: 'PERMANENT',
    lastError: '<b>HTTP 422</b> rejected',
    origin: 'TARGET',
    failedAt: '2026-10-17T12:05:00Z'
  },
  {
    trackingId: M2,
    deliveryId: null,
    flowId: FLOW,
    targetId: null,
    failingStage: 'ROUTE',
    errorClass: 'ROUTE_FAILED',
    lastError: null,
    origin: 'PLATFORM',
    failedAt: '2026-10-17T11:00:00.5Z'
  }
];

test('each failure becomes a row: tracking id linked to the detail screen with its failing stage', () => {
  const rows = failureRows(answer);
  assert.equal(rows.length, 2);
  assert.equal(rows[0].trackingId, M1);
  assert.equal(rows[0].detailHash, `#/messages/${M1}?failed=DELIVER`);
  assert.equal(rows[1].detailHash, `#/messages/${M2}?failed=ROUTE`);
  assert.equal(rows[0].stageLabel, 'Deliver');
  assert.equal(rows[1].stageLabel, 'Route');
  assert.equal(rows[0].errorClass, 'PERMANENT');
  assert.equal(rows[0].failedAtText, '2026-10-17 12:05:00 UTC');
  assert.equal(rows[0].failedAt, '2026-10-17T12:05:00Z');
});

test('the order is the backend order (newest first) - the view never re-sorts', () => {
  assert.deepEqual(failureRows(answer).map((r) => r.trackingId), [M1, M2]);
});

test('rows have unique keys: a delivery by its id, a route failure by its tracking id', () => {
  const keys = failureRows(answer).map((r) => r.key);
  assert.equal(new Set(keys).size, keys.length);
  assert.equal(keys[0], D1);
});

test('the last error stays plain text as answered (React escapes it); a missing one is worded', () => {
  const rows = failureRows(answer);
  assert.equal(rows[0].lastErrorText, '<b>HTTP 422</b> rejected', 'text, not markup - never parsed as HTML');
  assert.equal(rows[0].hasLastError, true);
  assert.equal(rows[1].lastErrorText, 'No error text recorded');
  assert.equal(rows[1].hasLastError, false);
});

test('origin words: PLATFORM is our side, TARGET is the target answer, anything else as named', () => {
  assert.equal(originWord('PLATFORM'), 'Platform (our side)');
  assert.equal(originWord('TARGET'), 'Target answer');
  assert.equal(originWord('OTHER'), 'OTHER');
  assert.deepEqual(failureRows(answer).map((r) => r.originWord), ['Target answer', 'Platform (our side)']);
});

test('the query sends only valid filters: a uuid flow and an ISO date; blanks and junk are dropped', () => {
  assert.deepEqual(failuresQuery({ flowId: FLOW, date: '2026-10-17' }), { flowId: FLOW, date: '2026-10-17' });
  assert.deepEqual(failuresQuery({ flowId: '', date: '' }), {});
  assert.deepEqual(failuresQuery({ flowId: 'not-a-uuid', date: '17/10/2026' }), {});
  assert.deepEqual(failuresQuery({ flowId: FLOW, date: '' }), { flowId: FLOW });
});

test('the default day is today in UTC (not the browser zone)', () => {
  assert.equal(todayUtc(new Date('2026-10-17T23:30:00-05:00')), '2026-10-18');
  assert.equal(todayUtc(new Date('2026-10-17T00:10:00Z')), '2026-10-17');
});

test('empty and caption words name the filters actually applied', () => {
  assert.equal(emptyText(null, null), 'No failed messages.');
  assert.equal(emptyText('ORDERS', '2026-10-17'), 'No failed messages for ORDERS on 2026-10-17 (UTC).');
  assert.equal(emptyText(null, '2026-10-17'), 'No failed messages on 2026-10-17 (UTC).');
  assert.equal(emptyText('ORDERS', null), 'No failed messages for ORDERS.');
  assert.equal(failuresCaption('ORDERS', '2026-10-17'), 'Failed and dead-lettered messages for ORDERS on 2026-10-17 (UTC), newest first');
  assert.equal(failuresCaption(null, null), 'Failed and dead-lettered messages, all flows and days, newest first');
});

test('an empty answer is no rows', () => {
  assert.deepEqual(failureRows([]), []);
});
