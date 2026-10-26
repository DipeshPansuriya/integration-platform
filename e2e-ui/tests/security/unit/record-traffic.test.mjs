// T211: recordTraffic() must never hang AND must never turn an unread response into "no token found". S3-A6 sat at its 180 s timeout
// because one response.text() never resolved (a body cut off by the next navigation). No Docker, no browser: a fake page.
import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { recordTraffic, expectNoTokenInTraffic } from '../lib.mjs';

process.env.CONSOLE_URL ??= 'http://127.0.0.1:4173';
const TIMEOUT = { timeout: 5000 };
const never = () => new Promise(() => {});
const later = (ms, value) => new Promise((resolve) => setTimeout(() => resolve(value), ms));

function fakeResponse({ url, body, status = 200, type = 'application/json', headers }) {
  const request = { url: () => url };
  return {
    request: () => request,
    url: () => url,
    status: () => status,
    headers: () => ({ 'content-type': type }),
    headersArray: headers ?? (async () => [{ name: 'Set-Cookie', value: 'a=b' }]),
    text: () => body
  };
}

function fakePage(extra = {}) {
  return Object.assign(new EventEmitter(), extra);
}

test('settle returns although one body never resolves, and names that body as unread', TIMEOUT, async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 50 });
  page.emit('response', fakeResponse({ url: 'http://c/api/me', body: Promise.resolve('{"ok":true}') }));
  page.emit('response', fakeResponse({ url: 'http://c/api/flows', body: never() }));
  const started = Date.now();
  await traffic.settle();
  assert.ok(Date.now() - started < 2000, 'settle is bounded');
  assert.equal(traffic.responses.find((r) => r.url.endsWith('/me')).body, '{"ok":true}', 'a finished body is still scanned');
  assert.deepEqual(traffic.unreadBodies().map((r) => [r.url, r.what, r.state]), [['http://c/api/flows', 'body', 'timeout']]);
});

test('a body cut off by a navigation (request failed) is not unread; a timeout without a failed request is', TIMEOUT, async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 50 });
  const aborted = fakeResponse({ url: 'http://c/api/flows', body: never() });
  page.emit('response', aborted);
  page.emit('response', fakeResponse({ url: 'http://c/api/systems', body: never() }));
  page.emit('requestfailed', aborted.request());
  await traffic.settle();
  assert.deepEqual(traffic.unreadBodies().map((r) => r.url), ['http://c/api/systems']);
});

test('a REJECTED body read (page closed, protocol error) is counted as unread with its reason', TIMEOUT, async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 50 });
  const rejected = Promise.reject(new Error('Target page, context or browser has been closed\n  at stack'));
  rejected.catch(() => {});
  page.emit('response', fakeResponse({ url: 'http://c/login/oauth2/code/keycloak', body: rejected, type: 'text/html' }));
  await traffic.settle();
  assert.deepEqual(traffic.unreadBodies().map((r) => [r.url, r.state, r.reason]), [['http://c/login/oauth2/code/keycloak', 'rejected', 'Target page, context or browser has been closed']]);
});

test('a redirect has no body to read: its rejected read is not unread (its Location header is scanned elsewhere)', TIMEOUT, async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 50 });
  const rejected = Promise.reject(new Error('Response body is unavailable for redirect responses'));
  rejected.catch(() => {});
  page.emit('response', fakeResponse({ url: 'http://c/login/oauth2/code/keycloak', status: 302, body: rejected, type: 'text/html' }));
  await traffic.settle();
  assert.deepEqual(traffic.unreadBodies(), []);
});

test('a header read that times out or rejects is surfaced, not silent', TIMEOUT, async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 50 });
  page.emit('response', fakeResponse({ url: 'http://c/a', body: Promise.resolve('x'), headers: never }));
  page.emit('response', fakeResponse({ url: 'http://c/b', body: Promise.resolve('x'), headers: async () => { throw new Error('gone'); } }));
  await traffic.settle();
  assert.deepEqual(traffic.unreadBodies().map((r) => [r.url, r.what, r.state]), [['http://c/a', 'headers', 'timeout'], ['http://c/b', 'headers', 'rejected']]);
});

