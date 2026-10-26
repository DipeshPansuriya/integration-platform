// Lane screens-gap, FAILING FIRST: the pure fixture builders the new docker screen specs share
// (tests/screens/screen-sql.mjs). No docker, no JVM - `node --test tests/screens/unit/screen-sql.test.mjs`.
// The SQL builder is the one place a spec composes text for `psql -c`, so it refuses anything that is
// not a uuid / ISO day / non-negative integer instead of interpolating it.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { aggregateRowSql, assertIsoDay, assertUuid, utcDay } from '../screen-sql.mjs';

const TENANT = '11111111-2222-3333-4444-555555555555';
const FLOW = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
const TARGET = '99999999-8888-7777-6666-555555555555';

test('utcDay is the UTC calendar day, shifted by whole days', () => {
  const now = new Date('2026-10-22T23:59:59Z');
  assert.equal(utcDay(0, now), '2026-10-22');
  assert.equal(utcDay(1, now), '2026-10-23');
  assert.equal(utcDay(-22, now), '2026-09-30');
});

test('assertUuid passes a uuid and names the field of anything else', () => {
  assert.equal(assertUuid(TENANT, 'tenantId'), TENANT);
  assert.throws(() => assertUuid("x'; drop table message; --", 'tenantId'), /tenantId/);
  assert.throws(() => assertUuid(undefined, 'flowId'), /flowId/);
});

test('assertIsoDay accepts only a real YYYY-MM-DD day', () => {
  assert.equal(assertIsoDay('2020-01-15'), '2020-01-15');
  assert.throws(() => assertIsoDay('2020-13-40'), /day/);
  assert.throws(() => assertIsoDay("2020-01-15'; --"), /day/);
});

test('aggregateRowSql binds the tenant for FORCE RLS BEFORE the insert and carries every count column', () => {
  const sql = aggregateRowSql({
    tenantId: TENANT, day: '2020-01-15', interfaceId: FLOW, targetId: TARGET,
    successes: 5, failuresTransient: 3, failuresAmbiguous: 1, failuresPermanent: 2,
    retriesMade: 4, retriedThenSucceeded: 2, stillStuck: 1
  });
  assert.ok(sql.indexOf("set_config('app.tenant_id'") < sql.indexOf('insert into public.delivery_daily_aggregate'), sql);
  for (const column of ['successes', 'failures_transient', 'failures_ambiguous', 'failures_permanent',
    'retries_made', 'retried_then_succeeded', 'still_stuck']) assert.match(sql, new RegExp(column));
  assert.match(sql, /values \('1{8}-2222-3333-4444-555555555555'::uuid, '2020-01-15'::date, 'a{8}-/);
  assert.match(sql, /, 5, 3, 1, 2, 4, 2, 1\)/);
});

test('aggregateRowSql defaults the counts to 0 and refuses a non-uuid, a bad day or a negative count', () => {
  assert.match(aggregateRowSql({ tenantId: TENANT, day: '2020-01-16', interfaceId: FLOW, targetId: TARGET }), /, 0, 0, 0, 0, 0, 0, 0\)/);
  assert.throws(() => aggregateRowSql({ tenantId: "x'", day: '2020-01-16', interfaceId: FLOW, targetId: TARGET }), /tenantId/);
  assert.throws(() => aggregateRowSql({ tenantId: TENANT, day: 'today', interfaceId: FLOW, targetId: TARGET }), /day/);
  assert.throws(() => aggregateRowSql({ tenantId: TENANT, day: '2020-01-16', interfaceId: FLOW, targetId: TARGET, successes: -1 }), /successes/);
  assert.throws(() => aggregateRowSql({ tenantId: TENANT, day: '2020-01-16', interfaceId: FLOW, targetId: TARGET, stillStuck: 1.5 }), /stillStuck/);
});
