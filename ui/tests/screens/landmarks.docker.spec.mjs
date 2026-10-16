// Row 310 B17 (sanjay-developer): exactly ONE main landmark on every screen, measured in the real
// console through the accessibility tree (getByRole('main')), never source text.
//  - every shell route: the shell's <main className="content"> is the only main; the page root inside it is not;
//  - #/login without a session: the sign-in card renders without the shell and keeps its own single main.

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

const NO_SESSION = { storageState: { cookies: [], origins: [] } };

const SHELL_ROUTES = [
  ['#/', 'dashboard'],
  ['#/flows', 'flows-list'],
  ['#/flow/new', 'flow-definition'],
  ['#/versions', 'versions'],
  ['#/systems', 'systems'],
  ['#/queue', 'queue-dashboard'],
  ['#/contracts', 'contracts'],
  ['#/rules', 'rules-home'],
  ['#/mappings', 'mapping-designer'],
];

for (const [hash, dataPage] of SHELL_ROUTES) {
  test(`${hash}: one main landmark (the shell's), the page root is not a second one`, async ({ page }) => {
    await page.goto(`${consoleUrl()}/${hash}`);
    await expect(page.locator(`[data-page="${dataPage}"]`)).toBeVisible();
    await expect(page.getByRole('main')).toHaveCount(1);
  });
}

test('#/login without a session: the sign-in card keeps its single main landmark', async ({ browser }) => {
  const context = await browser.newContext(NO_SESSION);
  const page = await context.newPage();
  await page.goto(`${consoleUrl()}/#/login`);
  await expect(page.locator('[data-page="login"]')).toBeVisible();
  await expect(page.getByRole('main')).toHaveCount(1);
  await context.close();
});
