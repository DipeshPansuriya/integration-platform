// T130 (row 323, F21 BS-B) step 5: the base suite's direct reads - pure argv and output handling for
// support/db.mjs. Two layers: the statement must be ONE read-only SELECT (source-scan.mjs isReadOnlySelect)
// AND the psql session itself is read-only (PGOPTIONS default_transaction_read_only=on), so a slip in
// either is still refused. Only the booted suite postgres is ever asked: bootBackend names it
// ipass-ui-t41-pg-<8 hex>, and the docker-suite lock makes it this run's.

import { isReadOnlySelect } from './source-scan.mjs';

const SUITE_PG = /^ipass-ui-t41-pg-[0-9a-f]{8}$/;

/** The `docker` arguments of one read-only query against the suite postgres; refuses anything else. */
export function readOnlyPsqlArgs(container, sql) {
  if (typeof container !== 'string' || !SUITE_PG.test(container)) {
    throw new Error(`base suite: ${container} is not the booted suite postgres (ipass-ui-t41-pg-<8 hex>)`);
  }
  if (!isReadOnlySelect(sql)) throw new Error(`base suite: only ONE read-only SELECT runs here, not: ${String(sql).slice(0, 120)}`);
  return ['exec', '-e', 'PGOPTIONS=-c default_transaction_read_only=on', container,
    'psql', '-X', '-q', '-U', 'owner', '-d', 'platform', '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-F', '|', '-c', sql];
}

/**
 * psql's unaligned tuples (-t -A -F '|') as rows of fields; an empty field is null (SQL NULL prints as
 * nothing). An empty line is no row, so a query must never return ONE column that can be empty.
 */
export function rowsOf(output) {
  return String(output ?? '').split('\n').map((line) => line.replace(/\r$/, '')).filter((line) => line !== '')
    .map((line) => line.split('|').map((field) => (field === '' ? null : field)));
}
