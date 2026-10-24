// T211: recordTraffic().settle() must never hang. S3-A6 sat at its 180 s timeout because one response.text() never
// resolved (a body cut off by the next navigation), so Promise.all(pending) never did. No Docker, no browser: a fake page.
import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { recordTraffic } from '../lib.mjs';

function fakeResponse({ url, body, type = 'application/json' }) {
  const request = { url: () => url };
  return {
    request: () => request,
    url: () => url,
    status: () => 200,
    headers: () => ({ 'content-type': type }),
    headersArray: async () => [{ name: 'Set-Cookie', value: 'a=b' }],
    text: () => body
  };
}

function fakePage() {
  const page = new EventEmitter();
  return page;
}

test('settle returns although one body never resolves, and names that body as unread', async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 50 });
  const done = fakeResponse({ url: 'http://c/api/me', body: Promise.resolve('{"ok":true}') });
  const stuck = fakeResponse({ url: 'http://c/api/flows', body: new Promise(() => {}) });
  page.emit('response', done);
  page.emit('response', stuck);
  const started = Date.now();
  await traffic.settle();
  assert.ok(Date.now() - started < 2000, 'settle is bounded');
  assert.equal(traffic.responses.find((r) => r.url.endsWith('/me')).body, '{"ok":true}', 'a finished body is still scanned');
  assert.deepEqual(traffic.unreadBodies().map((r) => r.url), ['http://c/api/flows'], 'the stuck body is reported, never skipped silently');
});

test('a body cut off by a navigation (request failed) is not an unread body; a finished response with no body is', async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 50 });
  const aborted = fakeResponse({ url: 'http://c/api/flows', body: new Promise(() => {}) });
  const silent = fakeResponse({ url: 'http://c/api/systems', body: new Promise(() => {}) });
  page.emit('response', aborted);
  page.emit('response', silent);
  page.emit('requestfailed', aborted.request());
  await traffic.settle();
  assert.deepEqual(traffic.unreadBodies().map((r) => r.url), ['http://c/api/systems']);
});

test('a rejecting body read is tolerated as before and a response with a set-cookie header is recorded', async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 50 });
  page.emit('response', fakeResponse({ url: 'http://c/api/x', body: Promise.reject(new Error('gone')) }));
  await traffic.settle();
  assert.deepEqual(traffic.responses[0].setCookies, ['a=b']);
});
