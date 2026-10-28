// Lane screens-gap (IPass lane 2): the delivery-report screen (#/reports, menu "Delivery report") had NO docker
// screen spec - only the pure view model (tests/ui/delivery-report-view.test.mjs). WRITTEN, NOT RUN: the screens
// suite needs a docker-suite lock slot (all held at authoring time); the run command is in the hand-back.
//
// T-139 (E13): one UTC day, one row per interface/target - Successes, Failures (transient + ambiguous + permanent),
// Deliveries (successes + failures), Retries made, Retried then succeeded, Still stuck - read LIVE through
// GET /api/v1/reports/delivery (rides READ_MESSAGE_TIMELINE; the tenant is bound server-side, never in the path).
//
// Seeding: the flow comes from the REAL onboarding API (a fresh flow per scenario). The report reads
// public.delivery_daily_aggregate, which ONLY the DAILY_REPORT scheduler job writes (DailyReportPlaceholderHandler,
// "yesterday" per active tenant) - no API produces it, so, as failures/message-detail do for message rows, the
// aggregate row goes into the suite's own pinned postgres as owner (screen-sql.mjs binds app.tenant_id for FORCE
// RLS). Each scenario reads its OWN past day (utcDay(-10) to utcDay(-13): the report refuses a day older than 31 days,
// DeliveryReportController.java:33,40), so no other spec's data can land in its figures.

import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';
import { freshFlow, psql, tenantIdOf } from './screen-fixtures.mjs';
import { aggregateRowSql, utcDay } from './screen-sql.mjs';

const PAGE = '[data-page="delivery-report"]';
const READY = `${PAGE} [data-delivery-report]`;

/** The read finished: no loading line and the table is up (the page unmounts the table while it re-reads). */
async function settled(page) {
  await expect(page.locator(`${PAGE} [data-loading]`)).toHaveCount(0);
  await expect(page.locator(READY)).toBeVisible();
}

async function openReport(page, day) {
  await page.goto(consoleUrl());
  await page.click('[data-nav="reports"]');
  await expect(page.locator(READY)).toBeVisible();
  if (day !== undefined) {
    await page.locator('[data-day-filter]').fill(day);
    await settled(page);
  }
}

