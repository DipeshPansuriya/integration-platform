// T130 (row 323, F21 BS-B) step 5 - the base suite's direct reads, FAILING FIRST. No docker: pure.
//
// Every direct read the count oracle and the specs make is ONE read-only SELECT (source-scan.mjs
// isReadOnlySelect) AND runs in a read-only session (PGOPTIONS default_transaction_read_only=on) - two
// layers, so a slip in one is still refused. Only the booted suite postgres is ever asked (bootBackend
// names it ipass-ui-t41-pg-<8 hex>).

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readOnlyPsqlArgs, rowsOf } from '../lib/psql.mjs';

const PG = 'ipass-ui-t41-pg-0a1b2c3d';

test('one read, in a read-only session: PGOPTIONS, no psqlrc, ON_ERROR_STOP, unaligned tuples', () => {
  assert.deepEqual(readOnlyPsqlArgs(PG, 'select 1'), [
    'exec', '-e', 'PGOPTIONS=-c default_transaction_read_only=on', PG,
    'psql', '-X', '-q', '-U', 'owner', '-d', 'platform', '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-F', '|', '-c', 'select 1'
  ]);
});

test('a write, a second statement or any other container is refused before docker is called', () => {
  for (const sql of ['insert into public.message (x) values (1)', 'select 1; delete from public.outbox', 'set role owner', '']) {
    assert.throws(() => readOnlyPsqlArgs(PG, sql), /read-only/, sql);
  }
  for (const name of ['ipass-ui-t41-pg-', 'ipass-ui-kc-0a1b2c3d', 'postgres', 'ipass-ui-t41-pg-0a1b2c3d; ls', undefined]) {
    assert.throws(() => readOnlyPsqlArgs(name, 'select 1'), /suite postgres/, String(name));
  }
});

test('rowsOf: unaligned tuples into fields; no output is no rows; an empty field is null', () => {
  assert.deepEqual(rowsOf('a|b|c\nd||f\n'), [['a', 'b', 'c'], ['d', null, 'f']]);
  assert.deepEqual(rowsOf('42\n'), [['42']]);
  assert.deepEqual(rowsOf(''), []);
  assert.deepEqual(rowsOf('\n\n'), []);
  assert.deepEqual(rowsOf('a|b\r\n'), [['a', 'b']], 'a CR from the Windows docker CLI is not data');
});
