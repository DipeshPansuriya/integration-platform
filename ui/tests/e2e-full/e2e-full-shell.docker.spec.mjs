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

test('e2e-full UI: the tracking read answers 404 for a message the tenant does not own (no cross-tenant leak)', async ({ page }) => {
  const unknown = '00000000-0000-4000-8000-0000000000ff';
  const answer = await page.request.get(`${consoleUrl()}/api/v1/admin/messages/${unknown}/timeline`);
  expect([403, 404]).toContain(answer.status());
});

test('e2e-full UI: the failures read answers for the signed-in tenant', async ({ page }) => {
  const answer = await page.request.get(`${consoleUrl()}/api/v1/admin/messages/failures`);
  expect(answer.status()).toBe(200);
});
