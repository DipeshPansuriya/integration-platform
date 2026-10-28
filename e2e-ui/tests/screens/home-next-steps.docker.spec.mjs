// Row 575 (S2, Home "Next steps") - the Home blocks per role, in the real console against the real backend and Keycloak.
// Each role signs in through the realm login form in its own fresh context (users: global-setup.mjs, CONSOLE_MENU_USERS).
//   Set up an integration                  : tenant admin + configurator
//   Waiting for your approval              : tenant admin + approver
//   Find a message + Needs attention today : tenant admin + support + read-only
// UX only: a hidden block is never the gate; a block whose API answers 403 is simply not rendered (no error banner).

import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';
import { browserLogin } from './keycloak-login.mjs';

const NO_SESSION = { storageState: { cookies: [], origins: [] } };
const BLOCKS = ['setup', 'approvals', 'messages'];
const SHOWN = {
  TENANT_ADMIN: ['setup', 'approvals', 'messages'],
  CONFIGURATOR: ['setup'],
  APPROVER: ['approvals'],
  SUPPORT: ['messages'],
  READ_ONLY: ['messages'],
};
const HEADING = { setup: 'Set up an integration', approvals: /^Waiting for your approval \(\d+\)$/, messages: 'Find a message' };

function userFor(role) {
  if (role === 'TENANT_ADMIN') return { username: process.env.CONSOLE_LOGIN_USER, password: process.env.CONSOLE_LOGIN_PASSWORD };
  return JSON.parse(process.env.CONSOLE_MENU_USERS)[role];
}

async function homeAs(browser, role) {
  const context = await browser.newContext(NO_SESSION);
  const page = await context.newPage();
  await browserLogin(page, consoleUrl(), userFor(role));
  await page.goto(`${consoleUrl()}/#/`);
  await expect(page.locator('[data-page="dashboard"]')).toHaveAttribute('data-dashboard-loaded', 'true');
  return { context, page };
}

for (const [role, shown] of Object.entries(SHOWN)) {
  test(`Scenario: ${role} sees the Home blocks ${shown.join(' + ')} and no others`, async ({ browser }) => {
    const { context, page } = await homeAs(browser, role);
    await expect(page.getByRole('heading', { level: 1, name: 'Home' })).toBeVisible();
    for (const block of BLOCKS) {
      const section = page.locator(`[data-home-block="${block}"]`);
      if (shown.includes(block)) {
        await expect(section, `${role}: ${block} block`).toBeVisible();
        await expect(section.getByRole('heading', { level: 2, name: HEADING[block] })).toBeVisible();
        if (block === 'approvals') await expect(section.getByRole('link', { name: 'Open versions' })).toHaveAttribute('href', '#/versions');
      } else {
        await expect(section, `${role}: no ${block} block`).toHaveCount(0);
      }
    }
    if (shown.includes('messages')) {
      await expect(page.getByRole('heading', { level: 3, name: 'Needs attention today' })).toBeVisible();
      await expect(page.getByLabel('Tracking id')).toBeVisible();
    } else {
      await expect(page.getByRole('heading', { name: 'Needs attention today' })).toHaveCount(0);
    }
    await context.close();
  });
}

for (const role of ['SUPPORT', 'READ_ONLY', 'APPROVER', 'CONFIGURATOR']) {
  test(`@negative @role Scenario: ${role} sees no error banner on Home (blocked API answers are hidden, not shown)`, async ({ browser }) => {
    const { context, page } = await homeAs(browser, role);
    await expect(page.locator('main [role="alert"]'), `${role}: no alert on Home`).toHaveCount(0);
    await expect(page.getByText('Some counts could not be loaded.')).toHaveCount(0);
    await expect(page.getByText('Failed messages could not be loaded.')).toHaveCount(0);
    await context.close();
  });
}

