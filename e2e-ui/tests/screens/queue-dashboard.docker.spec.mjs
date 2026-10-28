// F10-narrow (lane D, row278 release-base plan step 8, sanctioned by the project owner 2026-10-13) - the queue
// dashboard screens, FAILING FIRST.
//
// The narrow scope: live counts only. A console user opens #/queue and sees, per flow and day,
// how much work is queued (a message still ACCEPTED, or a delivery in a non-final state:
// PENDING/HELD/SENDING/RETRY_WAIT/DUE), how many messages the routing plane processed (ROUTED),
// how many delivered their contract-translated output (DELIVERED), and how much failed
// (ROUTE_FAILED messages + DEAD_LETTERED deliveries). Every figure is derived LIVE from
// public.message + public.delivery through GET /api/v1/admin/queue/summary - no hand-maintained
// counters anywhere. The counts move when the state tables move.
//
// Seeding: each scenario onboards its OWN fresh flow through the real API from the signed-in page
// (session + CSRF), so the lifecycle journey's state can never leak into an exact count; the
// message/delivery state rows are inserted straight into the suite's own pinned postgres
// (`ipass-ui-t41-pg-*`, as owner - fixtures, never production paths; the app itself reads only).

import { execFileSync } from 'node:child_process';
import { suitePgContainer } from './suite-pg.mjs';
import process from 'node:process';
import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

const PAGE = '[data-page="queue-dashboard"]';
const RUN = Date.now().toString(36);

/** Reads today's UTC date (postgres' now() runs in the container's UTC clock, so both agree). */
const DAY = new Date().toISOString().slice(0, 10);

/** One API call from inside the page: the browser's own session and CSRF cookies, like the console's client. */
async function inBrowser(page, method, apiPath, body) {
  return page.evaluate(
    async ({ method, apiPath, body }) => {
      const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
      const headers = {};
      if (body !== undefined) headers['content-type'] = 'application/json';
      if (method !== 'GET' && xsrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf.slice('XSRF-TOKEN='.length));
      const answer = await fetch(apiPath, { method, headers, credentials: 'include', body: body === undefined ? undefined : JSON.stringify(body) });
      return { status: answer.status, data: await answer.json().catch(() => null) };
    },
    { method, apiPath, body }
  );
}

/** The suite's own pinned postgres (boot-backend names it ipass-ui-t41-pg-*) - fixtures in, counts out. */
function psql(sql) {
  const names = [suitePgContainer()]; // L22: this run's postgres (IPASS_UI_PG_CONTAINER), else the single running one
  return execFileSync('docker', ['exec', names[0], 'psql', '-U', 'owner', '-d', 'platform', '-t', '-A', '-c', sql],
    { encoding: 'utf8' }).trim();
}

/** The signed-in tenant's id, looked up by the harness tenant code. */
function tenantIdOf() {
  const id = psql(`select id::text from public.tenant where code = '${process.env.CONSOLE_TENANT_CODE}'`);
  expect(id, 'the harness tenant row exists').not.toBe('');
  return id;
}

