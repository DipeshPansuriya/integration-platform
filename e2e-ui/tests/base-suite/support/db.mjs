// T130 (row 323, F21 BS-B): the base suite's direct reads in a worker - ONE read-only SELECT
// (lib/source-scan.mjs isReadOnlySelect) in a read-only psql session (lib/psql.mjs) against the booted
// suite postgres only (BS_PG_CONTAINER, named by bootBackend). Nothing here can write a row.

import { execFileSync } from 'node:child_process';
import { readOnlyPsqlArgs, rowsOf } from '../lib/psql.mjs';
import { bsEnv } from './boot-state.mjs';

/** Rows of one read-only query, each an array of text fields (SQL NULL -> null). Bounded: 30 s. */
export function readRows(sql) {
  const output = execFileSync('docker', readOnlyPsqlArgs(bsEnv('pgContainer'), sql), { encoding: 'utf8', timeout: 30000 });
  return rowsOf(output);
}

/** The single value of a one-row, one-column read; throws when the read does not give exactly one. */
export function readOne(sql) {
  const rows = readRows(sql);
  if (rows.length !== 1 || rows[0].length !== 1) throw new Error(`expected one value, got ${JSON.stringify(rows)} for: ${sql}`);
  return rows[0][0];
}
