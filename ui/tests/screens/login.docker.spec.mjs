// T-097 (E4-F1-S1, lane D, row 278) - "Log in before seeing anything", in the browser, FAILING FIRST.
//
// The console against the REAL backend and the REAL Keycloak (realm `platform`): every login goes
// through the realm's login form (keycloak-login.mjs), the browser holds only the backend's
// HttpOnly session cookie (ADR-007 Decision 1). Each scenario opens its own fresh browser context
// (no session) - the suite's shared signed-in state is never touched. The API-level twins (every
// /api path, the token-claim and inactive-user scenarios, the cookie flags) run in platform-app's
// ConsoleLoginContainerTest.

import { expect, test } from '@playwright/test';
import { consoleUrl, loginUser } from './boot-state.mjs';

const NO_SESSION = { storageState: { cookies: [], origins: [] } };

function atTheRealmLogin(url) {
  return url.toString().startsWith(process.env.CONSOLE_ISSUER);
}

// Row 310 lane A (10-screens §2, 00-portal-ia §4): a no-session visit lands on the console's own
// sign-in card first - a centered card, h1 "Sign in", ONE primary "Continue with SSO" as the first
// focus, no side nav on the empty shell - and only that button goes on to the realm login form.
async function atTheSignInCard(page) {
  await page.waitForURL((url) => url.toString().startsWith(consoleUrl()) && url.hash === '#/login');
  const card = page.locator('[data-page="login"]');
  await expect(card).toBeVisible();
  await expect(card.getByRole('heading', { level: 1 })).toHaveText('Sign in');
  await expect(page.locator('[data-nav]'), 'no side nav on the sign-in state').toHaveCount(0);
  const sso = page.locator('[data-action="sso"]');
  await expect(sso).toHaveText('Continue with SSO');
  await expect(sso, 'the SSO button is the first focus').toBeFocused();
}

async function continueWithSso(page) {
  await atTheSignInCard(page);
  await page.click('[data-action="sso"]');
  await page.waitForURL(atTheRealmLogin);
}

test('Scenario: Login, then own tenant only - redirected to Keycloak, logged in, only tenant A is shown', async ({ browser }) => {
  const context = await browser.newContext(NO_SESSION);
  const page = await context.newPage();

  await page.goto(`${consoleUrl()}/#/versions`);
  await continueWithSso(page);
  await page.fill('#username', loginUser().username);
  await page.fill('#password', loginUser().password);
  await Promise.all([page.waitForURL((url) => url.toString().startsWith(consoleUrl())), page.click('#kc-login')]);

  await expect(page.locator('[data-session-tenant]')).toHaveText(process.env.CONSOLE_TENANT_CODE);
  await page.goto(`${consoleUrl()}/#/versions`);
  await expect(page.locator('[data-flow-row]').first()).toBeVisible();
  await expect(page.locator(`[data-flow-row="${process.env.CONSOLE_OTHER_TENANT_FLOW}"]`)).toHaveCount(0);
  await context.close();
});

test('@negative @security Scenario: No session, no data - the page goes to the Keycloak login and the API answers 401', async ({ browser }) => {
  const context = await browser.newContext(NO_SESSION);
  const page = await context.newPage();

  for (const screen of ['/', '/#/flows', '/#/versions']) {
    await page.goto(`${consoleUrl()}${screen}`);
    await atTheSignInCard(page);
    await expect(page.locator('[data-flow-row]'), `${screen}: no tenant data without a session`).toHaveCount(0);
  }
  await continueWithSso(page);
  await expect(page.locator('#kc-form-login')).toBeVisible();
  for (const api of ['/api/v1/me', '/api/v1/admin/flows']) {
    const answer = await context.request.get(`${consoleUrl()}${api}`, { maxRedirects: 0 });
    expect(answer.status(), api).toBe(401);
  }
  const cookies = await context.cookies(consoleUrl());
  expect(cookies.filter((c) => c.name === 'SESSION')).toHaveLength(0);
  await context.close();
});

test('Logout ends the session: back at the Keycloak login, and the API answers 401', async ({ browser }) => {
  const context = await browser.newContext(NO_SESSION);
  const page = await context.newPage();
  await page.goto(consoleUrl());
  await continueWithSso(page);
  await page.fill('#username', loginUser().username);
  await page.fill('#password', loginUser().password);
  await Promise.all([page.waitForURL((url) => url.toString().startsWith(consoleUrl())), page.click('#kc-login')]);
  await expect(page.locator('[data-session-tenant]')).toBeVisible();

  const session = (await context.cookies(`https://${new URL(consoleUrl()).host}`)).find((c) => c.name === 'SESSION');
  expect(session?.httpOnly, 'the session cookie is HttpOnly').toBe(true);
  expect(session?.sameSite).toBe('Lax');

  // The shell (lane F, row 310) puts Sign out in the account menu: open it the way a user does.
  await page.click('[data-action="account"]');
  await expect(page.locator('[data-action="logout"]')).toBeVisible();
  await page.click('[data-action="logout"]');
  // RP-initiated logout returns to the console (post-logout redirect = console origin), whose /me
  // answers 401 - so the browser shows the sign-in card, and SSO asks for credentials again.
  await continueWithSso(page);
  // The session is really over (server-side, not just out of the jar): the console reloads, the
  // API answers 401 and the client sends the browser back to the realm login. (A request-level
  // context.request check cannot prove this: it never carries the Secure session cookie over the
  // console's http://127.0.0.1 origin, so its 401 is true with a dead AND with a live session -
  // measured lane D; the browser's own cookies are the session here.)
  await page.goto(consoleUrl());
  await continueWithSso(page);
  await expect(page.locator('#kc-form-login')).toBeVisible();
  await context.close();
});

test('Signed in, the sign-in state never shows: #/login goes on to the Dashboard', async ({ page }) => {
  // The suite's shared signed-in state (global setup). 10-screens §2: while /me resolves the card
  // shows only "Checking your session...", and a live session goes straight to the Dashboard.
  await page.goto(`${consoleUrl()}/#/login`);
  await page.waitForURL((url) => url.toString().startsWith(consoleUrl()) && (url.hash === '#/' || url.hash === '' || url.hash === '#/dashboard'));
  await expect(page.locator('[data-page="dashboard"]')).toBeVisible();
  await expect(page.locator('[data-action="sso"]')).toHaveCount(0);
  await expect(page.locator('[data-session-tenant]')).toHaveText(process.env.CONSOLE_TENANT_CODE);
});
