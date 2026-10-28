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

// Row 571: there is no "Continue with SSO" page. A no-session visit goes straight to the realm login form
// (the console's #/login state redirects once and records the attempt); a second no-session arrival after
// that attempt (Back from the form, a cancelled login) shows "Try again" - never a second automatic jump;
// right after a sign-out the landing shows a Sign in button. Console state keeps data-page="login".
async function atTheRealmLoginForm(page) {
  await page.waitForURL(atTheRealmLogin);
  await expect(page.locator('#kc-form-login')).toBeVisible();
}

async function atTheConsoleSignInState(page, view) {
  await page.waitForURL((url) => url.toString().startsWith(consoleUrl()) && url.hash === '#/login');
  const card = page.locator('[data-page="login"]');
  await expect(card).toBeVisible();
  await expect(card).toHaveAttribute('data-login-view', view);
  await expect(page.locator('[data-nav]'), 'no side nav on the sign-in state').toHaveCount(0);
  return card;
}

async function signInAtTheRealm(page) {
  await page.fill('#username', loginUser().username);
  await page.fill('#password', loginUser().password);
  await Promise.all([page.waitForURL((url) => url.toString().startsWith(consoleUrl())), page.click('#kc-login')]);
}

test('Scenario: Login, then own tenant only - straight to Keycloak, logged in, back on the asked-for screen, only tenant A is shown', async ({ browser }) => {
  const context = await browser.newContext(NO_SESSION);
  const page = await context.newPage();

  await page.goto(`${consoleUrl()}/#/versions`);
  await atTheRealmLoginForm(page);
  await expect(page.locator('[data-action="sso"]'), 'no SSO landing page in between').toHaveCount(0);
  await signInAtTheRealm(page);

  await expect(page.locator('[data-session-tenant]')).toHaveText(process.env.CONSOLE_TENANT_CODE);
  expect(new URL(page.url()).hash, 'the return-to path is restored after the login').toBe('#/versions');
  await expect(page.locator('[data-flow-row]').first()).toBeVisible();
  await expect(page.locator(`[data-flow-row="${process.env.CONSOLE_OTHER_TENANT_FLOW}"]`)).toHaveCount(0);
  await context.close();
});

test('@negative @security Scenario: No session, no data - every screen goes to the Keycloak login and the API answers 401', async ({ browser }) => {
  const context = await browser.newContext(NO_SESSION);
  for (const screen of ['/', '/#/flows', '/#/versions']) {
    const page = await context.newPage(); // a fresh tab per screen: each is a first visit, so each redirects once
    await page.goto(`${consoleUrl()}${screen}`);
    await atTheRealmLoginForm(page);
    await expect(page.locator('[data-flow-row]'), `${screen}: no tenant data without a session`).toHaveCount(0);
    await page.close();
  }
  for (const api of ['/api/v1/me', '/api/v1/admin/flows']) {
    const answer = await context.request.get(`${consoleUrl()}${api}`, { maxRedirects: 0 });
    expect(answer.status(), api).toBe(401);
  }
  const cookies = await context.cookies(consoleUrl());
  expect(cookies.filter((c) => c.name === 'SESSION')).toHaveLength(0);
  await context.close();
});

test('@negative Scenario: loop guard - back from the Keycloak form shows Try again, never a second automatic redirect', async ({ browser }) => {
  const context = await browser.newContext(NO_SESSION);
  const page = await context.newPage();
  await page.goto(consoleUrl());
  await atTheRealmLoginForm(page);
  await page.goto(consoleUrl()); // the visitor abandons the form and returns: a recorded attempt with no session
  const card = await atTheConsoleSignInState(page, 'failed');
  await expect(card.getByRole('heading', { level: 1 })).toHaveText('Sign-in did not complete');
  const retry = page.locator('[data-action="sso"]');
  await expect(retry).toHaveText('Try again');
  await expect(retry, 'the button is the first focus').toBeFocused();
  await page.waitForTimeout(1500);
  expect(page.url().startsWith(consoleUrl()), 'still on the console: no redirect loop').toBe(true);
  await retry.click();
  await atTheRealmLoginForm(page);
  await context.close();
});

test('Logout ends the session: the landing shows a Sign in button, and Sign in asks for credentials again', async ({ browser }) => {
  const context = await browser.newContext(NO_SESSION);
  const page = await context.newPage();
  await page.goto(consoleUrl());
  await atTheRealmLoginForm(page);
  await signInAtTheRealm(page);
  await expect(page.locator('[data-session-tenant]')).toBeVisible();

  const session = (await context.cookies(`https://${new URL(consoleUrl()).host}`)).find((c) => c.name === 'SESSION');
  expect(session?.httpOnly, 'the session cookie is HttpOnly').toBe(true);
  expect(session?.sameSite).toBe('Lax');

  // The shell (lane F, row 310) puts Sign out in the account menu: open it the way a user does.
  await page.click('[data-action="account"]');
  await expect(page.locator('[data-action="logout"]')).toBeVisible();
  await page.click('[data-action="logout"]');
  // RP-initiated logout returns to the console, whose /me answers 401: the landing says so and waits for a click.
  const card = await atTheConsoleSignInState(page, 'logged-out');
  await expect(card.getByRole('heading', { level: 1 })).toHaveText('You have been signed out');
  const signIn = page.locator('[data-action="sso"]');
  await expect(signIn).toHaveText('Sign in');
  await signIn.click();
  await atTheRealmLoginForm(page);
  // The session is really over (server-side, not just out of the jar): the realm asks for credentials again.
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

// Row 571 follow-up 5: a failed code exchange (here: the provider answered error=access_denied) used to end on raw
// JSON. A browser navigation is sent to the console's sign-in state, which shows Try again (never a second jump).
test.describe('a failed code exchange', () => {
  test.use(NO_SESSION);
  test('a browser lands on the sign-in state with Try again, not on JSON', async ({ page }) => {
    await page.goto(`${consoleUrl()}/login/oauth2/code/keycloak?error=access_denied&state=stale`);
    await page.waitForURL((url) => url.toString().startsWith(consoleUrl()) && url.hash.startsWith('#/login'));
    expect(new URL(page.url()).search, 'no code or state is left in the address bar').toBe('');
    const card = await atTheConsoleSignInState(page, 'failed');
    await expect(card.getByRole('button', { name: 'Try again' })).toBeFocused();
  });
});
