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

test('Scenario: Login, then own tenant only - redirected to Keycloak, logged in, only tenant A is shown', async ({ browser }) => {
  const context = await browser.newContext(NO_SESSION);
  const page = await context.newPage();

  await page.goto(`${consoleUrl()}/#/versions`);
  await page.waitForURL(atTheRealmLogin);
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
    await page.waitForURL(atTheRealmLogin);
    await expect(page.locator('#kc-form-login')).toBeVisible();
  }
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
  await page.waitForURL(atTheRealmLogin);
  await page.fill('#username', loginUser().username);
  await page.fill('#password', loginUser().password);
  await Promise.all([page.waitForURL((url) => url.toString().startsWith(consoleUrl())), page.click('#kc-login')]);
  await expect(page.locator('[data-session-tenant]')).toBeVisible();

  const session = (await context.cookies(consoleUrl())).find((c) => c.name === 'SESSION');
  expect(session?.httpOnly, 'the session cookie is HttpOnly').toBe(true);
  expect(session?.sameSite).toBe('Lax');

  await page.click('[data-action="logout"]');
  await page.waitForURL(atTheRealmLogin);
  const answer = await context.request.get(`${consoleUrl()}/api/v1/me`, { maxRedirects: 0 });
  expect(answer.status()).toBe(401);
  await context.close();
});
