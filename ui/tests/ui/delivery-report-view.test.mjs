// T139 (E13, lane e13-t139ui): the delivery-report view's pure view model, unit-tested under node.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/delivery-report-view.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';

const { reportRows, reportColumns, reportQuery, todayUtc } = await import(
  '../../src/pages/delivery-report-view.ts'
);

const I1 = '6f0a2c1e-1111-4aaa-9bbb-000000000001';
const I2 = '6f0a2c1e-2222-4aaa-9bbb-000000000002';
const T1 = '9c1a2d1e-1111-4aaa-9bbb-000000000001';
const T2 = '9c1a2d1e-2222-4aaa-9bbb-000000000002';

// DailyCounts shape from platform-delivery DeliveryDailyAggregates.java (GET /api/v1/reports/delivery).
const answer = [
  {
    interfaceId: I1,
    targetId: T1,
    successes: 5,
    failuresTransient: 2,
    failuresAmbiguous: 1,
    failuresPermanent: 3,
    retriesMade: 4,
    retriedThenSucceeded: 2,
    stillStuck: 3
  },
  {
    interfaceId: I2,
    targetId: T2,
    successes: 0,
    failuresTransient: 0,
    failuresAmbiguous: 0,
    failuresPermanent: 0,
    retriesMade: 0,
    retriedThenSucceeded: 0,
    stillStuck: 0
  }
];

test('reportRows keeps one row per interface/target with computed totals', () => {
  const rows = reportRows(answer);
  assert.equal(rows.length, 2);
  const first = rows[0];
  assert.equal(first.interfaceId, I1);
  assert.equal(first.targetId, T1);
  assert.equal(first.failures, 6); // 2 + 1 + 3
  assert.equal(first.deliveries, 11); // 5 + 6
  assert.equal(first.stillStuck, 3);
  assert.equal(typeof first.key, 'string');
});

test('reportColumns names the shown columns', () => {
  const cols = reportColumns();
  assert.ok(Array.isArray(cols) && cols.length >= 4);
  assert.ok(cols.every((c) => typeof c === 'string'));
  assert.ok(cols.includes('Successes'));
});

test('reportQuery validates the day and drops junk (a junk value would be a 400, not no data)', () => {
  assert.deepEqual(reportQuery({ day: '2026-10-19' }), { day: '2026-10-19' });
  assert.equal(reportQuery({ day: '19-10-2026' }), undefined);
  assert.equal(reportQuery({ day: '' }), undefined);
  assert.equal(reportQuery({ day: undefined }), undefined);
});

test('todayUtc is the backend shape: a UTC YYYY-MM-DD day', () => {
  assert.equal(todayUtc(new Date('2026-10-19T01:23:45Z')), '2026-10-19');
});