// T171 security gate - helpers shared by the browser cases (S1-A1..A3, S3-A1..A6).
// Everything here drives the REAL console, backend and Keycloak that the global setup booted; nothing is mocked.

import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { expect } from '@playwright/test';
import { browserLogin } from '../screens/keycloak-login.mjs';
import { cookieJar, send } from '../../scripts/keycloak-harness.mjs';
import { consoleUrl, loginUser } from '../screens/boot-state.mjs';

export { consoleUrl };

/** A compact JWT (three base64url parts, header starting `eyJ`) - what an access, refresh or id token looks like. */
export const JWT_PATTERN = /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*/;
/** The names a token travels under in a URL, a form or a JSON body. */
export const TOKEN_NAME_PATTERN = /(?:^|[^A-Za-z0-9])(?:access_token|refresh_token|id_token)(?:[^A-Za-z0-9]|$)/i;

/** What a scan found in `text`, as a short list of labels (never the value itself). */
export function tokenFindings(text) {
  const findings = [];
  if (JWT_PATTERN.test(text)) findings.push('a JWT-shaped value');
  if (TOKEN_NAME_PATTERN.test(text)) findings.push('an access/refresh/id token field name');
  return findings;
}

/** The scanner must see what it is built to see, or "nothing found" proves nothing. */
export function assertScannerSees() {
  const sample = `${Buffer.from('{"alg":"RS256"}').toString('base64url')}.${Buffer.from('{"sub":"x","aud":"y"}').toString('base64url')}.c2lnbmF0dXJl`;
  expect(tokenFindings(sample), 'the scanner recognises a JWT').toContain('a JWT-shaped value');
  expect(tokenFindings('{"access_token":"x"}'), 'the scanner recognises a token field').toContain('an access/refresh/id token field name');
  expect(tokenFindings('{"tenant":"lane-d","flows":[]}'), 'the scanner stays quiet on ordinary data').toEqual([]);
}

export function hostOf(url) {
  return new URL(url).hostname;
}

/** The console/backend host the browser holds cookies for (127.0.0.1); Keycloak is `localhost`, a different site. */
export function consoleHost() {
  return hostOf(consoleUrl());
}

export function otherUser() {
  return { username: process.env.SEC_OTHER_USER, password: process.env.SEC_OTHER_PASSWORD };
}

/** A fresh, signed-out browser context logged in through the realm's real login form. */
export async function freshLogin(browser, user = loginUser()) {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await browserLogin(page, consoleUrl(), user);
  await expect(page.locator('[data-session-tenant]')).toBeVisible();
  return { context, page };
}

/** The console-origin cookies of a context (the Keycloak cookies live on `localhost` and are the IdP's own). */
export async function consoleCookies(context) {
  return (await context.cookies()).filter((cookie) => cookie.domain === consoleHost());
}

export async function sessionCookie(context) {
  return (await consoleCookies(context)).find((cookie) => cookie.name === 'SESSION');
}

/** A cookie the way Spring's session cookie is set in the browser. */
export function sessionCookieFor(value) {
  return { name: 'SESSION', value, domain: consoleHost(), path: '/', httpOnly: true, secure: true, sameSite: 'Lax' };
}

/** The Spring Session id behind a SESSION cookie value (the cookie is the id, base64-encoded). */
export function sessionIdOf(cookieValue) {
  return Buffer.from(cookieValue, 'base64').toString('utf8');
}

/**
 * One API call from inside the page (the browser sends its own cookies, as the console does).
 * `csrf`: 'valid' = the XSRF-TOKEN cookie's value in X-XSRF-TOKEN, 'none' = no header, 'forged' = `token`.
 */
export async function api(page, method, apiPath, { body, csrf = 'valid', token } = {}) {
  const answer = await page.evaluate(
    async ({ method, apiPath, body, csrf, token }) => {
      const pair = document.cookie.split('; ').filter((entry) => entry.startsWith('XSRF-TOKEN=')).pop();
      const real = pair ? decodeURIComponent(pair.slice('XSRF-TOKEN='.length)) : null;
      const headers = {};
      if (body !== undefined) headers['content-type'] = 'application/json';
      const sent = csrf === 'valid' ? real : csrf === 'forged' ? token : null;
      if (sent) headers['X-XSRF-TOKEN'] = sent;
      try {
        const response = await fetch(apiPath, { method, headers, credentials: 'include', body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) });
        return { status: response.status, text: await response.text() };
      } catch (e) {
        return { status: 0, text: `no answer: ${e.name}` }; // a request that hangs is a finding, not a stalled test
      }
    },
    { method, apiPath, body, csrf, token }
  );
  let data = null;
  try {
    data = JSON.parse(answer.text);
  } catch {
    // not JSON
  }
  return { status: answer.status, text: answer.text, data };
}

/** Opens the console so a page-origin fetch carries the context's cookies. */
export async function atConsole(page) {
  await page.goto(`${consoleUrl()}/`);
}

export function psql(sql) {
  return execFileSync('docker', ['exec', process.env.SEC_PG, 'psql', '-U', 'owner', '-d', 'platform', '-t', '-A', '-c', sql], { encoding: 'utf8' }).trim();
}

