// T171 (row 372) S3-A5b: the conditions karan attached when he closed B33 (a tampered SESSION cookie, fixed on main 03a055c1).
//   (a) a tampered cookie on /logout, /login/oauth2/code/keycloak and /oauth2/authorization/keycloak: 302 or 401, never 500
//   (b) a tampered cookie plus a valid bearer token on the API: 200 (the bearer authenticates, the cookie is ignored)
//   (c) two canonical-shaped SESSION cookies in one request: 401, no 500, the pool still healthy afterwards
//   (d) an uppercase-hex canonical id: 401
// Plain HTTP at the backend (raw Cookie headers, redirects not followed), so the status is the backend's own. Every case
// prints the raw status codes. After each case a genuine login plus GET /api/v1/me proves the pool still serves and the
// application log shows no pool exhaustion. Against the REAL backend, Keycloak and database (global-setup.mjs); test code only.

import crypto from 'node:crypto';
import { expect, test } from '@playwright/test';
import { appLog, freshLogin, api, issuer, sessionCookie, sessionIdOf, tamperedVariants } from './lib.mjs';

const POOL_ERROR = 'Could not open JDBC Connection';

/** One raw request at the backend: the status and Location only, redirects not followed, `cookie` sent as given. */
async function raw(method, path, { cookie, headers = {} } = {}) {
  const sent = { ...headers };
  if (cookie) sent.cookie = cookie;
  try {
    const answer = await fetch(`${process.env.SEC_BACKEND}${path}`, { method, headers: sent, redirect: 'manual', signal: AbortSignal.timeout(20000) });
    const text = await answer.text();
    return { status: answer.status, location: answer.headers.get('location'), text };
  } catch (e) {
    return { status: 0, location: null, text: `no answer: ${e.name}` };
  }
}

const encode = (id) => Buffer.from(id).toString('base64');

/** After the abuse: a genuine login and GET /api/v1/me answer 200, and no pool error reached the application log. */
async function expectPoolHealthy(browser, poolErrorsBefore, when) {
  const pool = await freshLogin(browser);
  const me = await api(pool.page, 'GET', '/api/v1/me');
  expect(me.status, `pool check ${when}: a genuine login then GET /api/v1/me returns 200`).toBe(200);
  expect(me.data.tenantCode).toBe(process.env.CONSOLE_TENANT_CODE);
  await pool.context.close();
  expect((await appLog(POOL_ERROR)).length, `pool check ${when}: no pool exhaustion errors in the application log`).toBe(poolErrorsBefore.length);
}

