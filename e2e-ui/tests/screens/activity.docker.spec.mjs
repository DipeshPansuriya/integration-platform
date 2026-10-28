// Lane screens-gap (IPass lane 2): the activity view (#/activity, #/activity/<eventId>) had NO docker screen
// spec - only the pure view model (tests/ui/activity-view.test.mjs). WRITTEN, NOT RUN: the screens suite
// needs a docker-suite lock slot (all held at authoring time); the run command is in the hand-back.
//
// E12-F2-S2 "I see only my own tenant's activity": a console user opens Activity (menu, Overview group) and sees
// the bound tenant's configuration changes newest first - When (a link to the event), Actor, Action, Targets -
// narrowed by an exact Action and inclusive UTC From/To days, 50 per page. One event shows before/after as plain
// text. Another tenant's event id answers 404 and reads exactly like a missing one.
//
// Seeding: the REAL config API from the signed-in page (POST/PUT /api/v1/admin/flows write FLOW_CREATED and
// FLOW_DRAFT_SAVED audit events, FlowAdminService.java:90,122) - no psql rows. Selectors are the page's own
// data-* attributes (src/pages/Activity.tsx) and its labelled inputs.

import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';
import { freshFlow, inBrowser, meOf } from './screen-fixtures.mjs';
import { utcDay } from './screen-sql.mjs';

const PAGE = '[data-page="activity"]';
const LOADED = `${PAGE}[data-activity-loaded="true"]`;
const RUN = Date.now().toString(36);

async function openActivity(page) {
  await page.goto(consoleUrl());
  await page.click('[data-nav="activity"]');
  await expect(page.locator(LOADED)).toBeVisible();
}

async function applyFilter(page, { action, from, to }) {
  if (action !== undefined) await page.getByLabel('Action', { exact: true }).fill(action);
  if (from !== undefined) await page.getByLabel('From', { exact: true }).fill(from);
  if (to !== undefined) await page.getByLabel('To', { exact: true }).fill(to);
  await page.click('[data-action="apply-activity-filter"]');
}

/** A flow with a second audit event: onboard it, then save a new draft of it (FLOW_CREATED then FLOW_DRAFT_SAVED). */
async function flowWithTwoEvents(page, scenario) {
  const flow = await freshFlow(page, scenario);
  const detail = (await inBrowser(page, 'GET', `/api/v1/admin/flows/${flow.id}`)).data;
  const saved = await inBrowser(page, 'PUT', `/api/v1/admin/flows/${flow.id}`, {
    sourceSystem: detail.sourceSystem, destinations: detail.destinations,
    businessKeyRule: detail.businessKeyRule, ordering: detail.ordering
  });
  expect(saved.status, `scenario ${scenario} saves a draft: ${JSON.stringify(saved.data)}`).toBeLessThan(300);
  return flow;
}

/** The bound tenant's own events for one flow, read through the API the page reads (newest first). */
async function eventsOf(page, flowId) {
  const list = await inBrowser(page, 'GET', '/api/v1/activity?size=200');
  expect(list.status).toBe(200);
  return list.data.items.filter((item) => (item.targetIds ?? []).includes(`interface:${flowId}`));
}

const actionCells = (page) => page.locator('[data-activity-row] td:nth-child(3)');

