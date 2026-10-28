// Row 310 lane A seam (arjun-fullstack): a no-session answer sends the browser to the console's own
// sign-in state (#/login, 10-screens §2 / 00-portal-ia §4), NOT straight to Keycloak from the API client. Since
// row 571 that state goes on to the login entry point (LOGIN_ENTRY) by itself, once (login-redirect.test.mjs).
// Runs under node --test with the ts-resolve hook (npm test); window/document/fetch are stubbed.

import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

const nav = { hash: '#/versions', assigned: [] };
globalThis.window = {
  location: {
    get hash() { return nav.hash; },
    set hash(v) { nav.hash = v.startsWith('#') ? v : `#${v}`; nav.assigned.push(`hash:${nav.hash}`); },
    assign(url) { nav.assigned.push(`assign:${url}`); }
  }
};
globalThis.document = { cookie: '' };
let status = 401;
globalThis.fetch = async () => new Response('{}', { status, headers: { 'content-type': 'application/json' } });

const { consoleFetch, LOGIN_ENTRY, LOGIN_ROUTE } = await import('../../src/api/client.ts');

beforeEach(() => { nav.hash = '#/versions'; nav.assigned = []; status = 401; });

test('the sign-in route and the SSO entry point are the designed values', () => {
  assert.equal(LOGIN_ROUTE, '#/login');
  assert.equal(LOGIN_ENTRY, '/oauth2/authorization/keycloak');
});

test('a 401 (no session) goes to the in-app #/login card, never straight to Keycloak', async () => {
  const answer = await consoleFetch('/api/v1/admin/flows');
  assert.equal(answer.status, 401, 'the caller still sees the 401');
  assert.equal(nav.hash, '#/login');
  assert.deepEqual(nav.assigned, ['hash:#/login'], 'one in-app navigation, no full-page jump to the entry point');
});

test('a 401 while already on #/login does not navigate again (no loop, no reload)', async () => {
  nav.hash = '#/login';
  await consoleFetch('/api/v1/me');
  assert.deepEqual(nav.assigned, []);
});

test('a signed-in answer does not navigate', async () => {
  status = 200;
  await consoleFetch('/api/v1/me');
  assert.deepEqual(nav.assigned, []);
  assert.equal(nav.hash, '#/versions');
});
