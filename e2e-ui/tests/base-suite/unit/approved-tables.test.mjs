// lane qa-f3-approved-tables (2026-10-22): the pure oracles of the F3 spec.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  DB_TABLE, STATUS, approvedTablesPath, dbSystemBody, decidedRowProblems, leaksOf, publishRefusalProblems, refusalOf, requestedRowProblems
} from '../lib/approved-tables.mjs';

test('approvedTablesPath builds list, row and action paths', () => {
  assert.equal(approvedTablesPath('s1'), '/api/v1/admin/systems/s1/approved-tables');
  assert.equal(approvedTablesPath('s1', 'r1', 'approve'), '/api/v1/admin/systems/s1/approved-tables/r1/approve');
});

test('dbSystemBody is an OUTBOUND DB system on the exception host with a plain-identifier table and account', () => {
  const body = dbSystemBody('f3-db-a', 'F3 DB A', { host: 'localhost', port: 8089 });
  assert.deepEqual({ ...body, settings: undefined }, { code: 'f3-db-a', name: 'F3 DB A', direction: 'OUTBOUND', type: 'DB', settings: undefined });
  assert.equal(body.settings.host, 'localhost');
  assert.equal(body.settings.port, 8089);
  assert.match(body.settings.table, /^[A-Za-z_][A-Za-z_0-9]{0,62}$/);
  assert.match(body.settings.user, /^[A-Za-z_][A-Za-z_0-9]{0,62}$/);
  assert.equal('approvedTables' in body.settings, false, 'the UI/API dropped approvedTables');
});

test('refusalOf reads only the named refusal code', () => {
  assert.equal(refusalOf({ status: 409, data: { refusal: 'NOT_PENDING_APPROVAL' } }), 'NOT_PENDING_APPROVAL');
  assert.equal(refusalOf({ status: 404, data: { error: 'not found' } }), null);
  assert.equal(refusalOf(null), null);
});

test('publishRefusalProblems accepts only the 400 that names the unapproved table', () => {
  const ok = { status: 400, data: { error: 'the publish was refused: the DB table is not approved for the target (f3-db-a.f3_orders); request and approve it first' } };
  assert.deepEqual(publishRefusalProblems(ok, 'f3-db-a'), []);
  assert.equal(publishRefusalProblems({ status: 200, data: {} }, 'f3-db-a').length, 3);
  assert.ok(publishRefusalProblems({ status: 400, data: { error: 'the DB table is not approved (other.f3_orders)' } }, 'f3-db-a').some((p) => p.includes('f3-db-a.f3_orders')));
});

test('requestedRowProblems: a clean pending row has none, every deviation is named', () => {
  const row = { id: 'r', systemId: 's', table: DB_TABLE, status: STATUS.pending, requestedBy: 'u1', decidedBy: null };
  assert.deepEqual(requestedRowProblems(row, 's'), []);
  assert.equal(requestedRowProblems({ ...row, status: STATUS.approved, decidedBy: 'u2' }, 's').length, 2);
  assert.ok(requestedRowProblems({ ...row, systemId: 'x' }, 's').length > 0);
});

test('decidedRowProblems: an approval by the requester is no problem any more (single-user workflow), a missing decider is', () => {
  assert.deepEqual(decidedRowProblems({ status: STATUS.approved, requestedBy: 'u1', decidedBy: 'u2' }, STATUS.approved), []);
  assert.deepEqual(decidedRowProblems({ status: STATUS.approved, requestedBy: 'u1', decidedBy: 'u1' }, STATUS.approved), []);
  assert.ok(decidedRowProblems({ status: STATUS.pending, requestedBy: 'u1', decidedBy: null }, STATUS.approved).length > 0);
});

test('leaksOf finds a foreign id in a list and ignores empty needles', () => {
  const list = [{ id: 'a1', systemId: 's-a' }];
  assert.deepEqual(leaksOf(list, ['s-b', 'b1']), []);
  assert.deepEqual(leaksOf([...list, { id: 'b1' }], ['s-b', 'b1', '', undefined]), ['b1']);
  assert.deepEqual(leaksOf(null, ['x']), []);
});
