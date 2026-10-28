// Row 575 (S1, role-aware menu) - the menu per role, in the real console against the real backend and Keycloak.
// Every role signs in through the realm's login form in its own fresh context (no shared session), then the
// PRIMARY side-nav group is counted: TENANT_ADMIN 6, CONFIGURATOR 3, APPROVER 4, SUPPORT 3, READ_ONLY 5.
// The Keycloak and Kafka UI links sit in the Account menu and show for TENANT_ADMIN only. UX check only: a hidden
// item is never the gate - the screens a role cannot open show NoAccess, and the server answers 403 regardless.
// Users: global-setup.mjs seeds one realm user per menu role in the default tenant (CONSOLE_MENU_USERS, memory only).

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';
import { browserLogin } from './keycloak-login.mjs';

const NO_SESSION = { storageState: { cookies: [], origins: [] } };

const EXPECTED = {
  TENANT_ADMIN: ['home', 'flows', 'systems', 'approvals', 'messages', 'reports'],
  CONFIGURATOR: ['home', 'flows', 'systems'],
  APPROVER: ['home', 'flows', 'systems', 'approvals'],
  SUPPORT: ['home', 'messages', 'reports'],
  READ_ONLY: ['home', 'flows', 'systems', 'messages', 'reports'],
};

function userFor(role) {
  if (role === 'TENANT_ADMIN') return { username: process.env.CONSOLE_LOGIN_USER, password: process.env.CONSOLE_LOGIN_PASSWORD };
  return JSON.parse(process.env.CONSOLE_MENU_USERS)[role];
}

async function signedInAs(browser, role) {
  const context = await browser.newContext(NO_SESSION);
  const page = await context.newPage();
  await browserLogin(page, consoleUrl(), userFor(role));
  await expect(page.locator('[data-session-tenant]')).toHaveText(process.env.CONSOLE_TENANT_CODE);
  return { context, page };
}

const primaryItems = (page) => page.locator('nav[aria-label="Primary"] > .nav-group:not([data-nav-group="more"]) [data-nav]');

for (const [role, items] of Object.entries(EXPECTED)) {
  test(`Scenario: ${role} sees ${items.length} primary menu items (${items.join(', ')})`, async ({ browser }) => {
    const { context, page } = await signedInAs(browser, role);
    await expect(primaryItems(page)).toHaveCount(items.length);
    expect(await primaryItems(page).evaluateAll((els) => els.map((el) => el.getAttribute('data-nav')))).toEqual(items);
    if (!items.includes('messages')) {
      await expect(page.locator('nav[aria-label="Primary"] [data-nav="messages"]'), `${role}: no Messages item`).toHaveCount(0);
    }
    await context.close();
  });
}

for (const [role, shows] of [['TENANT_ADMIN', true], ['CONFIGURATOR', false], ['SUPPORT', false], ['APPROVER', false], ['READ_ONLY', false]]) {
  test(`${shows ? '' : '@negative @role '}Scenario: Keycloak and Kafka UI links ${shows ? 'show' : 'are absent'} for ${role}`, async ({ browser }) => {
    const { context, page } = await signedInAs(browser, role);
    await page.click('[data-action="account"]');
    await expect(page.locator('[data-action="logout"]')).toBeVisible();
    const tools = page.locator('[data-nav="keycloak"], [data-nav="kafbat"]');
    if (shows) {
      await expect(tools).toHaveCount(2);
      await expect(page.locator('[data-nav="keycloak"]')).toHaveAttribute('target', '_blank');
      await expect(page.locator('[data-nav="keycloak"]')).toHaveAttribute('rel', /noopener/);
      await expect(page.locator('[data-nav="kafbat"]')).toBeVisible();
    } else {
      await expect(tools).toHaveCount(0);
    }
    await context.close();
  });
}

for (const [hash, label] of [['#/messages', 'Messages'], ['#/approvals', 'Approvals'], ['#/reports', 'Reports'], ['#/failures', 'Failures']]) {
  test(`@negative @role Scenario: a configurator typing ${hash} gets the NoAccess alert, not a blank page or the ${label} screen`, async ({ browser }) => {
    const { context, page } = await signedInAs(browser, 'CONFIGURATOR');
    await page.goto(`${consoleUrl()}/${hash}`);
    await expect(page.locator('main [role="alert"][data-no-access="true"]')).toBeVisible();
    await expect(page.locator('main [data-page]'), 'no screen content behind the alert').toHaveCount(0);
    await context.close();
  });
}

test('@negative @role Scenario: the hidden item is UX only - the server still answers a configurator 403 on the failures list', async ({ browser }) => {
  const { context, page } = await signedInAs(browser, 'CONFIGURATOR');
  // In-page fetch: the browser sends the HttpOnly session cookie (the request fixture does not send a Secure cookie to http).
  const status = await page.evaluate(async () => (await fetch('/api/v1/admin/messages/failures', { credentials: 'same-origin' })).status);
  expect(status, 'the server stays the gate').toBe(403);
  await context.close();
});

test('Scenario: keyboard - the first Tab stop is the skip link, Enter moves focus to main without changing the route', async ({ browser }) => {
  const { context, page } = await signedInAs(browser, 'TENANT_ADMIN');
  await page.goto(`${consoleUrl()}/#/flows`);
  await expect(page.locator('main')).toBeVisible();
  const hashBefore = new URL(page.url()).hash;
  await page.evaluate(() => { document.activeElement?.blur(); window.scrollTo(0, 0); });
  await page.keyboard.press('Tab');
  const skip = page.locator('a.skip-link');
  await expect(skip, 'the skip link is the first control').toBeFocused();
  await expect(skip).toBeInViewport();
  await page.keyboard.press('Enter');
  await expect(page.locator('#main-content'), 'focus moves to the main landmark').toBeFocused();
  expect(new URL(page.url()).hash, 'the skip link does not change the route').toBe(hashBefore);
  await context.close();
});

test('Scenario: after a menu navigation focus lands on the new screen heading (or main), and colliding menu initials are unique', async ({ browser }) => {
  const { context, page } = await signedInAs(browser, 'TENANT_ADMIN');
  for (const nav of ['flows', 'systems', 'approvals', 'messages']) {
    await page.click(`nav[aria-label="Primary"] [data-nav="${nav}"]`);
    await expect(page.locator(`nav[aria-label="Primary"] [data-nav="${nav}"]`)).toHaveAttribute('aria-current', 'page');
    // Soft: every screen is checked, so one failing screen does not hide the others.
    await expect.configure({ soft: true }).poll(() => page.evaluate(() => {
      const main = document.getElementById('main-content');
      const heading = main?.querySelector('h1');
      const active = document.activeElement;
      if (heading ? active === heading : active === main) return 'ok';
      return `focus stayed on <${active?.tagName.toLowerCase()}${active?.getAttribute('data-nav') ? ` data-nav=${active.getAttribute('data-nav')}` : ''}>; h1 tabIndex=${heading?.tabIndex}`;
    }), { message: `${nav}: focus on the h1 (else main)`, timeout: 2000 }).toBe('ok');
  }
  const initials = await page.locator('nav[aria-label="Primary"] [data-nav][data-initial]').evaluateAll((els) => els.map((el) => el.getAttribute('data-initial')));
  expect(new Set(initials).size, `collapsed-menu marks are unique: ${initials.join(',')}`).toBe(initials.length);
  await context.close();
});
