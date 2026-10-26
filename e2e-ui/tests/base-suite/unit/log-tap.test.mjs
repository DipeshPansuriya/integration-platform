// T131 (row 323, F21 BS-C, BS-09) - the app-log tap, FAILING FIRST. No docker: a plain node http server.
//
// The harness keeps the app's stdout/stderr in the RUNNER's memory (scripts/boot-backend.mjs:535-537,
// logDump() :692-694); Playwright workers are other processes. The tap serves that buffer to them on the
// loopback only, read-only, for the run's lifetime - never written to a file (a log line may carry what
// R07 keeps out of files). A worker reads the slice from a character offset, so BS-09 reads exactly the
// lines its own POST produced.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { startLogTap } from '../support/log-tap.mjs';

test('the tap serves the size and the text from an offset, on 127.0.0.1 only', async () => {
  let text = 'line 1\n';
  const tap = await startLogTap(() => text);
  try {
    assert.match(tap.url, /^http:\/\/127\.0\.0\.1:\d+$/);
    const size = await (await fetch(`${tap.url}/size`)).json();
    assert.deepEqual(size, { size: 7 });
    text += 'line 2\n';
    assert.equal(await (await fetch(`${tap.url}/log?from=7`)).text(), 'line 2\n');
    assert.equal(await (await fetch(`${tap.url}/log?from=0`)).text(), 'line 1\nline 2\n');
  } finally {
    await tap.close();
  }
});

test('the tap is read-only: anything but GET /size or GET /log is refused', async () => {
  const tap = await startLogTap(() => 'x');
  try {
    assert.equal((await fetch(`${tap.url}/log`, { method: 'POST', body: 'y' })).status, 405);
    assert.equal((await fetch(`${tap.url}/other`)).status, 404);
    assert.equal((await fetch(`${tap.url}/log?from=-1`)).status, 400);
    assert.equal((await fetch(`${tap.url}/log?from=abc`)).status, 400);
  } finally {
    await tap.close();
  }
});