/**
 * Every request, redirect chain, response header and (text) response body the page sees, for token scans.
 * T211: a body read can NEVER resolve (a response cut off by the next navigation), and settle() waited for all of them, so
 * S3-A6 hung to its 180 s timeout. Each read is now bounded by `bodyTimeoutMs`; a body that did not arrive is listed by
 * unreadBodies() unless its request failed (cut off by a navigation = the page never received it), so a response that
 * finished and was not scanned still fails the case instead of passing as "no token found".
 */
export function recordTraffic(page, { bodyTimeoutMs = 10000 } = {}) {
  const traffic = { requests: [], responses: [], navigations: [], pending: [] };
  const unread = [];
  const failed = new Set();
  const bounded = (work, onTimeout) => new Promise((resolve) => {
    const timer = setTimeout(() => { onTimeout(); resolve(); }, bodyTimeoutMs);
    work.catch(() => {}).then(() => { clearTimeout(timer); resolve(); });
  });
  page.on('request', (request) => {
    traffic.requests.push({ url: request.url(), method: request.method(), referer: request.headers().referer ?? null });
  });
  page.on('requestfailed', (request) => failed.add(request));
  page.on('framenavigated', (frame) => traffic.navigations.push(frame.url()));
  page.on('response', (response) => {
    const headers = response.headers();
    const entry = { url: response.url(), status: response.status(), headers, setCookies: [], body: null };
    traffic.responses.push(entry);
    // Playwright keeps Set-Cookie out of headers(); the raw header list has it
    traffic.pending.push(bounded(response.headersArray().then((list) => { entry.setCookies = list.filter((h) => h.name.toLowerCase() === 'set-cookie').map((h) => h.value); }), () => {}));
    const type = headers['content-type'] ?? '';
    // JS bundles and styles are the console's own code, not data it was handed; fonts and images carry no token.
    if (/json|html|text\/plain|x-www-form-urlencoded/.test(type) && !/javascript|css/.test(type)) {
      traffic.pending.push(bounded(response.text().then((text) => { entry.body = text; }), () => unread.push({ entry, request: response.request() })));
    }
  });
  traffic.settle = () => Promise.all(traffic.pending);
  /** Responses whose body should have been scanned and was not (not counting requests the browser itself cut off). */
  traffic.unreadBodies = () => unread.filter(({ entry, request }) => entry.body === null && !failed.has(request)).map(({ entry }) => ({ url: entry.url, status: entry.status }));
  return traffic;
}

/** Fails when any URL, Referer, Location header or console/backend-origin body shows a token. */
export function expectNoTokenInTraffic(traffic, extraUrls = []) {
  const consoleHostName = consoleHost();
  const findings = [];
  for (const url of [...traffic.navigations, ...extraUrls, ...traffic.requests.map((request) => request.url)]) {
    for (const found of tokenFindings(decodeURIComponent(url))) findings.push(`URL ${new URL(url).origin}${new URL(url).pathname}: ${found}`);
  }
  for (const request of traffic.requests) {
    if (request.referer) for (const found of tokenFindings(decodeURIComponent(request.referer))) findings.push(`Referer of ${new URL(request.url).pathname}: ${found}`);
  }
  for (const response of traffic.responses) {
    const location = response.headers.location;
    if (location) for (const found of tokenFindings(decodeURIComponent(location))) findings.push(`Location of ${new URL(response.url).pathname}: ${found}`);
    if (response.body && hostOf(response.url) === consoleHostName) {
      for (const found of tokenFindings(response.body)) findings.push(`body of ${new URL(response.url).pathname}: ${found}`);
    }
  }
  expect(findings, 'no token reaches the browser by URL, Referer, redirect or response body').toEqual([]);
}

/** A PKCE pair (RFC 7636, S256). */
export function pkcePair() {
  const verifier = crypto.randomBytes(48).toString('base64url');
  return { verifier, challenge: crypto.createHash('sha256').update(verifier).digest('base64url') };
}

export function randomState() {
  return crypto.randomBytes(24).toString('base64url');
}

export function issuer() {
  return process.env.CONSOLE_ISSUER;
}

export function callbackUri() {
  return `${process.env.SEC_BACKEND}/login/oauth2/code/keycloak`;
}

/** A browser-like client without the browser: its own cookie jar (the realm's SSO cookies and the backend's SESSION). */
export function newActor() {
  return { jar: cookieJar() };
}

/**
 * Walks the console's login (or any authorization request) over plain HTTP the way a browser would, through the realm's
 * login form when this actor has no Keycloak session yet, and STOPS at the code callback: it returns that URL without
 * calling it, so the code is still unused and the actor's SESSION holds the pending authorization request. (A
 * Playwright route cannot hold a redirect back: measured, the callback reached the backend anyway.)
 */
