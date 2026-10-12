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

// V-1 (row 306 defect batch, lane A): a row click must make THAT version the selected one.
// Before the fix every action resolved through the screen's single selectedRef, pinned at
// mount to the FIRST draft row - so acting after clicking another row walked the pinned one
// instead (click row 2, press Approve, row 1 gets approved). Two fresh drafts are created
// through the real API (the browser's own signed-in session, like the console's typed client):
// the SECOND one is the row the click selects; the first-draft row is the row the unfixed
// screen stays pinned to. The proof is SERVER truth on a fresh mount: only the clicked row
// walked the lifecycle, the pinned first-draft row never moved.
test('submit, approve and publish walk the row that was clicked, never the pinned first draft', async ({ page }) => {
  const { pinnedId, clickedId } = await page.evaluate(async (url) => {
    const read = async (method, path, body) => {
      const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
      const answer = await fetch(url + path, {
        method,
        headers: { ...(body ? { 'content-type': 'application/json' } : {}), ...(['POST', 'PUT', 'PATCH', 'DELETE'].includes(method) && xsrf ? { 'X-XSRF-TOKEN': decodeURIComponent(xsrf.split('=').pop()) } : {}) },
        credentials: 'include',
        body: body ? JSON.stringify(body) : undefined
      });
      return { status: answer.status, data: answer.status === 0 ? null : await answer.json().catch(() => null) };
    };
    const draft = { sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' };
    const first = (await read('POST', '/api/v1/admin/flows', draft)).data;
    const second = (await read('POST', '/api/v1/admin/flows', draft)).data;
    const rows = (await read('GET', '/api/v1/admin/flows')).data ?? [];
    const pinned = rows.find((row) => row.status === 'DRAFT');
    if (!first?.id || !second?.id || !pinned) throw new Error('the new drafts were refused or no draft row lists');
    if (pinned.id === second.id) throw new Error('the clicked row is also the first draft - suite state assumption broken');
    return { pinnedId: pinned.id, clickedId: second.id };
  }, consoleUrl());

  await page.click('[data-nav="versions"]');
  await page.click(`[data-flow-row="${clickedId}"]`); // the user's row - NOT the pinned first-draft row

  await page.fill('[data-field="change-note"]', 'the clicked row, never the pinned first draft');
  await page.click('[data-action="submit-for-approval"]');
  await expect(page.locator(`[data-flow-row="${clickedId}"] [data-version-status="SUBMITTED"]`)).toBeVisible();

  await page.click('[data-action="approve-as-approver"]');
  await expect(page.locator(`[data-flow-row="${clickedId}"] [data-version-status="APPROVED"]`)).toBeVisible();

  await page.click('[data-action="publish"]');
  await expect(page.locator('[data-published="true"]')).toBeVisible();
  await expect(page.locator('[data-bundle-equals-snapshot="true"]')).toBeVisible(); // the clicked flow's own submit snapshot

  // A fresh mount re-lists from the server: the clicked row is PUBLISHED, the pinned
  // first-draft row was never acted on and still reads DRAFT.
  await page.click('[data-nav="flows"]');
  await page.click('[data-nav="versions"]');
  await expect(page.locator(`[data-flow-row="${pinnedId}"] [data-version-status="DRAFT"]`)).toBeVisible();
  await expect(page.locator(`[data-flow-row="${clickedId}"] [data-version-status="PUBLISHED"]`)).toBeVisible();
});