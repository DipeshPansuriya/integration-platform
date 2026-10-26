// Row 278 close-E7, T94 (T-162 gap 3) - the console proxy with NO harness sessions, FAILING FIRST.
//
// The trial serves the built console with `startConsole(backend, {})` so no browser request can
// borrow a harness login. A request that still carries the X-Harness-Login marker must then be
// refused (403) - never crash the console process (the proxy's 'proxyReq' listener runs on the
// socket event, so a throw there is an uncaught exception) and never reach the backend. An
// ordinary request is still proxied. No docker: the backend here is a local stub.
//
// Uses the console's fixed port 4173 (strictPort): run it when no screens suite or trial is up.

import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { startConsole } from '../screens/console-server.mjs';

test('a marker request without a harness session is refused and the console keeps serving', async () => {
  const seen = [];
  const backend = http.createServer((req, res) => {
    seen.push(req.url);
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, path: req.url }));
  });
  await new Promise((resolve) => backend.listen(0, '127.0.0.1', resolve));
  const backendUrl = `http://127.0.0.1:${backend.address().port}`;

  const crashes = [];
  const onCrash = (error) => crashes.push(error);
  process.on('uncaughtException', onCrash);
  const consoleServer = await startConsole(backendUrl, {});
  try {
    const refused = await fetch(`${consoleServer.url}/api/v1/me`, { headers: { 'X-Harness-Login': 'default' } });
    assert.equal(refused.status, 403);
    await refused.arrayBuffer();
    await new Promise((resolve) => setTimeout(resolve, 200));
    assert.deepEqual(crashes.map((e) => e.message), [], 'the console process must not crash');
    assert.deepEqual(seen, [], 'a marker request never reaches the backend');

    const ordinary = await fetch(`${consoleServer.url}/api/v1/me`);
    assert.equal(ordinary.status, 200);
    assert.deepEqual(await ordinary.json(), { ok: true, path: '/api/v1/me' });
  } finally {
    process.off('uncaughtException', onCrash);
    await consoleServer.close();
    await new Promise((resolve) => backend.close(resolve));
  }
});
