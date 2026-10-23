// T171 (row 372) Release 1 security gate S1 (identity), the three browser cases of the plan:
//   S1-A1 console login happy path (humans)   S1-A2 PKCE / state enforcement   S1-A3 redirect_uri exact match
// Against the REAL backend, the REAL Keycloak realm `platform` and the BUILT console (global-setup.mjs). Each case
// has a positive control, so a refusal cannot be a broken fixture. Test code only.

import { expect, test } from '@playwright/test';
import {
  api, assertScannerSees, atConsole, authorizationRedirect, callbackUri, captureCallback, carry, consoleCookies, consoleLoginEntry, newActor,
  consoleUrl, freshLogin, issuer, JWT_PATTERN, pkcePair, randomState, tokenFindings
} from './lib.mjs';

const NO_SESSION = { storageState: { cookies: [], origins: [] } };
const AUTH_ENDPOINT = () => `${issuer()}/protocol/openid-connect/auth`;
const TOKEN_ENDPOINT = () => `${issuer()}/protocol/openid-connect/token`;

function authorizationUrl({ redirectUri = callbackUri(), challenge, method = 'S256', state = randomState() } = {}) {
  const url = new URL(AUTH_ENDPOINT());
  url.searchParams.set('client_id', 'platform-console');
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('scope', 'openid');
  url.searchParams.set('state', state);
  if (redirectUri !== null) url.searchParams.set('redirect_uri', redirectUri);
  if (challenge) {
    url.searchParams.set('code_challenge', challenge);
    url.searchParams.set('code_challenge_method', method);
  }
  return url;
}

async function exchange({ code, verifier }) {
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: 'platform-console',
    client_secret: process.env.SEC_CONSOLE_CLIENT_SECRET,
    code,
    redirect_uri: callbackUri()
  });
  if (verifier) body.set('code_verifier', verifier);
  const answer = await fetch(TOKEN_ENDPOINT(), { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body });
  const json = await answer.json().catch(() => ({}));
  return { status: answer.status, error: json.error ?? null, gotToken: typeof json.access_token === 'string' }; // never the token itself
}

/** What the authorization endpoint did with a request: a login form, an error page, or a redirect. */
async function atAuthorizationEndpoint(url) {
  const answer = await fetch(url, { redirect: 'manual' });
  const body = await answer.text();
  return { status: answer.status, location: answer.headers.get('location'), form: body.includes('id="kc-form-login"'), body };
}

async function codeFor(actor, { verifier, challenge }) {
  const callback = await captureCallback(actor, authorizationUrl({ challenge }).toString());
  const code = new URL(callback).searchParams.get('code');
  expect(code, 'the authorization endpoint issued a code').toBeTruthy();
  return { code, verifier };
}

async function meStatus(page) {
  await atConsole(page);
  return (await api(page, 'GET', '/api/v1/me')).status;
}

