// T112 (E13 base N3, lane D - vijay-developer, row 327): the message detail screen
// (#/messages/<trackingId>), FAILING FIRST. WRITTEN, NOT RUN in the lane: the screens suite needs the
// docker suite lock (row-278 queue); the lead runs it at the seam.
//
// Every pipeline stage (INTAKE, ROUTE, TRANSFORM, DELIVER) with its UTC timestamps, its duration, the gap
// since the previous stage and the total, read LIVE through GET /api/v1/admin/messages/{id}/timeline (T110).
// The failing stage is marked; stages after it read "Not reached" - never a zero duration (backlog negative
// scenario). A tracking id the bound tenant cannot see reads "No message with this tracking id" - never a
// hint at another tenant.
//
// Seeding: queue-dashboard.docker.spec.mjs's pattern - a fresh flow through the real API, then message,
// stage-timing and delivery rows straight into the suite's pinned postgres (owner; fixtures only).

import { execFileSync } from 'node:child_process';
import { suitePgContainer } from './suite-pg.mjs';
import { randomUUID } from 'node:crypto';
import process from 'node:process';
import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

const PAGE = '[data-page="message-detail"]';
const DAY = new Date().toISOString().slice(0, 10);
const T0 = Date.parse(`${DAY}T00:01:00Z`);
const at = (ms) => new Date(T0 + ms).toISOString();

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

function psql(sql) {
  const names = [suitePgContainer()]; // L22: this run's postgres (IPASS_UI_PG_CONTAINER), else the single running one
  return execFileSync('docker', ['exec', names[0], 'psql', '-U', 'owner', '-d', 'platform', '-t', '-A', '-c', sql],
    { encoding: 'utf8' }).trim();
}

function tenantIdOf() {
  const id = psql(`select id::text from public.tenant where code = '${process.env.CONSOLE_TENANT_CODE}'`);
  expect(id, 'the harness tenant row exists').not.toBe('');
  return id;
}

async function freshFlow(page, scenario) {
  await page.goto(consoleUrl());
  await expect(page.locator('nav')).toBeVisible();
  const created = await inBrowser(page, 'POST', '/api/v1/admin/flows',
    { sourceSystem: 'S', destinations: ['T1'], businessKeyRule: 'customer_id' });
  expect(created?.data?.id, `scenario ${scenario} onboards a flow: ${JSON.stringify(created?.data ?? created)}`).toBeTruthy();
  return { id: created.data.id, scenario };
}

function seedMessage(tenantId, flowId, state) {
  const id = randomUUID();
  psql(`insert into public.message (id, tenant_id, interface_id, interface_version_id, source_contract_version_id,`
    + ` business_key, state, accepted_at, updated_at) values ('${id}'::uuid, '${tenantId}'::uuid, '${flowId}'::uuid,`
    + ` gen_random_uuid(), gen_random_uuid(), 'order-${id}', '${state}', '${at(0)}'::timestamptz,`
    + ` '${at(5000)}'::timestamptz)`);
  return id;
}

/** One stage timing row; finished null = the stage never finished. */
function seedStage(tenantId, messageId, stage, startMs, finishMs) {
  psql(`insert into public.message_stage_timing (message_id, tenant_id, accepted_at, stage, started_at, finished_at)`
    + ` values ('${messageId}'::uuid, '${tenantId}'::uuid, '${at(0)}'::timestamptz, '${stage}',`
    + ` '${at(startMs)}'::timestamptz, ${finishMs === null ? 'null' : `'${at(finishMs)}'::timestamptz`})`);
}

function seedDeadLetter(tenantId, flowId, messageId) {
  const id = randomUUID();
  psql(`insert into public.delivery (id, tenant_id, state, created_at, updated_at, dead_letter_class, interface_id,`
    + ` target_id, message_id) values ('${id}'::uuid, '${tenantId}'::uuid, 'DEAD_LETTERED', '${at(1400)}'::timestamptz,`
    + ` '${at(5000)}'::timestamptz, 'PERMANENT', '${flowId}'::uuid, gen_random_uuid(), '${messageId}'::uuid)`);
  psql(`insert into public.delivery_event (tenant_id, delivery_id, type, detail, occurred_at) values`
    + ` ('${tenantId}'::uuid, '${id}'::uuid, 'DEAD_LETTER', 'PERMANENT', '${at(5000)}'::timestamptz)`);
  return id;
}

