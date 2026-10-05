// T-017 (E3 lane D, row 278) - the flow-definition screen, FAILING FIRST.
//
// A configurator creates a flow with source system S and destinations T1/T2 and saves an
// interface with its mandatory business-key rule, its ordering setting (PER_KEY default,
// ADR-011) and its sourceSchema draft. Save draft hits the REAL API (E3-F3-S1: no mock,
// no MSW, no stub layer between the screen and the API - the two earlier UI-API defects
// died on a mock). Boot protocol: the same container harness boot-backend.mjs (docker-tagged,
// one suite at a time), the built console served by vite preview proxying /api to the booted
// backend.
//
// RED at this tip: the screen does not exist, and lane C's T-015 flow admin API is not merged
// into row278-integration yet. The red run's failures name what is missing.

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

test.beforeEach(async ({ page }) => {
  await page.goto(consoleUrl());
});

test('the console has a flow-definition screen the navigable menu reaches', async ({ page }) => {
  await page.click('[data-nav="flows"]');
  await expect(page.locator('[data-page="flow-definition"]')).toBeVisible();
});

test('Save draft sends the business-key rule, the ordering setting (PER_KEY default) and the sourceSchema, and the REAL API answers 200', async ({ page }) => {
  await page.click('[data-nav="flows"]');
  await page.click('[data-page="flow-definition"]');

  await page.fill('[data-field="source-system"]', 'S');
  await page.fill('[data-field="destination-1"]', 'T1');
  await page.fill('[data-field="destination-2"]', 'T2');
  await page.fill('[data-field="interface-name"]', 'orders');
  await page.fill('[data-field="business-key-rule"]', 'customer_id, order_date');
  await page.fill('[data-field="source-schema"]', '{"type":"object","properties":{"customer_id":{"type":"string"}}}');

  const response = page.waitForResponse((r) => r.status() === 200);
  await page.click('[data-action="save-draft"]');
  await expect(response).toBeTruthy();
  await expect(page.locator('[data-saved="true"]')).toBeVisible();
});

test('an interface without its mandatory business-key rule is refused by the real API and the screen shows the clear validation error', async ({ page }) => {
  await page.click('[data-nav="flows"]');
  await page.click('[data-page="flow-definition"]');

  await page.fill('[data-field="source-system"]', 'S');
  await page.fill('[data-field="destination-1"]', 'T1');
  await page.fill('[data-field="interface-name"]', 'orders');
  // no business-key rule: the API refused this in the deleted build (E3-F1-S1 @negative)

  await page.click('[data-action="save-draft"]');
  await expect(page.locator('[data-validation-error="business-key-rule"]')).toBeVisible();
});

test('the ordering setting defaults to PER_KEY (ADR-011) and persists on save', async ({ page }) => {
  await page.click('[data-nav="flows"]');
  await page.click('[data-page="flow-definition"]');

  const setting = page.locator('[data-field="ordering-setting"]');
  await expect(setting).toHaveValue('PER_KEY');
  await page.fill('[data-field="interface-name"]', 'orders');
  await page.fill('[data-field="business-key-rule"]', 'customer_id');
  await page.click('[data-action="save-draft"]');
  await page.reload();
  await expect(setting).toHaveValue('PER_KEY');
});