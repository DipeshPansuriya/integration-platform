// F3 (row 523, ADR-H-IPASS-03) - the worker's side of the target database, FAILING FIRST. No docker: pure.
// Each tenant gets its own NARROW login on the sidecar (INSERT and SELECT on the declared tables, nothing else),
// created by psql over stdin (a password never rides in an argument) against the booted sidecar only.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { DB_NAME, DB_SELF_TABLE, DB_TABLE } from '../lib/approved-tables.mjs';
import { accountName, narrowAccountSql, targetPsqlArgs } from '../lib/target-db.mjs';

test('accountName: one plain lower-case identifier per tenant and run, never the fixed unit-row name', () => {
  assert.equal(accountName('a', '0a1b2c3d'), 'f3_etl_a_0a1b2c3d');
  assert.equal(accountName('b', '0a1b2c3d'), 'f3_etl_b_0a1b2c3d');
  for (const [key, rand] of [['A', '0a1b2c3d'], ['a b', '0a1b2c3d'], ['a', ''], ['a', "x'; drop"], ['', 'ab']]) {
    assert.throws(() => accountName(key, rand), /account/, `${key}/${rand}`);
  }
});

test('narrowAccountSql: login only, INSERT and SELECT on exactly the two tables, nothing wider', () => {
  const sql = narrowAccountSql({ account: 'f3_etl_a_0a1b2c3d', password: 'Zm9vYmFyYmF6cXV4MTIzNDU' });
  assert.deepEqual(sql, [
    "CREATE ROLE f3_etl_a_0a1b2c3d LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'Zm9vYmFyYmF6cXV4MTIzNDU'",
    `GRANT INSERT, SELECT ON TABLE public.${DB_TABLE} TO f3_etl_a_0a1b2c3d`,
    `GRANT INSERT, SELECT ON TABLE public.${DB_SELF_TABLE} TO f3_etl_a_0a1b2c3d`
  ]);
  for (const statement of sql.slice(1)) assert.doesNotMatch(statement, /\bALL\b|UPDATE|DELETE|TRUNCATE|TRIGGER|REFERENCES/);
});

test('narrowAccountSql refuses a value that could leave its SQL literal', () => {
  assert.throws(() => narrowAccountSql({ account: 'f3 etl', password: 'Zm9vYmFyYmF6cXV4MTIzNDU' }), /account/);
  assert.throws(() => narrowAccountSql({ account: 'f3_etl_a_0a1b2c3d', password: "short" }), /password/);
  assert.throws(() => narrowAccountSql({ account: 'f3_etl_a_0a1b2c3d', password: "has'quote-0123456789" }), /password/);
});

test('targetPsqlArgs: stdin psql as the owner on the target database of the booted sidecar only', () => {
  assert.deepEqual(targetPsqlArgs('ipass-ui-bs-tdb-0a1b2c3d'),
    ['exec', '-i', 'ipass-ui-bs-tdb-0a1b2c3d', 'psql', '-X', '-q', '-U', 'owner', '-d', DB_NAME, '-v', 'ON_ERROR_STOP=1']);
  for (const other of ['ipass-ui-t41-pg-0a1b2c3d', 'ipass-ui-bs-tdb-xyz', '', undefined, 'ipass-ui-bs-tdb-0a1b2c3d; rm']) {
    assert.throws(() => targetPsqlArgs(other), /not the booted target database/, String(other));
  }
});