/** One target call of a delivery: sent at startMs, outcome decided at finishMs (row 329: target response). */
function seedAttempt(tenantId, deliveryId, layer, outcome, startMs, finishMs) {
  psql(`insert into public.delivery_attempt (tenant_id, delivery_id, layer, outcome_class, started_at, finished_at)`
    + ` values ('${tenantId}'::uuid, '${deliveryId}'::uuid, ${layer}, '${outcome}', '${at(startMs)}'::timestamptz,`
    + ` '${at(finishMs)}'::timestamptz)`);
}

async function openDetail(page, hash) {
  await page.goto(`${consoleUrl()}${hash}`);
  await expect(page.locator(`${PAGE}[data-load]:not([data-load="loading"])`)).toBeVisible();
}

test('a dead-lettered message: every stage with times, duration and gap, the total, DELIVER marked failed (via Failures)', async ({ page }) => {
  const flow = await freshFlow(page, 'dead-letter');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTED');
  seedStage(tenantId, msg, 'INTAKE', 0, 40);
  seedStage(tenantId, msg, 'ROUTE', 50, 80);
  seedStage(tenantId, msg, 'TRANSFORM', 100, 1334);
  seedStage(tenantId, msg, 'DELIVER', 1400, null);
  const delivery = seedDeadLetter(tenantId, flow.id, msg);
  // two calls: a transient one, then the deciding one - target response is the LAST call only (row 329)
  seedAttempt(tenantId, delivery, 1, 'TRANSIENT', 1400, 1500);
  seedAttempt(tenantId, delivery, 2, 'PERMANENT', 3000, 3412);

  // reached from Failures (the detail screen has no menu entry)
  await page.goto(consoleUrl());
  await page.click('[data-nav="failures"]');
  await page.getByRole('combobox', { name: 'Flow', exact: true }).selectOption(flow.id);
  await page.click(`[data-message-link="${msg}"]`);
  await expect(page).toHaveURL(new RegExp(`#/messages/${msg}\\?failed=DELIVER$`));
  await expect(page.locator(`${PAGE}[data-message-loaded="true"]`)).toBeVisible();

  await expect(page.locator('[data-tracking-id]')).toHaveText(msg);
  await expect(page.locator('[data-total-ms]')).toHaveAttribute('data-total-ms', '1400');
  await expect(page.locator('[data-failing-stage]')).toHaveAttribute('data-failing-stage', 'DELIVER');

  const rows = page.locator('[data-stage-row]');
  await expect(rows).toHaveCount(4);
  await expect(page.locator('[data-stage-row="INTAKE"]')).toHaveAttribute('data-duration-ms', '40');
  await expect(page.locator('[data-stage-row="ROUTE"] [data-since-cell]')).toHaveText('10 ms');
  await expect(page.locator('[data-stage-row="TRANSFORM"] [data-duration-cell]')).toHaveText('1.234 s');
  const deliver = page.locator('[data-stage-row="DELIVER"]');
  await expect(deliver).toHaveAttribute('data-stage-status', 'failed');
  await expect(deliver.locator('[data-duration-cell]')).toHaveText('Did not finish');
  await expect(deliver.locator('[data-since-cell]')).toHaveText('66 ms');

  // E13-C2 spec words on the stage rows, and the target's own time (not our retry waits) in the summary
  await expect(page.locator('[data-stage-row="INTAKE"] th')).toHaveText('Intake (source intake)');
  await expect(page.locator('[data-stage-row="ROUTE"] th')).toHaveText('Route');
  await expect(page.locator('[data-stage-row="TRANSFORM"] th')).toHaveText('Transform (conversion)');
  await expect(deliver.locator('th')).toHaveText('Deliver (all attempts)');
  const response = page.locator(`[data-target-response="${delivery}"]`);
  await expect(response.locator('dt')).toHaveText('Target response');
  await expect(response.locator('dd')).toHaveText('412 ms (Permanent)');

  // table semantics (WCAG AA basics): caption, column and row headers
  await expect(page.locator('table[data-timeline] caption')).toContainText(msg);
  await expect(page.locator('table[data-timeline] th[scope="col"]')).toHaveCount(6);
  await expect(page.locator('table[data-timeline] th[scope="row"]')).toHaveCount(4);
});

