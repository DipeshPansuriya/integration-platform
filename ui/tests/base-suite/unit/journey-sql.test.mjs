// T131 (row 323, F21 BS-C, BS-10) - the journey's direct reads, FAILING FIRST: ONE read-only SELECT each,
// scoped to one tenant by its code, every id a UUID literal (never free text). No docker.
// The audit_event columns are T109's (lane t109 2344b8d V2_2_1220, not on main) - provisional.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { attemptRowsSql, auditRowsSql, deliveryOfMessageSql, eventTypesSql } from '../lib/direct-sql.mjs';
import { isReadOnlySelect } from '../lib/source-scan.mjs';

const CODE = 'lane-d-0a1b2c';
const ID = '3c2b1a09-0000-4000-8000-0000000000d1';

test('every journey read is one read-only SELECT naming the tenant code and the id', () => {
  for (const sql of [deliveryOfMessageSql(CODE, ID), attemptRowsSql(CODE, ID), eventTypesSql(CODE, ID)]) {
    assert.ok(isReadOnlySelect(sql), sql);
    assert.ok(sql.includes(`'${CODE}'`), sql);
    assert.ok(sql.includes(`'${ID}'`), sql);
  }
  assert.ok(isReadOnlySelect(auditRowsSql(CODE)), auditRowsSql(CODE));
  assert.ok(auditRowsSql(CODE).includes(`'${CODE}'`));
});

test('the delivery read carries the pins; the attempt read hashes the detail; events and audits keep their order', () => {
  assert.match(deliveryOfMessageSql(CODE, ID), /select d\.id::text, d\.state, d\.mapping_version_id::text, d\.contract_version_id::text from public\.delivery d/);
  assert.match(attemptRowsSql(CODE, ID), /md5\(coalesce\(a\.detail, ''\)\)/);
  assert.match(attemptRowsSql(CODE, ID), /order by a\.id$/);
  assert.match(eventTypesSql(CODE, ID), /select e\.type from public\.delivery_event e .* order by e\.id$/);
  assert.match(auditRowsSql(CODE), /from public\.audit_event a .* order by a\.id$/);
});

test('an id that is not a UUID, or a bad tenant code, is refused before any SQL exists', () => {
  for (const bad of ["x' or '1'='1", '', null, '3c2b1a09-0000-4000-8000-0000000000d1; select 1']) {
    assert.throws(() => deliveryOfMessageSql(CODE, bad), /not a UUID/);
    assert.throws(() => attemptRowsSql(CODE, bad), /not a UUID/);
    assert.throws(() => eventTypesSql(CODE, bad), /not a UUID/);
  }
  assert.throws(() => auditRowsSql('UPPER'), /tenant code/);
});
