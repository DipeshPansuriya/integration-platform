// F10-narrow (lane D, row278 release-base plan step 8, sanctioned Dipesh 2026-10-13) - the queue
// dashboard screens, FAILING FIRST.
//
// The narrow scope: live counts only. A console user opens #/queue and sees, per flow and day,
// how much work is queued (a message still ACCEPTED, or a delivery in a non-final state:
// PENDING/HELD/SENDING/RETRY_WAIT/DUE), how many messages the routing plane processed (ROUTED),
// how many delivered their contract-translated output (DELIVERED), and how much failed
// (ROUTE_FAILED messages + DEAD_LETTERED deliveries). Every figure is derived LIVE from
// public.message + public.delivery through GET /api/v1/admin/queue/summary - no hand-maintained
// counters anywhere. The counts change when the tables do.
//
// Seeding: the flow comes up through the REAL API from the signed-in page (session + CSRF), and
// the message/delivery state rows are inserted straight into the suite's own pinned postgres
// (`ipass-ui-t41-pg-*`, as owner - fixtures, never production paths; the app itself reads only).
// The counts must move when a second batch of rows lands.

import { execFileSync } from 'node:child_process';
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
  const names = execFileSync('docker', ['ps', '--format', '{{.Names}}'], { encoding: 'utf8' })
    .split('\n').map((name) => name.trim()).filter((name) => name.startsWith('ipass-ui-t41-pg-'));
  if (names.length !== 1) throw new Error(`exactly one booted suite postgres expected, saw: ${names.join(', ')}`);
  return execFileSync('docker', ['exec', names[0], 'psql', '-U', 'owner', '-d', 'platform', '-t', '-A', '-c', sql],
    { encoding: 'utf8' }).trim();
}

/** One interface_version/source_contract_version value is enough for the state rows (plain uuids, no FK). */
function seedMessage(tenantId, flowId, state, index, at) {
  psql(`insert into public.message (tenant_id, interface_id, interface_version_id, source_contract_version_id,`
    + ` business_key, state, accepted_at, routed_at) select '${tenantId}'::uuid, '${flowId}'::uuid,`
    + ` gen_random_uuid(), gen_random_uuid(), 'bk-${index}', '${state}', '${at}'::timestamptz,`
    + ` ${state === 'ACCEPTED' ? 'null' : `'${at}'::timestamptz`}`);
}

function seedDelivery(tenantId, flowId, state, index, at) {
  psql(`insert into public.delivery (tenant_id, state, created_at, updated_at, interface_id, business_key,`
    + ` target_id) select '${tenantId}'::uuid, '${state}', '${at}'::timestamptz, '${at}'::timestamptz,`
    + ` '${flowId}'::uuid, 'bk-${index}', gen_random_uuid()`);
}

/** The signed-in tenant's flow (created through the real onboarding API when the tenant has none). */
function flowOfTenant(page) {
  return page.evaluate(async () => {
    const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
    const headers = { 'content-type': 'application/json' };
    if (xsrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf.slice('XSRF-TOKEN='.length));
    const read = async (method, path, body) => {
      const answer = await fetch(path, { method, headers, credentials: 'include', body: body === undefined ? undefined : JSON.stringify(body) });
      return answer.json().catch(() => null);
    };
    let rows = (await read('GET', '/api/v1/admin/flows')) ?? [];
    if (!Array.isArray(rows) || rows.length === 0) {
      const created = await read('POST', '/api/v1/admin/flows', { sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' });
      if (!created || !created.id) return null;
      rows = (await read('GET', '/api/v1/admin/flows')) ?? [];
    }
    const row = Array.isArray(rows) ? rows[0] : null;
    return row && row.id ? { id: row.id, code: row.code ?? null } : null;
  });
}

async function openQueue(page) {
  await page.goto(consoleUrl());
  await page.click('[data-nav="queue"]');
  await expect(page.locator(`${PAGE}[data-queue-loaded="true"]`)).toBeVisible();
}

test('the dashboard derives live queued/processed/converted/failed counts per flow and day, and they move', async ({ page }) => {
  const flow = await flowOfTenant(page);
  expect(flow, 'tenant A has or onboards one flow through the real API').not.toBeNull();
  const tenantId = psql(`select id::text from public.tenant where code = '${process.env.CONSOLE_TENANT_CODE}'`);
  expect(tenantId, 'the harness tenant row exists').not.toBe('');

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

test('a flow with no message or delivery rows shows no row - the dashboard never invents figures', async ({ page }) => {
  const flow = await flowOfTenant(page);
  expect(flow).not.toBeNull();
  const tenantId = psql(`select id::text from public.tenant where code = '${process.env.CONSOLE_TENANT_CODE}'`);
  const work = psql(`select count(*) from public.message where tenant_id = '${tenantId}'::uuid`
    + ` and interface_id = '${flow.id}'::uuid and accepted_at::date = date '${DAY}'`);
  if (work !== '0') test.skip(true, 'another spec already put today-dated work on this flow');

  await openQueue(page);
  await expect(page.locator(`[data-queue-row="${flow.id}"]`).and(page.locator(`[data-day="${DAY}"]`))).toHaveCount(0);
});

// @negative @tenant: the counts are tenant-scoped - tenant B never sees tenant A's rows.
test("tenant B's console lists none of tenant A's work", async ({ page }) => {
  const flow = await flowOfTenant(page);
  expect(flow).not.toBeNull();

  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant' });
  await openQueue(page);
  await expect(page.locator(`[data-queue-row="${flow.id}"]`)).toHaveCount(0);
});