test.describe('S1 identity (browser)', () => {
  test('S1-A1 console login happy path: only the demo tenant is shown and the browser stores no access token', async ({ browser }) => {
    assertScannerSees();
    const { context, page } = await freshLogin(browser);

    await expect(page.locator('[data-session-tenant]')).toHaveText(process.env.CONSOLE_TENANT_CODE);
    const me = await api(page, 'GET', '/api/v1/me');
    expect(me.status).toBe(200);
    expect(me.text, 'the signed-in identity is the demo tenant').toContain(process.env.CONSOLE_TENANT_CODE);
    expect(me.text, 'and never the other tenant').not.toContain(process.env.SEC_OTHER_TENANT_CODE);

    await page.goto(`${consoleUrl()}/#/versions`);
    await expect(page.locator('[data-flow-row]').first()).toBeVisible();
    await expect(page.locator(`[data-flow-row="${process.env.CONSOLE_OTHER_TENANT_FLOW}"]`), 'the other tenant\'s flow is not listed').toHaveCount(0);
    const flows = await api(page, 'GET', '/api/v1/admin/flows');
    expect(flows.status).toBe(200);
    expect(flows.text, 'the flows API holds no row of the other tenant').not.toContain(process.env.CONSOLE_OTHER_TENANT_FLOW);

    // devtools view: cookies + storage of the console origin
    const cookies = await consoleCookies(context);
    expect(cookies.map((cookie) => cookie.name).sort(), 'the console origin holds the session and the CSRF cookie, nothing else').toEqual(['SESSION', 'XSRF-TOKEN']);
    for (const cookie of cookies) expect(JWT_PATTERN.test(cookie.value), `cookie ${cookie.name} holds no JWT`).toBe(false);
    const stored = await page.evaluate(() => ({
      local: Object.entries(localStorage),
      session: Object.entries(sessionStorage),
      cookie: document.cookie,
      name: window.name
    }));
    const dump = JSON.stringify(stored);
    expect(tokenFindings(dump), 'no token in localStorage, sessionStorage, window.name or document.cookie').toEqual([]);
    expect(stored.cookie, 'script cannot read the session cookie').not.toContain('SESSION=');
    await context.close();
  });

  test('S1-A2 PKCE and state enforcement: S256 + unguessable state on every start; missing or wrong state, wrong verifier and reused code are refused', async ({ browser }) => {
    // 1. the console start carries PKCE S256 and a fresh unguessable state each time
    const first = await authorizationRedirect();
    const second = await authorizationRedirect();
    for (const url of [first, second]) {
      expect(url.searchParams.get('code_challenge_method')).toBe('S256');
      expect(url.searchParams.get('code_challenge')).toMatch(/^[A-Za-z0-9_-]{43}$/);
      expect(url.searchParams.get('state')?.length).toBeGreaterThanOrEqual(22);
    }
    expect(second.searchParams.get('state')).not.toBe(first.searchParams.get('state'));
    expect(second.searchParams.get('code_challenge')).not.toBe(first.searchParams.get('code_challenge'));

    // 2. the realm refuses a code flow without PKCE, or with the weaker method (control: the real PKCE start shows the form)
    const { challenge, verifier } = pkcePair();
    const control = await atAuthorizationEndpoint(authorizationUrl({ challenge }));
    expect(control.status, 'control: a PKCE S256 request reaches the login form').toBe(200);
    expect(control.form).toBe(true);
    const noPkce = await atAuthorizationEndpoint(authorizationUrl());
    const plain = await atAuthorizationEndpoint(authorizationUrl({ challenge, method: 'plain' }));
    for (const [label, answer] of [['no code_challenge', noPkce], ['code_challenge_method=plain', plain]]) {
      expect(answer.form, `${label}: no login form`).toBe(false);
      const refused = answer.status >= 400 || (answer.status === 302 && /[?&]error=/.test(answer.location ?? ''));
      expect(refused, `${label}: refused (status ${answer.status})`).toBe(true);
    }

    // 3. the backend's code callback: state is required and bound to the browser session; the code is single use.
    // Each round: a fresh authorization request (its own state, held in the actor's backend session), the callback URL taken
    // WITHOUT being called, then called by a browser that carries that session - once altered, or once untouched.
    const actor = newActor();
    const entry = consoleLoginEntry();
    async function browserCarrying() {
      const context = await browser.newContext(NO_SESSION);
      await carry(context, actor);
      return { context, page: await context.newPage() };
    }

    for (const [label, alter] of [
      ['without state', (url) => url.searchParams.delete('state')],
      ['with an empty state', (url) => url.searchParams.set('state', '')],
      ['with a foreign state', (url) => url.searchParams.set('state', randomState())]
    ]) {
      const url = new URL(await captureCallback(actor, entry));
      alter(url);
      const attempt = await browserCarrying();
      await attempt.page.goto(url.toString()).catch(() => {});
      expect(await meStatus(attempt.page), `callback ${label}: no session`).toBe(401);
      await attempt.context.close();
    }

    const genuine = await captureCallback(actor, entry);
    const signedIn = await browserCarrying();
    await signedIn.page.goto(genuine);
    expect(await meStatus(signedIn.page), 'control: the untouched callback signs in').toBe(200);

    const other = await browser.newContext(NO_SESSION);
    const otherPage = await other.newPage();
    await otherPage.goto(genuine).catch(() => {});
    expect(await meStatus(otherPage), 'the same code and state replayed from another browser: no session').toBe(401);
    await other.close();
    await signedIn.page.goto(genuine).catch(() => {});
    expect(await meStatus(signedIn.page), 'the replay in the same browser does not break the session it already has').toBe(200);
    const stolenSession = await browserCarrying(); // the pre-login session id the actor started with, now spent
    await stolenSession.page.goto(genuine).catch(() => {});
    expect(await meStatus(stolenSession.page), 'the spent pre-login session cannot redeem the same code again').toBe(401);
    await stolenSession.context.close();

    // 4. the token endpoint: PKCE verifier mismatch / missing and code reuse are invalid_grant (control: the right verifier works)
    const wrong = await codeFor(actor, pkcePair());
    const mismatch = await exchange({ code: wrong.code, verifier: pkcePair().verifier });
    expect([mismatch.status, mismatch.error, mismatch.gotToken], 'wrong code_verifier').toEqual([400, 'invalid_grant', false]);

    const absent = await codeFor(actor, pkcePair());
    const missing = await exchange({ code: absent.code });
    expect([missing.status, missing.error, missing.gotToken], 'code_verifier missing').toEqual([400, 'invalid_grant', false]);

    const good = await codeFor(actor, { challenge, verifier });
    const accepted = await exchange(good);
    expect([accepted.status, accepted.gotToken], 'control: the matching verifier is accepted').toEqual([200, true]);
    const reused = await exchange(good);
    expect([reused.status, reused.error, reused.gotToken], 'the same code a second time').toEqual([400, 'invalid_grant', false]);
    await signedIn.context.close();
  });

  test('S1-A3 redirect_uri exact match: every variation is refused at the authorization endpoint (or normalised to the registered address)', async () => {
    const registered = new URL(callbackUri());
    const origin = registered.origin;
    const { challenge } = pkcePair();

    const control = await atAuthorizationEndpoint(authorizationUrl({ challenge }));
    expect([control.status, control.form], 'control: the registered redirect_uri reaches the login form').toEqual([200, true]);

    const variations = {
      'trailing slash': `${callbackUri()}/`,
      'extra path segment': `${callbackUri()}/x`,
      'added query': `${callbackUri()}?a=b`,
      'path traversal': `${origin}/login/oauth2/code/keycloak/../../evil`,
      'encoded traversal': `${origin}/login/oauth2/code/%2e%2e/keycloak`,
      'encoded slash': `${origin}/login/oauth2/code/keycloak%2f..`,
      'path parameter': `${callbackUri()};x=1`,
      'upper-cased path': `${origin}/LOGIN/OAUTH2/CODE/KEYCLOAK`,
      'other path on the registered host': `${origin}/login`,
      'wildcard path': `${origin}/*`,
      'wildcard segment': `${origin}/login/oauth2/code/*`,
      'sibling port': `http://127.0.0.1:${Number(registered.port) + 1}/login/oauth2/code/keycloak`,
      'console origin (a different port)': `${consoleUrl()}/login/oauth2/code/keycloak`,
      'https scheme': `https://${registered.host}/login/oauth2/code/keycloak`,
      'localhost instead of the loopback address': `http://localhost:${registered.port}/login/oauth2/code/keycloak`,
      'attacker host': 'http://evil.example/login/oauth2/code/keycloak',
      'attacker host after userinfo': `http://${registered.host}@evil.example/login/oauth2/code/keycloak`,
      'registered host as a prefix of an attacker host': `http://127.0.0.1.evil.example:${registered.port}/login/oauth2/code/keycloak`,
      'null byte': `${callbackUri()}%00`,
      'null byte then an attacker suffix': `${callbackUri()}%00.evil.example`,
      'javascript scheme': 'javascript:alert(1)',
      'data scheme': 'data:text/html,x'
    };
    const accepted = [];
    const normalised = [];
    const actor = newActor();
    for (const [label, redirectUri] of Object.entries(variations)) {
      const answer = await atAuthorizationEndpoint(authorizationUrl({ redirectUri, challenge }));
      const refused = answer.status >= 400 && !answer.form && !answer.location;
      if (refused) continue;
      if (answer.form) {
        // Not refused: the code must still be delivered to the registered address and nowhere else (the provider
        // normalised the value). Anything else is an accepted variation.
        const delivered = new URL(await captureCallback(actor, authorizationUrl({ redirectUri, challenge }).toString()));
        if (delivered.origin + delivered.pathname === callbackUri() && delivered.search.startsWith('?state=')) {
          normalised.push(label);
          continue;
        }
      }
      accepted.push(`${label} -> ${answer.status}${answer.form ? ' (login form)' : ''}${answer.location ? ' (redirect)' : ''}`);
    }
    test.info().annotations.push({ type: 'normalised to the registered address (not refused, never delivered elsewhere)', description: normalised.join('; ') || 'none' });
    expect(accepted, 'variations that were NOT refused and were delivered somewhere else').toEqual([]);

    // the sign-out redirect obeys the same rule: an unregistered post_logout_redirect_uri is never followed
    const logout = new URL(`${issuer()}/protocol/openid-connect/logout`);
    logout.searchParams.set('client_id', 'platform-console');
    logout.searchParams.set('post_logout_redirect_uri', 'http://evil.example/');
    const signOut = await fetch(logout, { redirect: 'manual' });
    expect(signOut.headers.get('location') ?? '', 'sign-out does not redirect to an unregistered address').not.toContain('evil.example');
  });
});