test('a read still pending at check time is unread, so a missing settle() cannot pass a scan', TIMEOUT, async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 5000 });
  page.emit('response', fakeResponse({ url: 'http://c/api/slow', body: later(300, '{}') }));
  assert.deepEqual(traffic.unreadBodies().map((r) => r.state), ['pending', 'pending'], 'headers read and body read');
  await traffic.settle();
  assert.deepEqual(traffic.unreadBodies(), []);
});

function fakeRequest(url) {
  return { url: () => url, method: () => 'GET', headers: () => ({}) };
}

test('quiesce waits for an in-flight request and its slow body, so a navigation after it cuts nothing off', TIMEOUT, async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 5000, quietMs: 30 });
  const request = fakeRequest('http://c/api/v1/admin/flows');
  page.emit('request', request);
  setTimeout(() => {
    page.emit('response', Object.assign(fakeResponse({ url: 'http://c/api/v1/admin/flows', body: later(100, '{"flows":[]}') }), { request: () => request }));
    setTimeout(() => page.emit('requestfinished', request), 120);
  }, 60);
  await traffic.quiesce();
  assert.equal(traffic.responses.length, 1, 'the response that arrived while quiescing was waited for');
  assert.equal(traffic.responses[0].body, '{"flows":[]}');
  assert.deepEqual(traffic.unreadBodies(), []);
});

test('quiesce is bounded when a request never finishes, and that read is then reported unread', TIMEOUT, async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 50, idleTimeoutMs: 100, quietMs: 30 });
  const request = fakeRequest('http://c/api/v1/admin/flows');
  page.emit('request', request);
  page.emit('response', Object.assign(fakeResponse({ url: 'http://c/api/v1/admin/flows', body: never() }), { request: () => request }));
  const started = Date.now();
  await traffic.quiesce();
  assert.ok(Date.now() - started < 2000);
  assert.deepEqual(traffic.unreadBodies().map((r) => [r.what, r.state]), [['body', 'timeout']]);
});

test('settle also waits for a response that arrives while it is waiting', TIMEOUT, async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 5000 });
  page.emit('response', fakeResponse({ url: 'http://c/api/a', body: later(60, '{}') }));
  setTimeout(() => page.emit('response', fakeResponse({ url: 'http://c/api/b', body: later(60, '{}') })), 30);
  await traffic.settle();
  assert.equal(traffic.responses.length, 2);
  assert.deepEqual(traffic.unreadBodies(), []);
});

test('expectNoTokenInTraffic fails on an unread response, and only an explicit annotated tolerance lets one pass', TIMEOUT, async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 50 });
  page.emit('response', fakeResponse({ url: 'http://127.0.0.1:4173/api/v1/admin/flows', body: never() }));
  await traffic.settle();
  assert.throws(() => expectNoTokenInTraffic(traffic), /every response was read and scanned/, 'unread /api/v1 body fails by default');
  assert.doesNotThrow(() => expectNoTokenInTraffic(traffic, [], { tolerateUnread: [{ pattern: /^\/api\/v1\/admin\/flows$/, reason: 'test: cut off on purpose' }] }));
  assert.throws(() => expectNoTokenInTraffic(traffic, [], { tolerateUnread: [{ pattern: /^\/other$/, reason: 'x' }] }), /every response was read/);
});

test('a token in a scanned console body still fails the scan', TIMEOUT, async () => {
  const page = fakePage();
  const traffic = recordTraffic(page, { bodyTimeoutMs: 50 });
  page.emit('response', fakeResponse({ url: 'http://127.0.0.1:4173/api/v1/x', body: Promise.resolve('{"access_token":"x"}') }));
  await traffic.settle();
  assert.throws(() => expectNoTokenInTraffic(traffic), /no token reaches the browser/);
});