test('Scenario: Find a message - a valid tracking id opens its message address', async ({ browser }) => {
  const { context, page } = await homeAs(browser, 'TENANT_ADMIN');
  const id = randomUUID();
  await page.getByLabel('Tracking id').fill(`  ${id.toUpperCase()}  `);
  await page.getByRole('button', { name: 'Go' }).click();
  await page.waitForURL((url) => url.hash.toLowerCase() === `#/messages/${id}`);
  await expect(page.locator('[data-home-block="messages"]')).toHaveCount(0);
  await context.close();
});

test('@negative Scenario: Find a message - an invalid tracking id stays on Home and shows the hint, announced and linked to the field', async ({ browser }) => {
  const { context, page } = await homeAs(browser, 'TENANT_ADMIN');
  const input = page.getByLabel('Tracking id');
  // The live region is mounted before anything is wrong (so the first hint is announced) and empty until then.
  await expect(page.locator('#home-tracking-hint')).toHaveAttribute('role', 'status');
  await expect(page.locator('#home-tracking-hint')).toHaveText('');
  for (const bad of ['', 'not-a-uuid', randomUUID().slice(0, 35), `${randomUUID()}x`]) {
    await input.fill(bad);
    await page.getByRole('button', { name: 'Go' }).click();
    await expect(page.locator('#home-tracking-hint'), `hint for "${bad}"`).toHaveText('Enter a tracking id (36 characters)');
    await expect(page.locator('#home-tracking-hint')).toHaveAttribute('role', 'status');
    await expect(input).toHaveAttribute('aria-invalid', 'true');
    await expect(input).toHaveAttribute('aria-describedby', 'home-tracking-hint');
    expect(new URL(page.url()).hash, 'still on Home').toMatch(/^(#\/)?$/);
  }
  await context.close();
});

test('Scenario: the setup checklist says it in words - six steps, each with a mark AND a state word, steps 4 and 6 are info', async ({ browser }) => {
  const { context, page } = await homeAs(browser, 'TENANT_ADMIN');
  const steps = page.locator('[data-home-block="setup"] ol > li[data-step]');
  await expect(steps).toHaveCount(6);
  expect(await steps.evaluateAll((els) => els.map((el) => el.getAttribute('data-step')))).toEqual(['systems', 'contracts', 'flow', 'test', 'approve', 'golive']);
  const rows = await steps.evaluateAll((els) => els.map((el) => ({
    mark: el.querySelector('.home-step-mark')?.textContent,
    markHidden: el.querySelector('.home-step-mark')?.getAttribute('aria-hidden'),
    word: el.querySelector('.home-step-state')?.textContent,
    cls: el.className,
    current: el.getAttribute('aria-current'),
  })));
  const pairs = { done: ['✓', 'Done'], next: ['●', 'Next'], todo: ['○', 'To do'] };
  // Steps 4 (Test) and 6 (Go live) are not tracked by the list data: an info state with its own words, never next/done.
  const infoWords = { 3: 'Checked when you publish', 5: 'Not tracked here' };
  for (const [index, row] of rows.entries()) {
    const state = [...Object.keys(pairs), 'info'].find((k) => row.cls.includes(`home-step-${k}`));
    expect(state, `a state class on ${JSON.stringify(row)}`).toBeTruthy();
    if (index in infoWords) {
      expect(state, `step ${index + 1} is info, never next/done`).toBe('info');
      expect([row.mark, row.word]).toEqual(['–', infoWords[index]]);
    } else {
      expect(state, `step ${index + 1} is not info`).not.toBe('info');
      expect([row.mark, row.word], `mark + word for a ${state} step`).toEqual(pairs[state]);
    }
    expect(row.markHidden, 'the glyph is decoration; the word carries the state').toBe('true');
    expect(row.current).toBe(state === 'next' ? 'step' : null);
  }
  expect(rows.filter((r) => r.cls.includes('home-step-next')).length, 'at most one Next step').toBeLessThanOrEqual(1);
  await expect(page.locator('[data-step="golive"]').getByRole('link', { name: 'Open Systems' })).toHaveAttribute('href', '#/systems');
  // every state word is rendered and visible (not colour alone, not display:none)
  for (const word of await steps.locator('.home-step-state').all()) await expect(word).toBeVisible();
  await context.close();
});
