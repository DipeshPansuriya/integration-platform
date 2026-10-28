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

const REDACTED = '[redacted]';

/**
 * Row 556 (Karan F5, CWE-532): text with the account password removed. Three layers: any `PASSWORD '...'` literal (also one psql
 * truncated mid-echo, so no full password is left to match), then every literal occurrence of the password (regex-special
 * characters are plain text) and of its base64 spelling (`+` `/` for `-` `_`). Total: never throws, '' for no text.
 */
export function redactPassword(text, password) {
  let out = text === undefined || text === null ? '' : String(text);
  out = out.replace(/PASSWORD\s+'[^'\r\n]*'?/gi, `PASSWORD ${REDACTED}`);
  if (typeof password === 'string' && password !== '') {
    for (const form of new Set([password, password.replace(/-/g, '+').replace(/_/g, '/')])) out = out.split(form).join(REDACTED);
  }
  return out;
}

/**
 * Runs the narrow-account statements through `exec` (execFileSync in the worker). A failure is rethrown as a NEW error: container,
 * account, exit status/code and psql's stderr with the password redacted. The original error (message, stderr, stdout, cmd,
 * input all carry the statement) is neither attached as `cause` nor copied.
 */
export function createAccountVia(exec, { container, account, password }) {
  const input = `${narrowAccountSql({ account, password }).join(';\n')};\n`;
  try {
    exec('docker', targetPsqlArgs(container), { input, encoding: 'utf8', timeout: 30000 });
  } catch (error) {
    const how = [error?.status != null ? `status ${error.status}` : '', error?.signal ? `signal ${error.signal}` : '', /^[A-Z_]+$/.test(error?.code ?? '') ? `code ${error.code}` : ''].filter(Boolean).join(', ');
    const stderr = redactPassword(error?.stderr, password).trim();
    throw new Error(`base suite: creating target account ${account} on ${container} failed (${how || 'no exit status'})${stderr ? `: ${stderr}` : ''}`);
  }
}
