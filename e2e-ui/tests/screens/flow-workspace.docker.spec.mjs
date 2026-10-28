// Row 575 (S4, flow workspace) - the header on #/flow/<id> in the real console against the real backend and Keycloak.
//   stepper : six steps, each with a mark AND a state word; what the list data cannot show is never ticked
//             ("Not tracked here" for Contracts and Go live, "Checked when you publish" for Test); Mapping says "N of M routes have a mapping"
//   tabs    : Setup / Mapping / Rules / Versions are links; "Failed messages" only for roles that read messages
//   guard   : the unsaved-edit prompt still fires from every workspace link (tabs and stepper actions: data-leaves-editor)
// Users: global-setup.mjs seeds menu-cf / menu-ap / menu-su in the default tenant (CONSOLE_MENU_USERS, memory only).

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';
import { browserLogin } from './keycloak-login.mjs';
import { inBrowser } from './screen-fixtures.mjs';

const NO_SESSION = { storageState: { cookies: [], origins: [] } };
const WORKSPACE = '[data-flow-workspace]';
const EDITOR = '[data-page="flow-definition"]';
const stamp = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

async function signedInAs(browser, role) {
  const context = await browser.newContext(NO_SESSION);
  const page = await context.newPage();
  await browserLogin(page, consoleUrl(), JSON.parse(process.env.CONSOLE_MENU_USERS)[role]);
  await expect(page.locator('[data-session-tenant]')).toHaveText(process.env.CONSOLE_TENANT_CODE);
  return { context, page };
}

/** A draft flow with two destinations (two routes, no mapping yet), created through the real API as the suite's tenant admin. */
async function twoRouteFlow(page) {
  await page.goto(consoleUrl());
  await expect(page.locator('nav')).toBeVisible();
  const created = await inBrowser(page, 'POST', '/api/v1/admin/flows',
    { name: `ws-${stamp()}`, sourceSystem: 'S', destinations: ['T1', 'T2'], businessKeyRule: 'a_key' });
  expect(created?.data?.id, `the flow is created: ${JSON.stringify(created?.data ?? created)}`).toBeTruthy();
  return created.data.id;
}

async function openWorkspace(page, id) {
  await page.goto(`${consoleUrl()}/#/flow/${id}`);
  await expect(page.locator(WORKSPACE)).toBeVisible();
  await expect(page.locator(EDITOR)).toBeVisible();
}

const step = (page, key) => page.locator(`[data-flow-step="${key}"]`);
const tab = (page, key) => page.locator(`nav[aria-label="Flow sections"] [data-flow-tab="${key}"]`);

test('Scenario: the stepper is honest - six steps with a mark AND a word; Contracts, Test and Go live are never ticked', async ({ page }) => {
  const id = await twoRouteFlow(page);
  await openWorkspace(page, id);
  await expect(page.locator('[data-flow-stepper]')).toHaveAttribute('aria-label', 'Setup progress');
  const steps = page.locator('[data-flow-stepper] > li[data-flow-step]');
  await expect(steps).toHaveCount(6);
  expect(await steps.evaluateAll((els) => els.map((el) => el.getAttribute('data-flow-step')))).toEqual(['systems', 'contracts', 'mapping', 'test', 'approve', 'golive']);

  const rows = await steps.evaluateAll((els) => els.map((el) => ({
    key: el.getAttribute('data-flow-step'),
    cls: el.className,
    mark: el.querySelector('.home-step-mark')?.textContent,
    markHidden: el.querySelector('.home-step-mark')?.getAttribute('aria-hidden'),
    word: el.querySelector('.home-step-state')?.textContent,
    note: el.querySelector('.home-step-note')?.textContent,
    current: el.getAttribute('aria-current'),
  })));
  const by = Object.fromEntries(rows.map((r) => [r.key, r]));

  expect([by.systems.mark, by.systems.word], 'Systems is done: source and destinations are saved').toEqual(['✓', 'Done']);
  for (const [key, word] of [['contracts', 'Not tracked here'], ['test', 'Checked when you publish'], ['golive', 'Not tracked here']]) {
    expect(by[key].cls, `${key} is info, never next/done`).toContain('home-step-info');
    expect([by[key].mark, by[key].word]).toEqual(['–', word]);
    expect(by[key].current).toBeNull();
  }
  for (const row of rows) expect(row.markHidden, `${row.key}: the glyph is decoration, the word carries the state`).toBe('true');
  expect(by.mapping.note, 'Mapping says how many routes have one').toMatch(/^\d+ of 2 routes have a mapping\.$/);
  expect(rows.filter((r) => r.cls.includes('home-step-next')).length, 'at most one Next step').toBeLessThanOrEqual(1);
  for (const word of await steps.locator('.home-step-state').all()) await expect(word).toBeVisible();
  await expect(page.locator('[data-flow-status]')).not.toHaveText('');
});

