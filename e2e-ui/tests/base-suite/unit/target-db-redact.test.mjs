// Row 556 (Karan F5, CWE-532/CWE-459) - a failed CREATE ROLE must not carry its password into the Playwright/CI log. FAILING FIRST.
// execFileSync's own error holds psql's echo of the statement (`LINE 1: ... PASSWORD '<pw>'`) in message, stderr and stdout;
// the worker rethrows a NEW error built from redacted stderr only. No docker: a fake execFileSync.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createAccountVia, narrowAccountSql, redactPassword } from '../lib/target-db.mjs';

const PW = 'Zm9vYmFyYmF6cXV4MTIzNDU';
const CONTAINER = 'ipass-ui-bs-tdb-0a1b2c3d';
const ACCOUNT = 'f3_etl_a_0a1b2c3d';

test('redactPassword: every occurrence, plain and quoted, becomes [redacted]', () => {
  const text = `ERROR: boom\nLINE 1: CREATE ROLE x PASSWORD '${PW}'\n  ${PW} and again ${PW}`;
  const out = redactPassword(text, PW);
  assert.equal(out.includes(PW), false);
  assert.equal(out.split('[redacted]').length - 1, 3);
});

test('redactPassword: regex-special characters in the password are literal, not a pattern', () => {
  assert.equal(redactPassword('a.b*c+d a-b*c+d', 'a.b*c+d'), '[redacted] a-b*c+d');
  assert.equal(redactPassword('x(y)[z]$^|?\\ end', '(y)[z]$^|?\\'), 'x[redacted] end');
});

test('redactPassword: the base64 (+ /) spelling of a base64url password is redacted too', () => {
  const pw = 'ab-cd_ef0123456789XYZ';
  assert.equal(redactPassword('see ab+cd/ef0123456789XYZ here', pw), 'see [redacted] here');
});

test("redactPassword: a PASSWORD '...' literal is redacted even when psql truncated it (no full password to match)", () => {
  assert.equal(redactPassword("LINE 1: ...NOBYPASSRLS PASSWORD 'Zm9vYmFyYmF6cXV4", PW), 'LINE 1: ...NOBYPASSRLS PASSWORD [redacted]');
  assert.doesNotMatch(redactPassword("PASSWORD 'whatever1234567890'; next", PW), /whatever/);
});

test('redactPassword: empty, missing and non-string input are safe', () => {
  assert.equal(redactPassword('', PW), '');
  assert.equal(redactPassword(undefined, PW), '');
  assert.equal(redactPassword(null, PW), '');
  assert.equal(redactPassword('plain text', ''), 'plain text');
  assert.equal(redactPassword('plain text', undefined), 'plain text');
  assert.equal(redactPassword(Buffer.from(`x ${PW} y`), PW), 'x [redacted] y');
});

/** What execFileSync really throws: message + stderr + stdout + cmd/input-derived text all carry the statement. */
function failingExec() {
  return (_cmd, _args, options) => {
    const echo = `psql: error: ERROR:  role "${ACCOUNT}" already exists\nLINE 1: CREATE ROLE ${ACCOUNT} LOGIN ... PASSWORD '${PW}'\n        ^\n`;
    const err = new Error(`Command failed: docker exec -i ${CONTAINER} psql\n${echo}`);
    err.status = 3;
    err.stderr = echo;
    err.stdout = options.input;
    err.cmd = 'docker exec';
    err.input = options.input;
    throw err;
  };
}

test('createAccountVia: a failing psql rethrows a NEW error with container, account, status and redacted stderr - no password anywhere', () => {
  let thrown;
  try { createAccountVia(failingExec(), { container: CONTAINER, account: ACCOUNT, password: PW }); } catch (e) { thrown = e; }
  assert.ok(thrown instanceof Error, 'it throws');
  assert.match(thrown.message, new RegExp(CONTAINER));
  assert.match(thrown.message, new RegExp(ACCOUNT));
  assert.match(thrown.message, /status 3/);
  assert.match(thrown.message, /\[redacted\]/);
  assert.match(thrown.message, /already exists/, 'the useful psql diagnosis stays');
  assert.equal(thrown.cause, undefined, 'the original error (unredacted) is not attached');
  for (const value of [thrown.message, thrown.stack, JSON.stringify(thrown, Object.getOwnPropertyNames(thrown))]) {
    assert.equal(String(value).includes(PW), false);
  }
  for (const key of Object.getOwnPropertyNames(thrown)) assert.equal(/input|stdout|stderr|cmd/.test(key), false, `no ${key} property`);
});

test('createAccountVia: a spawn failure without stderr (ENOENT, timeout) still reports a safe message', () => {
  const exec = () => { const e = new Error('spawnSync docker ENOENT'); e.code = 'ENOENT'; throw e; };
  assert.throws(() => createAccountVia(exec, { container: CONTAINER, account: ACCOUNT, password: PW }), (e) => /ENOENT/.test(e.message) && /f3_etl_a_0a1b2c3d/.test(e.message) && !e.message.includes(PW));
});

test('createAccountVia: on success it sends the narrow statements on stdin to the owner psql of the booted sidecar', () => {
  const calls = [];
  createAccountVia((cmd, args, options) => { calls.push({ cmd, args, options }); return ''; }, { container: CONTAINER, account: ACCOUNT, password: PW });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].cmd, 'docker');
  assert.deepEqual(calls[0].args.slice(0, 4), ['exec', '-i', CONTAINER, 'psql']);
  assert.equal(calls[0].args.some((a) => a.includes(PW)), false, 'the password never rides in argv');
  assert.equal(calls[0].options.input, `${narrowAccountSql({ account: ACCOUNT, password: PW }).join(';\n')};\n`);
  assert.equal(calls[0].options.timeout, 30000);
});
