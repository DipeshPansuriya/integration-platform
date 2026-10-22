// E18-F1-S1 UI leg (E18-C1 "plus a browser journey"): after the Java suite has put one message through every target
// type, the signed-in console must open on its own tenant and its read APIs must answer for the same session.
// Reuses the screens protocol (happy-path.docker.spec.mjs) and adds only reads the tracking/report journey needs.
// NOT YET RUN in this session (the docker lock was held); see the E18 run report.

import { expect, test } from '@playwright/test';
import { consoleUrl } from '../screens/boot-state.mjs';

test('e2e-full UI: the signed-in shell opens the dashboard for its own tenant', async ({ page }) => {
  await page.goto(`${consoleUrl()}/#/`);
  await expect(page.locator('[data-page="dashboard"]')).toBeVisible();
  await expect(page.locator('[data-session-tenant]')).toHaveText(process.env.CONSOLE_TENANT_CODE);
});

/** A read from inside the page: the browser's own session cookie (page.request does not send it to /api: 401, dry run + run 2026-10-22). */
async function inBrowser(page, apiPath) {
  return page.evaluate(async (apiPath) => (await fetch(apiPath, { credentials: 'include' })).status, apiPath);
}

test('e2e-full UI: the tracking read answers 404 for a message the tenant does not own (no cross-tenant leak)', async ({ page }) => {
  const unknown = '00000000-0000-4000-8000-0000000000ff';
  await page.goto(`${consoleUrl()}/#/`);
  expect([403, 404]).toContain(await inBrowser(page, `/api/v1/admin/messages/${unknown}/timeline`));
});

test('e2e-full UI: the failures read answers for the signed-in tenant', async ({ page }) => {
  await page.goto(`${consoleUrl()}/#/`);
  expect(await inBrowser(page, '/api/v1/admin/messages/failures')).toBe(200);
});
