// F3 (row 523, ADR-H-IPASS-03): the worker's side of the target database sidecar (boot-backend.mjs targetDb).
// Pure: the names and the statements. support/target-db.mjs runs them. Each tenant gets its own NARROW login -
// INSERT and SELECT on the two declared tables, no other right - so the privilege probe has something real to read.

import { DB_NAME, DB_SELF_TABLE, DB_TABLE } from './approved-tables.mjs';

const TARGET_CONTAINER = /^ipass-ui-bs-tdb-[0-9a-f]{8}$/;
const IDENTIFIER = /^[a-z][a-z0-9_]{0,62}$/;
const PASSWORD = /^[A-Za-z0-9_-]{16,64}$/;

/** The tenant's restricted login: `f3_etl_<tenant key>_<run suffix>`, a plain lower-case identifier. */
export function accountName(key, rand) {
  const name = `f3_etl_${key}_${rand}`;
  if (!/^[a-z]$/.test(key) || !/^[0-9a-f]{8}$/.test(rand) || !IDENTIFIER.test(name)) throw new Error(`base suite: ${name} is not a valid target account name`);
  return name;
}

/** The owner's statements that create one narrow login (password in the statement: it travels on stdin only). */
export function narrowAccountSql({ account, password }) {
  if (typeof account !== 'string' || !IDENTIFIER.test(account)) throw new Error(`base suite: account ${account} is not a plain identifier`);
  if (typeof password !== 'string' || !PASSWORD.test(password)) throw new Error('base suite: the account password must be 16-64 letters, digits, - or _');
  return [
    `CREATE ROLE ${account} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '${password}'`,
    ...[DB_TABLE, DB_SELF_TABLE].map((table) => `GRANT INSERT, SELECT ON TABLE public.${table} TO ${account}`)
  ];
}

/** The `docker` arguments of a psql that reads its statements from stdin, as the owner, on the booted target database only. */
export function targetPsqlArgs(container) {
  if (typeof container !== 'string' || !TARGET_CONTAINER.test(container)) {
    throw new Error(`base suite: ${container} is not the booted target database (ipass-ui-bs-tdb-<8 hex>)`);
  }
  return ['exec', '-i', container, 'psql', '-X', '-q', '-U', 'owner', '-d', DB_NAME, '-v', 'ON_ERROR_STOP=1'];
}