test('the report opens on today (UTC) with the eight named columns', async ({ page }) => {
  await openReport(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Delivery report' })).toBeVisible();
  await expect(page.locator('[data-day-filter]')).toHaveValue(utcDay(0));
  await expect(page.locator(`${READY} th[scope="col"]`)).toHaveText(
    ['Interface', 'Target', 'Successes', 'Failures', 'Deliveries', 'Retries made', 'Retried then succeeded', 'Still stuck']);
  await expect(page).toHaveURL(/#\/reports$/);
});

test('a day with an aggregate shows one row per interface/target: failures collapse the three classes, deliveries add the successes', async ({ page }) => {
  const flow = await freshFlow(page, 'figures');
  const target = randomUUID();
  const otherTarget = randomUUID();
  psql(aggregateRowSql({
    tenantId: tenantIdOf(), day: utcDay(-10), interfaceId: flow.id, targetId: target,
    successes: 5, failuresTransient: 3, failuresAmbiguous: 1, failuresPermanent: 2,
    retriesMade: 4, retriedThenSucceeded: 2, stillStuck: 1
  }));
  psql(aggregateRowSql({ tenantId: tenantIdOf(), day: utcDay(-10), interfaceId: flow.id, targetId: otherTarget, successes: 7 }));

  await openReport(page, utcDay(-10));
  const rows = page.locator(`[data-report-row^="${flow.id}/"]`);
  await expect(rows).toHaveCount(2);

  const first = page.locator(`[data-report-row="${flow.id}/${target}"] td`);
  await expect(first).toHaveText([flow.id, target, '5', '6', '11', '4', '2', '1']);
  const second = page.locator(`[data-report-row="${flow.id}/${otherTarget}"] td`);
  await expect(second).toHaveText([flow.id, otherTarget, '7', '0', '7', '0', '0', '0']);
});

test('changing the day re-reads: a day with no aggregate lists no rows, going back brings the figures back', async ({ page }) => {
  const flow = await freshFlow(page, 'day-change');
  const target = randomUUID();
  psql(aggregateRowSql({ tenantId: tenantIdOf(), day: utcDay(-11), interfaceId: flow.id, targetId: target, successes: 9 }));

  await openReport(page, utcDay(-11));
  await expect(page.locator(`[data-report-row="${flow.id}/${target}"]`)).toBeVisible();

  await page.locator('[data-day-filter]').fill(utcDay(-12));
  await settled(page);
  await expect(page.locator(`[data-report-row="${flow.id}/${target}"]`)).toHaveCount(0);
  await expect(page.locator('[data-report-row]')).toHaveCount(0);

  await page.locator('[data-day-filter]').fill(utcDay(-11));
  await expect(page.locator(`[data-report-row="${flow.id}/${target}"] td`).nth(2)).toHaveText('9');
});

test('keyboard and AT: the day input is reachable by Tab, its label names the UTC day, headers are column-scoped', async ({ page }) => {
  await openReport(page);
  const day = page.locator('[data-day-filter]');
  await expect(page.getByLabel(/^Day \(/)).toHaveCount(1);
  for (let i = 0; i < 20 && !(await day.evaluate((el) => el === document.activeElement)); i += 1) await page.keyboard.press('Tab');
  await expect(day).toBeFocused();
  expect(await day.evaluate((el) => getComputedStyle(el).outlineStyle !== 'none'), 'a visible focus indicator').toBe(true);
  await expect(page.locator(`${READY} th:not([scope="col"])`)).toHaveCount(0);
});

// Found by reading, UNVERIFIED by a run: DeliveryReport.tsx renders the date <input> only while `day` is truthy
// (the ternary at the label), and the input's onChange sets `day` to ''. Clearing the field (Delete/Backspace in a
// date input fires change with '') therefore replaces the input with the error alert - the user cannot pick another
// day without reloading. The expectation below is the sensible behaviour; it should fail until the input stays.
test.fixme('clearing the day keeps the input so another day can be picked (product defect: DeliveryReport.tsx unmounts the input when day is empty)', async ({ page }) => {
  await openReport(page);
  await page.locator('[data-day-filter]').fill('');
  await expect(page.locator('[role="alert"][data-refusal="delivery-report-error"]').first()).toBeVisible();
  await expect(page.locator('[data-day-filter]')).toBeVisible();
  await page.locator('[data-day-filter]').fill(utcDay(-1));
  await expect(page.locator(READY)).toBeVisible();
});

// @negative @tenant: tenant B's READ_ONLY reader passes the role check; the report is cut by tenant scoping
// (the tenant never travels in the path, FORCE RLS backs the read) - an empty table, never a refusal and never A's figures.
test("@negative @tenant the other tenant's reader sees none of tenant A's report rows for the same day", async ({ page }) => {
  const flow = await freshFlow(page, 'tenant-reader');
  const target = randomUUID();
  psql(aggregateRowSql({ tenantId: tenantIdOf(), day: utcDay(-13), interfaceId: flow.id, targetId: target, successes: 3 }));

  await openReport(page, utcDay(-13));
  await expect(page.locator(`[data-report-row="${flow.id}/${target}"]`)).toBeVisible(); // A sees its own row

  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant-reader' });
  await openReport(page, utcDay(-13));
  await expect(page.locator('[role="alert"][data-refusal]')).toHaveCount(0);
  await expect(page.locator(`[data-report-row="${flow.id}/${target}"]`)).toHaveCount(0);
  await expect(page.locator('[data-report-row]')).toHaveCount(0);
});

// @negative @role: the report rides READ_MESSAGE_TIMELINE; the other-tenant binding is a CONFIGURATOR of tenant B -
// refused (403) with the no-access wording, and no table (so no figures) is rendered.
test("@negative @role the other tenant's configurator is refused with the permission wording and sees no table", async ({ page }) => {
  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant' });
  await page.goto(consoleUrl());
  await page.click('[data-nav="reports"]');
  await expect(page.locator('[role="alert"][data-refusal="delivery-report-refused"]')).toHaveText(
    'You do not have access to this page. Ask an administrator if you need it.');
  await expect(page.locator('[data-delivery-report]')).toHaveCount(0);
});