test('a flow change appears newest first with its actor, action and targets; Activity is reachable from the menu', async ({ page }) => {
  const flow = await flowWithTwoEvents(page, 'rows');
  const me = await meOf(page);

  await openActivity(page);
  await expect(page).toHaveURL(/#\/activity$/);
  await expect(page.getByRole('heading', { name: 'Activity', exact: true })).toBeVisible();

  const mine = page.locator(`[data-activity-row]:has-text("interface:${flow.id}")`);
  await expect(mine).toHaveCount(2);
  // newest first: the draft save above the creation
  await expect(mine.nth(0).locator('td').nth(2)).toHaveText('FLOW_DRAFT_SAVED');
  await expect(mine.nth(1).locator('td').nth(2)).toHaveText('FLOW_CREATED');
  await expect(mine.nth(1).locator('[data-actor-cell]')).toHaveText(me.userId);
  await expect(mine.nth(1).locator('td').nth(3)).toContainText(`interface:${flow.id}`);
  await expect(mine.nth(1).locator('td').nth(3)).toContainText('interface_version:');
  // the When cell is a link to the event, with a machine-readable time
  await expect(mine.nth(1).locator('td').first().locator('a time[datetime]')).toBeVisible();
  await expect(page.locator('table[aria-label="Activity"] th[scope="col"]')).toHaveText(['When', 'Actor', 'Action', 'Targets']);
});

test('the Action filter is an exact match; a filter that matches nothing is announced, not blank', async ({ page }) => {
  const flow = await flowWithTwoEvents(page, 'action-filter');
  await openActivity(page);

  await applyFilter(page, { action: 'FLOW_DRAFT_SAVED' });
  await expect.poll(async () => (await actionCells(page).allTextContents()).every((text) => text === 'FLOW_DRAFT_SAVED')).toBe(true);
  await expect(page.locator(`[data-activity-row]:has-text("interface:${flow.id}")`)).toHaveCount(1);

  await applyFilter(page, { action: `NO_SUCH_ACTION_${RUN}` });
  await expect(page.locator('td[data-empty="activity-none"]')).toHaveText('No activity matches the filter.');
  await expect(page.locator('[data-activity-row]')).toHaveCount(0);
  await expect(page.locator('[data-action="activity-previous"]')).toBeDisabled();
  await expect(page.locator('[data-action="activity-next"]')).toBeDisabled();

  await applyFilter(page, { action: '' });
  await expect(page.locator(`[data-activity-row]:has-text("interface:${flow.id}")`)).toHaveCount(2);
});

test('the From/To days are inclusive UTC days: today keeps the event, tomorrow onward and yesterday backward drop it', async ({ page }) => {
  const flow = await freshFlow(page, 'date-filter');
  const mine = `[data-activity-row]:has-text("interface:${flow.id}")`;
  await openActivity(page);

  await applyFilter(page, { from: utcDay(0), to: utcDay(0) });
  await expect(page.locator(mine)).toHaveCount(1);

  await applyFilter(page, { from: utcDay(1), to: '' });
  await expect(page.locator('td[data-empty="activity-none"]')).toBeVisible();
  await expect(page.locator(mine)).toHaveCount(0);

  await applyFilter(page, { from: '', to: utcDay(-1) });
  await expect(page.locator(mine)).toHaveCount(0);
});

test('one event opens from its When link with before/after as plain text, and Back returns to the list', async ({ page }) => {
  const flow = await freshFlow(page, 'detail');
  const [event] = await eventsOf(page, flow.id);
  expect(event?.action, 'the create wrote its FLOW_CREATED event').toBe('FLOW_CREATED');

  await openActivity(page);
  await page.locator(`[data-activity-row]:has-text("interface:${flow.id}") td a`).first().click();
  await expect(page).toHaveURL(new RegExp(`#/activity/${event.id}$`));
  const detail = page.locator(`[data-activity-event="${event.id}"]`);
  await expect(detail).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Activity event' })).toBeVisible();
  await expect(detail.locator('dt')).toHaveText(['When', 'Actor', 'Action', 'Targets', 'Before', 'After']);
  await expect(detail.locator('dd').nth(2)).toHaveText('FLOW_CREATED');
  await expect(detail.locator('dd').nth(3)).toContainText(`interface:${flow.id}`);
  // plain text, exactly the strings the API holds (never rendered as markup)
  await expect(detail.locator('dd pre').nth(0)).toHaveText(event.before ?? '');
  await expect(detail.locator('dd pre').nth(1)).toHaveText(event.after);
  expect(event.after).toContain('"status":"DRAFT"');

  await page.click('[data-action="back-to-activity"]');
  await expect(page).toHaveURL(/#\/activity$/);
  await expect(page.locator(LOADED)).toBeVisible();
});

test('an event id nobody has reads "Activity event not found."', async ({ page }) => {
  await page.goto(consoleUrl());
  await expect(page.locator('nav')).toBeVisible();
  await page.goto(`${consoleUrl()}/#/activity/9223372036854775800`);
  await expect(page.locator('[role="alert"][data-refusal="activity-not-found"]')).toHaveText('Activity event not found.');
  await expect(page.locator('[data-activity-event] dl')).toHaveCount(0);
});

test('keyboard: the filter inputs are labelled, Enter in a field applies the filter, the When link is focusable', async ({ page }) => {
  const flow = await flowWithTwoEvents(page, 'keyboard');
  await openActivity(page);
  const action = page.getByLabel('Action', { exact: true });
  await action.fill('FLOW_CREATED');
  await action.press('Enter'); // the filter is a <form>: Enter submits it
  await expect.poll(async () => (await actionCells(page).allTextContents()).every((text) => text === 'FLOW_CREATED')).toBe(true);
  const link = page.locator(`[data-activity-row]:has-text("interface:${flow.id}") td a`).first();
  await link.focus();
  await expect(link).toBeFocused();
  expect(await link.evaluate((el) => getComputedStyle(el).outlineStyle), 'visible focus ring').toBe('solid');
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/#\/activity\/\d+$/);
});

// Paging: 50 per page (PAGE_SIZE, Activity.tsx). The empty-tenant binding (a TENANT_ADMIN of tenant C, whose
// activity no other spec reads) onboards 51 flows through the real API, so page 1 is full and Next is live.
test('paging: with more than 50 events Next opens the older page and Previous returns', async ({ page }) => {
  test.setTimeout(180_000);
  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'empty-tenant' });
  await page.goto(consoleUrl());
  await expect(page.locator('nav')).toBeVisible();
  const made = await page.evaluate(async () => {
    const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
    const token = decodeURIComponent(xsrf.slice('XSRF-TOKEN='.length));
    let created = 0;
    for (let i = 0; i < 51; i += 1) {
      const answer = await fetch('/api/v1/admin/flows', {
        method: 'POST', credentials: 'include',
        headers: { 'content-type': 'application/json', 'X-XSRF-TOKEN': token },
        body: JSON.stringify({ sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' })
      });
      if (answer.ok) created += 1;
    }
    return created;
  });
  expect(made, 'all 51 onboardings answered 2xx').toBe(51);

  await openActivity(page);
  await expect(page.locator('[data-activity-row]')).toHaveCount(50);
  await expect(page.locator('[data-action="activity-previous"]')).toBeDisabled();
  await expect(page.locator('[data-action="activity-next"]')).toBeEnabled();
  const rows = page.locator('[data-activity-row]');
  const newestId = await rows.first().getAttribute('data-activity-row');
  await page.click('[data-action="activity-next"]');
  // page 2 holds the older events (how many more pages there are depends on the events earlier specs left)
  await expect(rows.first()).not.toHaveAttribute('data-activity-row', newestId);
  await expect(page.locator('[data-action="activity-previous"]')).toBeEnabled();
  await page.click('[data-action="activity-previous"]');
  await expect(rows.first()).toHaveAttribute('data-activity-row', newestId);
  await expect(rows).toHaveCount(50);
});

// @negative @tenant: tenant B's READ_ONLY reader gets past the role check and sees an empty view of A's changes;
// A's event id read as B is the same 404 as a missing id - never a hint that it exists.
test("@negative @tenant the other tenant's reader sees none of tenant A's events and A's event id reads as not found", async ({ page }) => {
  const flow = await freshFlow(page, 'tenant-reader');
  const [event] = await eventsOf(page, flow.id);
  expect(event?.id, "tenant A's event id").toBeTruthy();

  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant-reader' });
  await openActivity(page);
  await expect(page.locator('[role="alert"][data-refusal]')).toHaveCount(0);
  await expect(page.locator(`[data-activity-row]:has-text("interface:${flow.id}")`)).toHaveCount(0);

  await page.goto(`${consoleUrl()}/#/activity/${event.id}`);
  await expect(page.locator('[role="alert"][data-refusal="activity-not-found"]')).toHaveText('Activity event not found.');
  await expect(page.locator('[data-activity-event] dl')).toHaveCount(0);
});

// @negative @role: the view rides READ_MESSAGE_TIMELINE (tenant admin, support, read-only); the other-tenant
// binding is a CONFIGURATOR of tenant B - refused outright with the no-access words, listing nothing.
test("@negative @role the other tenant's configurator is refused with the no-access state", async ({ page }) => {
  const flow = await freshFlow(page, 'role');
  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant' });
  await openActivity(page);
  await expect(page.locator('[role="alert"][data-refusal="activity-no-access"]'))
    .toHaveText('You do not have access to this page. Ask an administrator if you need it.');
  await expect(page.locator(`[data-activity-row]:has-text("interface:${flow.id}")`)).toHaveCount(0);
});