test('Scenario: Go live - the tenant admin gets the Open Systems action, a configurator is told to ask the tenant admin', async ({ page, browser }) => {
  test.setTimeout(60_000);
  const id = await twoRouteFlow(page);
  await openWorkspace(page, id);
  await expect(step(page, 'golive').getByRole('link', { name: 'Open Systems' })).toHaveAttribute('href', '#/systems');

  const cf = await signedInAs(browser, 'CONFIGURATOR');
  await openWorkspace(cf.page, id);
  await expect(step(cf.page, 'golive').locator('.home-step-note')).toHaveText('Ask your tenant admin to issue the source key.');
  await expect(step(cf.page, 'golive').getByRole('link')).toHaveCount(0);
  await cf.context.close();
});

test('Scenario: the tabs are links - Setup is current, the others go to the flow\'s screens', async ({ page }) => {
  const id = await twoRouteFlow(page);
  await openWorkspace(page, id);
  const nav = page.getByRole('navigation', { name: 'Flow sections' });
  await expect(nav.getByRole('link')).toHaveCount(5);
  await expect(page.locator('[role="tab"]'), 'tabs are links, not role=tab buttons').toHaveCount(0);
  await expect(tab(page, 'setup')).toHaveAttribute('aria-current', 'page');
  await expect(tab(page, 'rules')).toHaveAttribute('href', `#/rules?flow=${id}`);
  await expect(tab(page, 'versions')).toHaveAttribute('href', `#/versions/${id}`);
  await expect(tab(page, 'messages')).toHaveText('Failed messages');
  await expect(tab(page, 'messages')).toHaveAttribute('href', `#/failures?flow=${id}`);
  await expect(tab(page, 'mapping')).toHaveAttribute('href', /^#\/mappings(\/.+)?$/);
  for (const key of ['setup', 'mapping', 'rules', 'versions', 'messages']) {
    await expect(tab(page, key), `${key}: a workspace link that leaves the editor`).toHaveAttribute('data-leaves-editor', 'true');
  }
});

test('@negative @role Scenario: "Failed messages" is hidden for roles that cannot read messages (configurator, approver)', async ({ page, browser }) => {
  test.setTimeout(90_000);
  const id = await twoRouteFlow(page);
  for (const role of ['CONFIGURATOR', 'APPROVER']) {
    const user = await signedInAs(browser, role);
    await openWorkspace(user.page, id);
    await expect(user.page.getByRole('navigation', { name: 'Flow sections' }).getByRole('link'), `${role}: four tabs`).toHaveCount(4);
    await expect(tab(user.page, 'messages'), `${role}: no Failed messages tab`).toHaveCount(0);
    await expect(tab(user.page, 'versions')).toBeVisible();
    await user.context.close();
  }
});

test('@negative @role Scenario: support cannot read configuration - a typed flow address shows the NoAccess alert, no workspace', async ({ page, browser }) => {
  const id = await twoRouteFlow(page);
  const su = await signedInAs(browser, 'SUPPORT');
  await su.page.goto(`${consoleUrl()}/#/flow/${id}`);
  await expect(su.page.locator('main [role="alert"][data-no-access="true"]')).toBeVisible();
  await expect(su.page.locator(WORKSPACE)).toHaveCount(0);
  await su.context.close();
});

test('Scenario: the unsaved-edit prompt still fires on every workspace tab, Stay keeps the edit, Leave goes on', async ({ page }) => {
  test.setTimeout(60_000);
  const id = await twoRouteFlow(page);
  await openWorkspace(page, id);
  await page.fill('[data-field="business-key-rule"]', 'edited');
  for (const key of ['versions', 'rules', 'mapping', 'messages']) {
    await tab(page, key).click();
    await expect(page.locator('[data-confirm="unsaved-changes"]'), `${key} tab: the prompt holds the move`).toBeVisible();
    await expect(page.locator(EDITOR)).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`#/flow/${id}$`));
    await page.click('[data-action="stay"]');
    await expect(page.locator('[data-confirm="unsaved-changes"]')).toBeHidden();
    await expect(page.locator('[data-field="business-key-rule"]'), 'Stay keeps the edit').toHaveValue('edited');
  }
  await tab(page, 'versions').click();
  await page.click('[data-action="leave-without-saving"]');
  await expect(page.locator(EDITOR)).toBeHidden();
  await expect(page).toHaveURL(new RegExp(`#/versions/${id}$`));
});

test('Scenario: a stepper action link is guarded too, and a clean editor leaves without a prompt', async ({ page }) => {
  test.setTimeout(60_000);
  const id = await twoRouteFlow(page);
  await openWorkspace(page, id);
  const action = page.locator('[data-flow-stepper] a[data-leaves-editor="true"]').first();
  await expect(action).toBeVisible();
  await page.fill('[data-field="business-key-rule"]', 'edited');
  await action.click();
  await expect(page.locator('[data-confirm="unsaved-changes"]'), 'a stepper action holds the move').toBeVisible();
  await page.click('[data-action="stay"]');

  await page.reload(); // nothing unsaved any more
  await expect(page.locator(WORKSPACE)).toBeVisible();
  await tab(page, 'versions').click();
  await expect(page.locator('[data-confirm="unsaved-changes"]')).toHaveCount(0);
  await expect(page).toHaveURL(new RegExp(`#/versions/${id}$`));
});
