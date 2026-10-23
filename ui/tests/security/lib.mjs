// T171 security gate - helpers shared by the browser cases (S1-A1..A3, S3-A1..A6).
// Everything here drives the REAL console, backend and Keycloak that the global setup booted; nothing is mocked.

import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { expect } from '@playwright/test';
import { browserLogin } from '../screens/keycloak-login.mjs';
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
      const response = await fetch(apiPath, { method, headers, credentials: 'include', body: body === undefined ? undefined : JSON.stringify(body) });
      return { status: response.status, text: await response.text() };
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

/** Every request, redirect chain, response header and (text) response body the page sees, for token scans. */
export function recordTraffic(page) {
  const traffic = { requests: [], responses: [], navigations: [], pending: [] };
  page.on('request', (request) => {
    traffic.requests.push({ url: request.url(), method: request.method(), referer: request.headers().referer ?? null });
  });
  page.on('framenavigated', (frame) => traffic.navigations.push(frame.url()));
  page.on('response', (response) => {
    const headers = response.headers();
    const entry = { url: response.url(), status: response.status(), headers, body: null };
    traffic.responses.push(entry);
    const type = headers['content-type'] ?? '';
    // JS bundles and styles are the console's own code, not data it was handed; fonts and images carry no token.
    if (/json|html|text\/plain|x-www-form-urlencoded/.test(type) && !/javascript|css/.test(type)) {
      traffic.pending.push(response.text().then((text) => { entry.body = text; }).catch(() => {}));
    }
  });
  traffic.settle = () => Promise.all(traffic.pending);
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

/**
 * Drives one authorization round of the console client in `page` and returns the code callback's URL WITHOUT letting
 * the backend consume it (the request is aborted in the browser). Goes through the realm's login form when the
 * page has no Keycloak session yet; with one, Keycloak answers the authorization request by itself.
 */
export async function captureCallback(page, startUrl, user = loginUser()) {
  const pattern = /\/login\/oauth2\/code\/keycloak/;
  const handler = (route) => route.abort();
  await page.route(pattern, handler);
  try {
    const requested = page.waitForRequest(pattern, { timeout: 45000 });
    requested.catch(() => {});
    page.goto(startUrl).catch(() => {});
    const form = page.locator('#kc-form-login');
    const formShown = form.waitFor({ state: 'visible', timeout: 45000 }).then(() => 'form').catch(() => 'none');
    const first = await Promise.race([requested.then(() => 'callback'), formShown]);
    if (first === 'form') {
      await page.fill('#username', user.username);
      await page.fill('#password', user.password);
      page.click('#kc-login').catch(() => {});
    }
    return (await requested).url();
  } finally {
    await page.unroute(pattern, handler);
  }
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