/** Onboards ONE fresh flow through the real onboarding API; its own flow keeps the scenario deterministic. */
async function freshFlow(page, scenario) {
  await page.goto(consoleUrl()); // the browser session must exist before the page-side fetch
  await expect(page.locator('nav')).toBeVisible();
  const created = await inBrowser(page, 'POST', '/api/v1/admin/flows',
    { sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' });
  expect(created?.data?.id, `scenario ${scenario} onboards a flow: ${JSON.stringify(created?.data ?? created)}`).toBeTruthy();
  return { id: created.data.id, scenario };
}

function seedMessage(tenantId, flowId, state, index, at) {
  psql(`insert into public.message (tenant_id, interface_id, interface_version_id, source_contract_version_id,`
    + ` business_key, state, accepted_at, routed_at) select '${tenantId}'::uuid, '${flowId}'::uuid,`
    + ` gen_random_uuid(), gen_random_uuid(), '${index}', '${state}', '${at}'::timestamptz,`
    + ` ${state === 'ACCEPTED' ? 'null' : `'${at}'::timestamptz`}`);
}

function seedDelivery(tenantId, flowId, state, index, at) {
  psql(`insert into public.delivery (tenant_id, state, created_at, updated_at, interface_id, business_key,`
    + ` target_id) select '${tenantId}'::uuid, '${state}', '${at}'::timestamptz, '${at}'::timestamptz,`
    + ` '${flowId}'::uuid, '${index}', gen_random_uuid()`);
}

async function openQueue(page) {
  await page.goto(consoleUrl());
  await page.click('[data-nav="queue"]');
  await expect(page.locator(`${PAGE}[data-queue-loaded="true"]`)).toBeVisible();
}

test('the dashboard derives live queued/processed/converted/failed counts per flow and day, and they move', async ({ page }) => {
  const flow = await freshFlow(page, 'counts');
  const tenantId = tenantIdOf();

  // the seed: one message per state group, one delivery per state group, all on today's UTC day.
  const at = `${DAY} 12:00:00+00`;
  seedMessage(tenantId, flow.id, 'ACCEPTED', `m-q-${RUN}`, at);
  seedMessage(tenantId, flow.id, 'ROUTED', `m-p-${RUN}`, at);
  seedMessage(tenantId, flow.id, 'ROUTE_FAILED', `m-f-${RUN}`, at);
  seedDelivery(tenantId, flow.id, 'DELIVERED', `d-c-${RUN}`, at);
  seedDelivery(tenantId, flow.id, 'DEAD_LETTERED', `d-f-${RUN}`, at);
  seedDelivery(tenantId, flow.id, 'RETRY_WAIT', `d-q-${RUN}`, at);

  await openQueue(page);

  const row = page.locator(`[data-queue-row="${flow.id}"][data-day="${DAY}"]`);
  await expect(row).toBeVisible();
  await expect(row).toHaveAttribute('data-count-queued', '2');
  await expect(row).toHaveAttribute('data-count-processed', '1');
  await expect(row).toHaveAttribute('data-count-converted', '1');
  await expect(row).toHaveAttribute('data-count-failed', '2');

  // LIVE: the counts move when the state tables move - two more messages queue the work.
  seedMessage(tenantId, flow.id, 'ACCEPTED', `m-q2-${RUN}-1`, at);
  seedMessage(tenantId, flow.id, 'ACCEPTED', `m-q2-${RUN}-2`, at);
  await page.reload();
  const after = page.locator(`[data-queue-row="${flow.id}"][data-day="${DAY}"]`);
  await expect(after).toHaveAttribute('data-count-queued', '4');
  await expect(after).toHaveAttribute('data-count-processed', '1');
});

// Isolation (row 310): test 1 seeds message/delivery rows into tenant A on the same booted backend,
// so "no work at all" is read as the harness's empty-tenant binding - a tenant admin of tenant C,
// which never holds a state row. The flow is onboarded AND the dashboard read as that tenant.
test('a flow with no message or delivery rows shows no row - the dashboard never invents figures', async ({ page }) => {
  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'empty-tenant' });
  const flow = await freshFlow(page, 'no-work');
  await openQueue(page);
  await expect(page.locator('[data-empty="queue-no-work"]')).toBeVisible();
  await expect(page.locator(`[data-queue-row="${flow.id}"]`)).toHaveCount(0);
});

// @negative @tenant @role: the figures ride READ_MESSAGE_TIMELINE (tenant admin, support, read-only)
// and are tenant-scoped - the harness's other-tenant binding is a CONFIGURATOR of tenant B, whose
// console is refused outright and never lists tenant A's work (or anyone's).
test("the other tenant's console is refused by the role gate and lists none of tenant A's work", async ({ page }) => {
  const flow = await freshFlow(page, 'tenant-cut');

  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant' });
  // Row 575 S1: the menu hides Queue from a configurator; opened by address, the shell shows the no-access state.
  // The refusal here is by the role gate (UX); the server 403 is covered by ForgedTenantDenialSuite.
  await page.goto(`${consoleUrl()}/#/queue`);
  await expect(page.locator('[role="alert"][data-no-access="true"]')).toBeVisible();
  await expect(page.locator(`[data-queue-row="${flow.id}"]`)).toHaveCount(0);
});