async function bearerToken() {
  const answer = await fetch(`${issuer()}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'client_credentials', client_id: process.env.SEC_BEARER_CLIENT, client_secret: process.env.SEC_BEARER_SECRET })
  });
  const json = await answer.json().catch(() => ({}));
  expect(answer.status, `control: the service client gets a token (answer: ${json.error ?? ''} ${json.error_description ?? ''})`).toBe(200);
  return json.access_token;
}

test.describe('S3-A5b B33 conditions (tampered session cookie)', () => {
  test('S3-A5b-a tampered cookie on /logout, the code callback and the login entry: 302 or 401, never 500', async ({ browser }) => {
    const genuine = await freshLogin(browser);
    const value = (await sessionCookie(genuine.context)).value;
    const poolErrorsBefore = await appLog(POOL_ERROR);
    const requests = [
      ['POST /logout', (cookie) => raw('POST', '/logout', { cookie: `${cookie}; XSRF-TOKEN=probe`, headers: { 'x-xsrf-token': 'probe' } })],
      ['GET /login/oauth2/code/keycloak', (cookie) => raw('GET', '/login/oauth2/code/keycloak?code=probe&state=probe', { cookie })],
      ['GET /oauth2/authorization/keycloak', (cookie) => raw('GET', '/oauth2/authorization/keycloak', { cookie })]
    ];
    // control: the same three requests with no cookie at all answer something other than 5xx, so a refusal below is the cookie's doing
    const codes = {};
    for (const [label, send] of requests) {
      const control = await send('SESSION=');
      codes[`${label} | no usable cookie (control)`] = control.status;
      expect.soft(control.status, `${label}: control without a usable cookie`).toBeLessThan(500);
    }
    for (const [variant, tampered] of Object.entries(tamperedVariants(value))) {
      for (const [label, send] of requests) {
        const answer = await send(`SESSION=${tampered}`);
        codes[`${label} | ${variant}`] = answer.status;
        expect.soft([302, 401], `${label} with "${variant}": ${answer.status} ${answer.text.slice(0, 80).replace(/\s+/g, ' ')}`).toContain(answer.status);
      }
    }
    console.log(`S3-A5b-a raw status codes:\n${Object.entries(codes).map(([key, status]) => `  ${status}  ${key}`).join('\n')}`);
    await genuine.context.close();
    await expectPoolHealthy(browser, poolErrorsBefore, 'after the tampered cookies on the session endpoints');
  });

  test('S3-A5b-b tampered cookie plus a valid bearer token on the API: 200', async ({ browser }) => {
    const genuine = await freshLogin(browser);
    const value = (await sessionCookie(genuine.context)).value;
    const poolErrorsBefore = await appLog(POOL_ERROR);
    const token = await bearerToken();
    const authorization = { authorization: `Bearer ${token}` };
    const codes = {};

    const alone = await raw('GET', '/api/v1/me', { headers: authorization });
    codes['bearer alone (control)'] = alone.status;
    expect(alone.status, 'control: the bearer token alone is accepted').toBe(200);
    const cookieAlone = await raw('GET', '/api/v1/me', { cookie: `SESSION=${tamperedVariants(value)['first character changed']}` });
    codes['tampered cookie alone (control)'] = cookieAlone.status;
    expect(cookieAlone.status, 'control: the tampered cookie alone is refused').toBe(401);

    for (const [variant, tampered] of Object.entries(tamperedVariants(value))) {
      const answer = await raw('GET', '/api/v1/me', { cookie: `SESSION=${tampered}`, headers: authorization });
      codes[variant] = answer.status;
      expect.soft(answer.status, `bearer + "${variant}": ${answer.text.slice(0, 80).replace(/\s+/g, ' ')}`).toBe(200);
    }
    console.log(`S3-A5b-b raw status codes (bearer + tampered cookie):\n${Object.entries(codes).map(([key, status]) => `  ${status}  ${key}`).join('\n')}`);
    await genuine.context.close();
    await expectPoolHealthy(browser, poolErrorsBefore, 'after bearer + tampered cookies');
  });

  test('S3-A5b-c two canonical-shaped SESSION cookies in one request: 401, no 500, pool healthy afterwards', async ({ browser }) => {
    const genuine = await freshLogin(browser);
    const value = (await sessionCookie(genuine.context)).value;
    const poolErrorsBefore = await appLog(POOL_ERROR);
    const codes = {};

    // twice the pool's size (10) in requests, so one leaked connection per request would show
    const statuses = [];
    for (let i = 0; i < 25; i++) {
      const answer = await raw('GET', '/api/v1/me', { cookie: `SESSION=${encode(crypto.randomUUID())}; SESSION=${encode(crypto.randomUUID())}` });
      statuses.push(answer.status);
      expect.soft(answer.status, `two unknown canonical ids, request ${i + 1}: ${answer.text.slice(0, 80).replace(/\s+/g, ' ')}`).toBe(401);
    }
    codes['two unknown canonical ids (25 requests)'] = [...new Set(statuses)].join(',');

    // the same on a path that is not the API
    const login = await raw('GET', '/oauth2/authorization/keycloak', { cookie: `SESSION=${encode(crypto.randomUUID())}; SESSION=${encode(crypto.randomUUID())}` });
    codes['GET /oauth2/authorization/keycloak, two unknown ids'] = login.status;
    expect.soft(login.status, 'login entry with two unknown canonical ids is not a 500').toBeLessThan(500);

    // information, not asserted: which cookie Spring takes when one of the two is a live session (shadowing order)
    const unknownFirst = await raw('GET', '/api/v1/me', { cookie: `SESSION=${encode(crypto.randomUUID())}; SESSION=${value}` });
    const genuineFirst = await raw('GET', '/api/v1/me', { cookie: `SESSION=${value}; SESSION=${encode(crypto.randomUUID())}` });
    codes['info: unknown then genuine'] = unknownFirst.status;
    codes['info: genuine then unknown'] = genuineFirst.status;
    console.log(`S3-A5b-c raw status codes:\n${Object.entries(codes).map(([key, status]) => `  ${status}  ${key}`).join('\n')}`);
    expect(statuses.every((status) => status < 500), 'no 500 anywhere').toBe(true);
    await genuine.context.close();
    await expectPoolHealthy(browser, poolErrorsBefore, 'after 25 double-cookie requests');
  });

  test('S3-A5b-d uppercase-hex canonical session id: 401', async ({ browser }) => {
    const genuine = await freshLogin(browser);
    const value = (await sessionCookie(genuine.context)).value;
    const id = sessionIdOf(value);
    const poolErrorsBefore = await appLog(POOL_ERROR);
    const codes = {};

    const control = await raw('GET', '/api/v1/me', { cookie: `SESSION=${value}` });
    codes['genuine lowercase id (control)'] = control.status;
    expect(control.status, 'control: the genuine cookie is accepted').toBe(200);
    expect(id, 'control: the live id is lowercase-canonical, so uppercasing it changes it').not.toBe(id.toUpperCase());

    const upperLive = await raw('GET', '/api/v1/me', { cookie: `SESSION=${encode(id.toUpperCase())}` });
    codes['live id, uppercased'] = upperLive.status;
    expect.soft(upperLive.status, `the live id uppercased: ${upperLive.text.slice(0, 80).replace(/\s+/g, ' ')}`).toBe(401);
    const upperRandom = await raw('GET', '/api/v1/me', { cookie: `SESSION=${encode(crypto.randomUUID().toUpperCase())}` });
    codes['random canonical id, uppercased'] = upperRandom.status;
    expect.soft(upperRandom.status, `a random uppercase canonical id: ${upperRandom.text.slice(0, 80).replace(/\s+/g, ' ')}`).toBe(401);
    console.log(`S3-A5b-d raw status codes:\n${Object.entries(codes).map(([key, status]) => `  ${status}  ${key}`).join('\n')}`);
    await genuine.context.close();
    await expectPoolHealthy(browser, poolErrorsBefore, 'after the uppercase ids');
  });
});
