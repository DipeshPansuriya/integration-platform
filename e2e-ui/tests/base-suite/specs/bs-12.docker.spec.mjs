// T130 (row 323, F21 BS-B) BS-12 - dashboard counts from REAL rows. After BS-07 (and, once T131 lands,
// BS-10), per flow and UTC day the dashboard's queued / processed / converted / failed equal a direct count
// of message + delivery rows (lib/counts.mjs - an independent re-derivation of QueueSummaryService), with
// 0 difference and NO hand-seeded row: every counted message came through the intake API in this run
// (provenanceGaps), and no base-suite source writes a pipeline row (unit/direct-sql.test.mjs). Tenant B's
// admin sees only tenant B's flow. Counts move without a restart.
//
// Status lives in the gate (waitsOn / lib/pending-names.mjs) and the run report _reports/2026-10-22-base-suite-run.md, not in this header.

import { expect, test } from '@playwright/test';
import { FIGURES, countsOf, deriveCounts, differences, provenanceGaps } from '../lib/counts.mjs';
import { deliveryRowsSql, intakeTimingSql, messageRowsSql, outboxAggregatesSql } from '../lib/direct-sql.mjs';
import { FLOW, SOURCE_CONTRACT, TENANTS, orderRecord } from '../lib/tenants.mjs';
import { eventually, postRecord, sameAppProcess, sourceToken } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { readRows } from '../support/db.mjs';
import { addTracking, readRun } from '../support/run-file.mjs';
import { intakeCode, rotatedSecret } from '../support/seed.mjs';

test.describe.configure({ mode: 'serial' });

const QUEUE = '[data-page="queue-dashboard"]';
const SETTLED_MESSAGE = new Set(['ROUTED', 'ROUTE_FAILED']);
const SETTLED_DELIVERY = new Set(['DELIVERED', 'DEAD_LETTERED']);
const tenantCode = (tenant) => bsEnv(tenant.key === 'A' ? 'tenantA' : 'tenantB');
const ids = (sql) => readRows(sql).map(([id]) => id);

/** The tenant's rows, read directly (one read-only SELECT each). */
function rowsOf(tenant) {
  const code = tenantCode(tenant);
  return {
    messages: readRows(messageRowsSql(code)).map(([id, flowId, state, day]) => ({ id, flowId, state, day })),
    deliveries: readRows(deliveryRowsSql(code)).map(([messageId, flowId, state, day]) => ({ messageId, flowId, state, day }))
  };
}

/** Waits until no message is still ACCEPTED and no delivery in flight - then a count is a fixed point. */
async function settled(tenant) {
  return eventually(() => {
    const rows = rowsOf(tenant);
    const busy = rows.messages.some((m) => !SETTLED_MESSAGE.has(m.state)) || rows.deliveries.some((d) => !SETTLED_DELIVERY.has(d.state));
    return rows.messages.length > 0 && !busy ? rows : null;
  }, `tenant ${tenant.key}: every message and delivery row settled`, { timeoutMs: 90000 });
}

async function openQueue(page) {
  await page.goto(bsEnv('consoleUrl'));
  await page.click('[data-nav="reports"]'); // row 575 S6: queue is a tab of the reports hub now
  await page.click('[data-nav="queue"]');
  await expect(page.locator(`${QUEUE}[data-queue-loaded="true"]`)).toBeVisible();
}

/** Every (flow, day) group of the direct derivation equals the dashboard row: [] when 0 difference. */
async function dashboardDifferences(page, rows) {
  const derived = deriveCounts(rows.messages, rows.deliveries);
  const found = [];
  for (const { flowId, day } of derived.values()) {
    const row = page.locator(`[data-queue-row="${flowId}"][data-day="${day}"]`);
    await expect(row, `dashboard row for flow ${flowId} on ${day}`).toBeVisible();
    const shown = Object.fromEntries(await Promise.all(FIGURES.map(async (f) => [f, await row.getAttribute(`data-count-${f}`)])));
    const diff = differences(shown, countsOf(derived, flowId, day));
    if (diff.length > 0) found.push({ flowId, day, diff });
  }
  return found;
}

function provenance(tenant, rows) {
  const code = tenantCode(tenant);
  return provenanceGaps({
    messageIds: rows.messages.map((m) => m.id),
    trackingIds: readRun().tracking[tenant.key],
    outboxAggregateIds: ids(outboxAggregatesSql(code)),
    intakeTimingMessageIds: ids(intakeTimingSql(code)),
    deliveryMessageIds: rows.deliveries.map((d) => d.messageId)
  });
}

test("tenant A's dashboard equals a direct count of its real rows - 0 difference, no hand-seeded row", async ({ page }) => {
  const rows = await settled(TENANTS.A);
  expect(provenance(TENANTS.A, rows), 'every counted row came through the intake API in this run').toEqual([]);
  await openQueue(page);
  expect(await dashboardDifferences(page, rows)).toEqual([]);
});

test("tenant B's admin sees only tenant B's flow, and its figures equal tenant B's direct count", async ({ page }) => {
  const { seed } = readRun();
  const rows = await settled(TENANTS.B);
  expect(provenance(TENANTS.B, rows), 'tenant B rows came through the intake API').toEqual([]);
  await page.setExtraHTTPHeaders({ 'X-Harness-Login': TENANTS.B.admin });
  await openQueue(page);
  await expect(page.locator(`[data-queue-row="${seed.A.flowId}"]`), "none of tenant A's flow").toHaveCount(0);
  await expect(page.locator(`[data-queue-row="${seed.B.flowId}"]`).first(), "tenant B's own flow").toBeVisible();
  expect(await dashboardDifferences(page, rows)).toEqual([]);
});

test('the counts move without a restart: one more tenant-A post, the same 0 difference', async ({ page }) => {
  await page.goto(`${bsEnv('consoleUrl')}/`);
  const before = (await settled(TENANTS.A)).messages.length;
  const { clientId, secretValue } = await rotatedSecret(page, TENANTS.A, readRun().seed.A.credentialId);
  const answer = await postRecord(await sourceToken(clientId, secretValue), intakeCode(TENANTS.A), SOURCE_CONTRACT.versionLabel, orderRecord(TENANTS.A, 2));
  expect(answer.status, JSON.stringify(answer.data)).toBe(202);
  addTracking('A', [answer.data.trackingId]);

  const rows = await eventually(async () => {
    const now = await settled(TENANTS.A);
    return now.messages.length === before + 1 ? now : null;
  }, 'the new message is counted and settled', { timeoutMs: 90000 });
  await openQueue(page);
  expect(await dashboardDifferences(page, rows)).toEqual([]);
  expect(provenance(TENANTS.A, rows)).toEqual([]);
  expect(sameAppProcess(), 'no restart: the one app process still serves').toBe(true);
});
