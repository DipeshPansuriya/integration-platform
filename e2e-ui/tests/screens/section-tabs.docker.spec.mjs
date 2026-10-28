// Row 575 S6: the Reports hub (Queue | Daily delivery | Activity) and the Systems tabs (Systems | Contracts) in the real console.
// Tabs are links with aria-current="page"; the legacy routes load directly; the menu no longer lists Queue, Activity or Contracts.
import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

const TABS = (label) => `nav[aria-label="${label}"]`;

test('Scenario: the Reports item opens the hub; each tab is a link and the current one says so', async ({ page }) => {
  await page.goto(consoleUrl());
  await page.click('[data-nav="reports"]');
  const tabs = page.locator(TABS('Reports sections'));
  await expect(tabs).toBeVisible();
  await expect(tabs.locator('a')).toHaveText(['Queue', 'Daily delivery', 'Activity']);
  await expect(tabs.locator('a[aria-current="page"]')).toHaveText('Daily delivery');
  await tabs.locator('a[data-nav="queue"]').click();
  await expect(page.locator('[data-page="queue-dashboard"]')).toBeVisible();
  await expect(tabs.locator('a[aria-current="page"]')).toHaveText('Queue');
  await tabs.locator('a[data-nav="activity"]').click();
  await expect(tabs.locator('a[aria-current="page"]')).toHaveText('Activity');
  await expect(page.locator('nav[aria-label="Primary"] [data-nav="reports"]')).toHaveAttribute('aria-current', 'true');
});

test('Scenario: the Systems item and its Contracts tab', async ({ page }) => {
  await page.goto(consoleUrl());
  await page.click('[data-nav="systems"]');
  const tabs = page.locator(TABS('Systems sections'));
  await expect(tabs.locator('a')).toHaveText(['Systems', 'Contracts']);
  await expect(tabs.locator('a[aria-current="page"]')).toHaveText('Systems');
  await tabs.locator('a[data-nav="contracts"]').click();
  await expect(page.locator('[data-page="contracts"]')).toBeVisible();
  await expect(tabs.locator('a[aria-current="page"]')).toHaveText('Contracts');
});

for (const [hash, label] of [['#/queue', 'Reports sections'], ['#/activity', 'Reports sections'], ['#/reports', 'Reports sections'], ['#/contracts', 'Systems sections']]) {
  test(`Scenario: ${hash} still loads directly, with its tabs`, async ({ page }) => {
    await page.goto(`${consoleUrl()}/${hash}`);
    await expect(page.locator(TABS(label))).toBeVisible();
    await expect(page.locator(`${TABS(label)} a[aria-current="page"]`)).toHaveCount(1);
  });
}

test('Scenario: the menu no longer lists Queue, Activity or Contracts as items of their own', async ({ page }) => {
  await page.goto(consoleUrl());
  const menu = page.locator('nav[aria-label="Primary"]');
  for (const nav of ['queue', 'activity', 'contracts']) await expect(menu.locator(`[data-nav="${nav}"]`)).toHaveCount(0);
});