export async function captureCallback(actor, startUrl, user = loginUser()) {
  let url = String(startUrl);
  let init = {};
  let posted = false;
  for (let hop = 0; hop < 15; hop++) {
    const response = await send(actor.jar, url, init);
    init = {};
    if (response.status >= 300 && response.status < 400) {
      const next = new URL(response.headers.get('location'), url);
      if (next.pathname === '/login/oauth2/code/keycloak') return next.toString();
      url = next.toString();
      continue;
    }
    const page = await response.text();
    const form = /<form[^>]*\sid="kc-form-login"[^>]*>/.exec(page);
    const action = form && /\saction="([^"]+)"/.exec(form[0]);
    if (!action || posted) throw new Error(`no code callback: ended at ${new URL(url).pathname} with status ${response.status}`);
    posted = true;
    url = new URL(action[1].replaceAll('&amp;', '&'), url).toString();
    init = {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ username: user.username, password: user.password, credentialId: '' })
    };
  }
  throw new Error('no code callback within 15 hops');
}

/** Hands the actor's current backend session cookie (the pending authorization request lives in it) to a browser context. */
export async function carry(context, actor) {
  const value = actor.jar.value(process.env.SEC_BACKEND, 'SESSION');
  if (value) await context.addCookies([sessionCookieFor(value)]);
}

/** The console's own login entry (the backend answers with the redirect to the realm's authorization endpoint). */
export function consoleLoginEntry() {
  return `${consoleUrl()}/oauth2/authorization/keycloak`;
}

/** The authorization-endpoint URL the backend sends the browser to, read from its redirect (nothing is followed). */
export async function authorizationRedirect() {
  const answer = await fetch(consoleLoginEntry(), { redirect: 'manual' });
  expect(answer.status, 'the login entry redirects to the identity provider').toBe(302);
  return new URL(answer.headers.get('location'));
}

/** The application's own log lines matching `grep` (last 80) - for explaining an unexpected answer. */
export async function appLog(grep = 'ERROR|Exception') {
  const answer = await fetch(`${process.env.SEC_CONTROL}/log?grep=${encodeURIComponent(grep)}`, { headers: { 'x-control-token': process.env.SEC_CONTROL_TOKEN } });
  return answer.text();
}

/** browserLogin with the page's whereabouts in the failure, so a login that stalls is explained. */
export async function signIn(page, user = loginUser()) {
  try {
    await browserLogin(page, consoleUrl(), user);
  } catch (e) {
    const body = (await page.locator('body').innerText().catch(() => '')).slice(0, 300).replace(/\s+/g, ' ');
    throw new Error(`sign-in stalled at ${page.url()} :: ${body} :: ${e.message.split('\n')[0]}`);
  }
}

/**
 * The three NUL-bearing variants of tamperedVariants (T202, karan T171-P1): the byte that triggered B33 is named, not left to
 * the arithmetic of 'appended data'. A raw 0x00 byte cannot be carried by a Node client (fetch: "invalid header value";
 * http.request: ERR_INVALID_CHAR), so the two base64 forms put the NUL in the DECODED id (what the server's session lookup
 * sees) and the third sends it percent-encoded (the closest literal form a cookie header can carry).
 */
export const NUL_VARIANTS = Object.freeze({
  appended: 'NUL byte appended to the genuine id (base64)',
  inside: 'NUL byte inside the genuine id (base64)',
  percent: 'percent-encoded NUL (%00) appended to the cookie value'
});

/** The thirteen tampered SESSION cookie values of S3-A5 / S3-A5b, derived from a genuine cookie value. */
export function tamperedVariants(valueA) {
  const id = sessionIdOf(valueA);
  const flip = (text, at) => text.slice(0, at) + (text[at] === 'A' ? 'B' : 'A') + text.slice(at + 1);
  return {
    'first character changed': flip(valueA, 0),
    'last character changed': flip(valueA, valueA.length - 1),
    'middle character changed': flip(valueA, Math.floor(valueA.length / 2)),
    truncated: valueA.slice(0, Math.floor(valueA.length / 2)),
    'appended data': `${valueA}AAAA`,
    'doubled': `${valueA}${valueA}`,
    'unknown but well-formed id': Buffer.from(crypto.randomUUID()).toString('base64'),
    'plain (not encoded) id': id,
    [NUL_VARIANTS.appended]: Buffer.from(`${id} `).toString('base64'),
    [NUL_VARIANTS.inside]: Buffer.from(`${id.slice(0, 18)} ${id.slice(18)}`).toString('base64'),
    [NUL_VARIANTS.percent]: `${valueA}%00`,
    'quote and comment characters': "x'--",
    'oversized value': 'A'.repeat(4000)
  };
}

/** Guard for the specs: the NUL variants are present and the base64 ones really decode to a NUL byte in the id. */
export function expectNulVariants(variants) {
  expect(Object.keys(variants), 'the NUL-bearing cookie variants are part of the tampering set').toEqual(expect.arrayContaining(Object.values(NUL_VARIANTS)));
  for (const label of [NUL_VARIANTS.appended, NUL_VARIANTS.inside]) {
    expect(Buffer.from(variants[label], 'base64').includes(0), `${label}: the decoded id carries a NUL byte`).toBe(true);
  }
  expect(variants[NUL_VARIANTS.percent].endsWith('%00'), 'the percent form ends in %00').toBe(true);
}