// @negative (backlog): a stage after the failure is "Not reached" - never a zero duration.
test('@negative a route-failed message shows TRANSFORM and DELIVER as Not reached, never a zero duration', async ({ page }) => {
  const flow = await freshFlow(page, 'route-failed');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTE_FAILED');
  seedStage(tenantId, msg, 'INTAKE', 0, 40);
  seedStage(tenantId, msg, 'ROUTE', 50, 90);

  await openDetail(page, `#/messages/${msg}`);
  await expect(page.locator('[data-stage-row="ROUTE"]')).toHaveAttribute('data-stage-status', 'failed');
  for (const stage of ['TRANSFORM', 'DELIVER']) {
    const row = page.locator(`[data-stage-row="${stage}"]`);
    await expect(row).toHaveAttribute('data-stage-status', 'not-reached');
    await expect(row).toHaveAttribute('data-duration-ms', '');
    await expect(row.locator('[data-duration-cell]')).toHaveText('Not reached');
    await expect(row.locator('[data-duration-cell]')).not.toHaveText(/\b0\s*(ms|s)\b/);
  }
});

test('@negative an unknown or malformed tracking id reads "No message with this tracking id"', async ({ page }) => {
  await page.goto(consoleUrl());
  await openDetail(page, `#/messages/${randomUUID()}`);
  await expect(page.locator('[role="status"][data-empty="message-not-found"]')).toHaveText('No message with this tracking id');
  await expect(page.locator('[data-stage-row]')).toHaveCount(0);

  await openDetail(page, '#/messages/not-a-uuid');
  await expect(page.locator('[role="status"][data-empty="message-not-found"]')).toHaveText('No message with this tracking id');
});

// Row 328 M1: the failing stage is the server's - no ?failed= hint is needed, and a forged one marks nothing.
test('@negative the failing stage comes from the server: no hint still shows DELIVER failed, a forged hint shows nothing failed', async ({ page }) => {
  const flow = await freshFlow(page, 'server-stage');
  const tenantId = tenantIdOf();
  const dead = seedMessage(tenantId, flow.id, 'ROUTED');
  seedStage(tenantId, dead, 'INTAKE', 0, 40);
  seedDeadLetter(tenantId, flow.id, dead);
  await openDetail(page, `#/messages/${dead}`);
  await expect(page.locator('[data-failing-stage]')).toHaveAttribute('data-failing-stage', 'DELIVER');
  await expect(page.locator('[data-stage-row="DELIVER"]')).toHaveAttribute('data-stage-status', 'failed');

  const inFlight = seedMessage(tenantId, flow.id, 'ROUTED');
  seedStage(tenantId, inFlight, 'INTAKE', 0, 40);
  await openDetail(page, `#/messages/${inFlight}?failed=DELIVER`);
  await expect(page.locator(`${PAGE}[data-message-loaded="true"]`)).toBeVisible();
  await expect(page.locator('[data-failing-stage]')).toHaveCount(0);
  await expect(page.locator('[data-stage-status="failed"]')).toHaveCount(0);
  // no delivery yet: the target was not reached - never a zero duration
  await expect(page.locator('[data-target-response] dd')).toHaveText('Not reached');
});

// @negative @tenant (row 328 M2, backlog: tenant B gets 404): a tenant-B user WITH the read role passes the
// role check, so only tenant scoping stands between it and tenant A's message - the answer is not found.
test("@negative @tenant another tenant's reader gets not found for tenant A's message", async ({ page }) => {
  const flow = await freshFlow(page, 'tenant-cut');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTE_FAILED');
  seedStage(tenantId, msg, 'INTAKE', 0, 40);

  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant-reader' });
  await openDetail(page, `#/messages/${msg}`);
  await expect(page.locator(`${PAGE}[data-load="not-found"]`)).toBeVisible();
  await expect(page.locator('[role="status"][data-empty="message-not-found"]')).toHaveText('No message with this tracking id');
  await expect(page.locator('[data-stage-row]')).toHaveCount(0);
  await expect(page.locator(PAGE)).not.toContainText(/tenant/i);
});

// @negative @role: the other-tenant binding is a CONFIGURATOR of tenant B, without the read role - refused
// (403) before tenant scoping runs; no stage rows either way.
test("@negative @role another tenant's configurator is refused tenant A's message", async ({ page }) => {
  const flow = await freshFlow(page, 'role-cut');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTE_FAILED');
  seedStage(tenantId, msg, 'INTAKE', 0, 40);

  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant' });
  await openDetail(page, `#/messages/${msg}`);
  await expect(page.locator(`${PAGE}[data-load="refused"]`)).toBeVisible();
  await expect(page.locator('[data-stage-row]')).toHaveCount(0);
});
