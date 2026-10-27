// Systems register panel vs the destination allow-list (row 466), FAILING FIRST: a team member's UI save of a system on an external
// https base address was refused with "settings.baseUrl is refused: the host is not on the destination allow-list" and no suite
// showed it, because every other spec saves on a host this boot already allow-lists (screen-hosts.mjs). Here the SAME save goes
// through the SAME form twice against the REAL API: once on a host the allow-list does not cover (refused, shown in the red
// alert, system NOT listed) and once on the listed host (saved, system listed). Backend side: TestConnectionContainerTest.java:173.
//
// Hosts are the suite's own fixture names (never resolved or called): SCREEN_UNLISTED_HOST is kept off the list by
// unit/screen-hosts.test.mjs, partner.example.test is on it (SCREEN_ALLOWED_HOSTS).

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';
import { SCREEN_UNLISTED_HOST } from './screen-hosts.mjs';

const PAGE = '[data-page="systems"]';
const RUN = Date.now().toString(36);
const REFUSAL = 'settings.baseUrl is refused: the host is not on the destination allow-list';

async function openSystems(page) {
  await page.goto(consoleUrl());
  await page.click('[data-nav="systems"]');
  await expect(page.locator(`${PAGE}[data-systems-loaded="true"]`)).toBeVisible();
}

/** The register drawer, filled as an Outbound API system on the given base address, saved. */
async function registerApiSystem(page, { code, name, baseUrl }) {
  await page.click('[data-action="open-register"]');
  const form = page.getByRole('dialog', { name: 'Register a system' }).locator('[data-form="register-system"]');
  await form.locator('[data-field="system-code"]').fill(code);
  await form.locator('[data-field="system-name"]').fill(name);
  await form.locator('[data-field="system-direction"]').selectOption('OUTBOUND');
  await form.locator('[data-field="system-type"]').fill('API');
  await form.locator('[data-setting="baseUrl"]').fill(baseUrl);
  await page.click('[data-action="register-system"]');
}

test('an Outbound API system on a host off the allow-list is refused, the red text is shown and the system is not listed', async ({ page }) => {
  const code = `sys-unlisted-${RUN}`;
  await openSystems(page);
  const unlistedUrl = `https://${SCREEN_UNLISTED_HOST}/orders`; // by name, so unit/screen-hosts.test.mjs does not treat it as a fixture host that must be listed
  await registerApiSystem(page, { code, name: 'Unlisted partner', baseUrl: unlistedUrl });

  const alert = page.getByRole('alert').filter({ hasText: 'The save was refused:' });
  await expect(alert).toBeVisible();
  await expect(alert).toHaveClass(/systems-alert-danger/); // the red danger alert, not a neutral note
  await expect(alert.locator('[data-validation-error]')).toHaveText([REFUSAL]); // the one error, the server's words
  await page.reload();
  await expect(page.locator(`${PAGE}[data-systems-loaded="true"]`)).toBeVisible();
  await expect(page.locator(`[data-system-row="${code}"]`)).toHaveCount(0); // nothing was stored
});

test('the same save on the allow-listed host succeeds and the system appears in the register', async ({ page }) => {
  const code = `sys-listed-${RUN}`;
  await openSystems(page);
  await registerApiSystem(page, { code, name: 'Listed partner', baseUrl: 'https://partner.example.test/orders' });

  await expect(page.getByText('The save was refused:')).toHaveCount(0);
  await expect(page.locator(`[data-system-row="${code}"]`)).toBeVisible();
});
