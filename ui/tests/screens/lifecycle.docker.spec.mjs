// T-022 (E3 lane D, row 278) - the version + approval screens, FAILING FIRST.
//
// Scenario "The lifecycle": a draft flow version is submitted (changeNote body - the T-093
// contract), approved by ANOTHER user, and published; the published bundle equals the snapshot
// taken at submit, byte for byte. Author-never-approver is enforced server-side; the screen
// shows the refusal, never hides it (E3-F2-S1). All against the REAL API - no mock (E3-F3-S1).
//
// RED at this tip: the screens do not exist and lane C's T-019/T-021 lifecycle API is not
// merged into row278-integration yet.

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

test.beforeEach(async ({ page }) => {
  await page.goto(consoleUrl());
});

test('submit for approval sends the changeNote body through the real API and lists the submitted version', async ({ page }) => {
  await page.click('[data-nav="versions"]');
  await page.click('[data-page="versions"]');

  await page.fill('[data-field="change-note"]', 'first version of the orders flow');
  await page.click('[data-action="submit-for-approval"]');

  await expect(page.locator('[data-version-status="SUBMITTED"]').first()).toBeVisible();
});

test('the author cannot approve: the refusal is shown on the screen, never hidden', async ({ page }) => {
  await page.click('[data-nav="versions"]');
  await page.click('[data-action="submit-for-approval"]'); // the current user is the author
  await page.click('[data-action="approve"]');

  await expect(page.locator('[data-refusal="author-cannot-approve"]')).toBeVisible();
});

test('another user approves and the publish screen offers the approved version', async ({ page }) => {
  await page.click('[data-nav="versions"]');
  await page.click('[data-action="submit-for-approval"]');
  // the second seeded user (an approver who is not the author) approves through the API; the
  // console's approve screen is where the approver acts on their own login - the harness's
  // second binding drives it
  await page.click('[data-action="approve-as-approver"]');
  await expect(page.locator('[data-version-status="APPROVED"]').first()).toBeVisible();
  await expect(page.locator('[data-action="publish"]')).toBeEnabled();
});

test('the published bundle equals the snapshot taken at submit, byte for byte, on the publish screen', async ({ page }) => {
  await page.click('[data-nav="versions"]');
  await page.click('[data-action="submit-for-approval"]');
  await page.click('[data-action="approve-as-approver"]');
  await page.click('[data-action="publish"]');

  await expect(page.locator('[data-published="true"]')).toBeVisible();
  await expect(page.locator('[data-bundle-equals-snapshot="true"]')).toBeVisible(); // byte-for-byte compare rendered by the screen
});

test('a submitted version cannot be edited: the refused Save draft is shown, a new draft is the only way', async ({ page }) => {
  await page.click('[data-nav="versions"]');
  await page.click('[data-action="submit-for-approval"]');
  await page.click('[data-action="save-draft"]');

  await expect(page.locator('[data-refusal="submitted-version-immutable"]')).toBeVisible();
  await expect(page.locator('[data-action="new-draft"]')).toBeVisible();
});