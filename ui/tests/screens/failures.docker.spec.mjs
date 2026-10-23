// T112 (E13 base N3, lane D - vijay-developer, row 327): the failures screen (#/failures), FAILING FIRST.
// WRITTEN, NOT RUN in the lane: the screens suite needs the docker suite lock (row-278 queue); the lead
// runs it at the seam.
//
// A console user opens Failures (menu, beside Queue) and sees the bound tenant's failed and dead-lettered
// messages per flow and UTC day, newest first: tracking id (a link to the detail screen), failing stage,
// error class, last error text, origin (PLATFORM = our side, TARGET = the target's answer), failed at.
// Read LIVE through GET /api/v1/admin/messages/failures (T110). T112: a DELIVER failure carries a Replay
// button in the Action column (flow tested in operator-actions.docker.spec.mjs).
//
// Seeding (queue-dashboard.docker.spec.mjs's pattern): each scenario onboards its OWN fresh flow through the
// real API from the signed-in page, then inserts message/delivery state rows straight into the suite's own
// pinned postgres (`ipass-ui-t41-pg-*`, as owner - fixtures, never production paths; the app reads only).

import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import process from 'node:process';
import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

const PAGE = '[data-page="failures"]';
const DAY = new Date().toISOString().slice(0, 10);
const YESTERDAY = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10);

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
  const names = execFileSync('docker', ['ps', '--format', '{{.Names}}'], { encoding: 'utf8' })
    .split('\n').map((name) => name.trim()).filter((name) => name.startsWith('ipass-ui-t41-pg-'));
  if (names.length !== 1) throw new Error(`exactly one booted suite postgres expected, saw: ${names.join(', ')}`);
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
  // the 201 carries only {id} (FlowAdminController.create); the code comes from the read
  const read = await inBrowser(page, 'GET', `/api/v1/admin/flows/${created.data.id}`);
  expect(read?.data?.code, `scenario ${scenario} reads its flow back: ${JSON.stringify(read?.data ?? read)}`).toBeTruthy();
  return { id: created.data.id, code: read.data.code, scenario };
}

/** A message row with a known id (the tracking id); state ROUTED or ROUTE_FAILED. */
function seedMessage(tenantId, flowId, state, at) {
  const id = randomUUID();
  psql(`insert into public.message (id, tenant_id, interface_id, interface_version_id, source_contract_version_id,`
    + ` business_key, state, accepted_at, updated_at) values ('${id}'::uuid, '${tenantId}'::uuid, '${flowId}'::uuid,`
    + ` gen_random_uuid(), gen_random_uuid(), 'order-${id}', '${state}', '${at}'::timestamptz, '${at}'::timestamptz)`);
  return id;
}

/** A DEAD_LETTERED delivery of the message, with the target's last answer and/or the platform's named reason. */
function seedDeadLetter(tenantId, flowId, messageId, at, { deadLetterClass, attemptDetail, eventDetail }) {
  const id = randomUUID();
  psql(`insert into public.delivery (id, tenant_id, state, created_at, updated_at, dead_letter_class, interface_id,`
    + ` target_id, message_id) values ('${id}'::uuid, '${tenantId}'::uuid, 'DEAD_LETTERED', '${at}'::timestamptz,`
    + ` '${at}'::timestamptz, '${deadLetterClass}', '${flowId}'::uuid, gen_random_uuid(), '${messageId}'::uuid)`);
  if (attemptDetail) {
    psql(`insert into public.delivery_attempt (tenant_id, delivery_id, layer, outcome_class, started_at, finished_at,`
      + ` detail) values ('${tenantId}'::uuid, '${id}'::uuid, 1, '${deadLetterClass}', '${at}'::timestamptz,`
      + ` '${at}'::timestamptz, '${attemptDetail}')`);
  }
  psql(`insert into public.delivery_event (tenant_id, delivery_id, type, detail, occurred_at) values`
    + ` ('${tenantId}'::uuid, '${id}'::uuid, 'DEAD_LETTER', '${eventDetail ?? deadLetterClass}', '${at}'::timestamptz)`);
  return id;
}

async function openFailures(page) {
  await page.goto(consoleUrl());
  await page.click('[data-nav="failures"]');
  await expect(page.locator(`${PAGE}[data-failures-loaded="true"]`)).toBeVisible();
}

async function pickFlow(page, flowId) {
  await page.getByRole('combobox', { name: 'Flow', exact: true }).selectOption(flowId);
  await expect(page.locator(`${PAGE}[data-failures-loaded="true"]`)).toBeVisible();
}

test('failed and dead-lettered messages show per flow and day with stage, class, last error, origin and a detail link', async ({ page }) => {
  test.setTimeout(60_000); // T194: the first test of a cold run seeds + boots the screen and exceeded the 15 s default (9.8 s warm)
  const flow = await freshFlow(page, 'rows');
  const tenantId = tenantIdOf();
  const routeFailed = seedMessage(tenantId, flow.id, 'ROUTE_FAILED', `${DAY} 00:01:00+00`);
  const targetMsg = seedMessage(tenantId, flow.id, 'ROUTED', `${DAY} 00:02:00+00`);
  seedDeadLetter(tenantId, flow.id, targetMsg, `${DAY} 00:03:00+00`,
    { deadLetterClass: 'PERMANENT', attemptDetail: 'HTTP 422 <b>rejected</b>' });
  const platformMsg = seedMessage(tenantId, flow.id, 'ROUTED', `${DAY} 00:04:00+00`);
  seedDeadLetter(tenantId, flow.id, platformMsg, `${DAY} 00:05:00+00`,
    { deadLetterClass: 'PERMANENT', eventDetail: 'PERMANENT: target credential unreadable' });

  await openFailures(page);
  await pickFlow(page, flow.id);

  const rows = page.locator('[data-failure-row]');
  await expect(rows).toHaveCount(3);
  // newest first, as the API answers
  await expect(rows.nth(0)).toHaveAttribute('data-failure-row', platformMsg);
  await expect(rows.nth(2)).toHaveAttribute('data-failure-row', routeFailed);

  const target = page.locator(`[data-failure-row="${targetMsg}"]`);
  await expect(target).toHaveAttribute('data-failing-stage', 'DELIVER');
  await expect(target).toHaveAttribute('data-origin', 'TARGET');
  await expect(target.locator('[data-error-class]')).toHaveText('PERMANENT');
  // plain text: the markup in the target's answer is shown, never rendered
  await expect(target.locator('[data-last-error]')).toHaveText('HTTP 422 <b>rejected</b>');
  await expect(target.locator('b')).toHaveCount(0);
  await expect(target.getByText('Target answer')).toBeVisible();

  const platform = page.locator(`[data-failure-row="${platformMsg}"]`);
  await expect(platform).toHaveAttribute('data-origin', 'PLATFORM');
  await expect(platform.locator('[data-last-error]')).toHaveText('PERMANENT: target credential unreadable');

  const route = page.locator(`[data-failure-row="${routeFailed}"]`);
  await expect(route).toHaveAttribute('data-failing-stage', 'ROUTE');
  await expect(route.locator('[data-last-error]')).toHaveAttribute('data-last-error', 'none');

  await expect(page.locator(`[data-message-link="${targetMsg}"]`)).toHaveAttribute('href', `#/messages/${targetMsg}?failed=DELIVER`);

  // T112: a DELIVER failure with a delivery offers Replay; a route failure has no delivery, so no button at all
  await expect(target.locator('[data-action-cell] button[data-action="replay"]')).toHaveText('Replay');
  await expect(route.getByRole('button', { name: /retry|replay/i })).toHaveCount(0);
});

test('the date filter narrows to one UTC day; clearing it shows all days', async ({ page }) => {
  const flow = await freshFlow(page, 'date');
  const tenantId = tenantIdOf();
  const today = seedMessage(tenantId, flow.id, 'ROUTE_FAILED', `${DAY} 00:01:00+00`);
  const yesterday = seedMessage(tenantId, flow.id, 'ROUTE_FAILED', `${YESTERDAY} 12:00:00+00`);

  await openFailures(page);
  await expect(page.getByLabel(/Day \(UTC/)).toHaveValue(DAY);
  await pickFlow(page, flow.id);
  await expect(page.locator(`[data-failure-row="${today}"]`)).toBeVisible();
  await expect(page.locator(`[data-failure-row="${yesterday}"]`)).toHaveCount(0);

  await page.getByLabel(/Day \(UTC/).fill(YESTERDAY);
  await expect(page.locator(`[data-failure-row="${yesterday}"]`)).toBeVisible();
  await expect(page.locator(`[data-failure-row="${today}"]`)).toHaveCount(0);

  await page.getByLabel(/Day \(UTC/).fill('');
  await expect(page.locator('[data-failure-row]')).toHaveCount(2);
});

test('keyboard: the filters are labelled, the tracking id link is reachable by Tab and opens the detail screen', async ({ page }) => {
  const flow = await freshFlow(page, 'keyboard');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTE_FAILED', `${DAY} 00:01:00+00`);
  await openFailures(page);
  await pickFlow(page, flow.id);

  await expect(page.locator('table[data-failures] caption')).toContainText(flow.code);
  // T112 added the Action column: 7 with one flow selected
  await expect(page.locator('table[data-failures] th[scope="col"]')).toHaveCount(7);

  await page.getByLabel(/Day \(UTC/).focus();
  const link = page.locator(`[data-message-link="${msg}"]`);
  for (let i = 0; i < 10 && !(await link.evaluate((el) => el === document.activeElement)); i += 1) await page.keyboard.press('Tab');
  await expect(link).toBeFocused();
  // visible focus: base.css a:focus-visible ring
  expect(await link.evaluate((el) => getComputedStyle(el).outlineStyle)).toBe('solid');
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`#/messages/${msg}\\?failed=ROUTE$`));
  await expect(page.locator('[data-page="message-detail"][data-message-loaded="true"]')).toBeVisible();
});

test('a tenant with no failures sees the empty state announced', async ({ page }) => {
  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'empty-tenant' });
  await openFailures(page);
  await page.getByLabel(/Day \(UTC/).fill('');
  await expect(page.locator('[role="status"][data-empty="failures-none"]')).toHaveText('No failed messages.');
  await expect(page.locator('[data-failure-row]')).toHaveCount(0);
});

// Row 328 D2: the All-flows view shows the Flow column - header and cells aligned, each cell the row's flow code.
test('All flows: the Flow column heads the table and each row names its own flow', async ({ page }) => {
  const first = await freshFlow(page, 'all-flows-1');
  const second = await freshFlow(page, 'all-flows-2');
  const tenantId = tenantIdOf();
  const one = seedMessage(tenantId, first.id, 'ROUTE_FAILED', `${DAY} 00:01:00+00`);
  const two = seedMessage(tenantId, second.id, 'ROUTE_FAILED', `${DAY} 00:02:00+00`);

  await openFailures(page);
  await expect(page.getByRole('combobox', { name: 'Flow', exact: true })).toHaveValue('');
  const headers = page.locator('table[data-failures] th[scope="col"]');
  await expect(headers).toHaveCount(8); // T112: + Action
  await expect(headers.nth(1)).toHaveText('Flow');
  await expect(page.locator(`[data-failure-row="${one}"] [data-flow-cell]`)).toHaveText(first.code);
  await expect(page.locator(`[data-failure-row="${two}"] [data-flow-cell]`)).toHaveText(second.code);
  // the Flow cell sits in the header's position (second cell of the row)
  await expect(page.locator(`[data-failure-row="${one}"] > td`).nth(1)).toHaveAttribute('data-flow-cell'); // presence: React renders the boolean as "true"

  // one flow selected: no Flow column, no Flow cell
  await pickFlow(page, first.id);
  await expect(headers).toHaveCount(7); // T112: + Action
  await expect(page.locator('[data-flow-cell]')).toHaveCount(0);
});

// @negative @tenant (row 328 M2): a tenant-B user WITH the read role gets past the role check and sees an
// empty list - tenant A's failures are cut by tenant scoping, never by a refusal.
test("@negative @tenant the other tenant's reader lists none of tenant A's failures", async ({ page }) => {
  const flow = await freshFlow(page, 'tenant-reader');
  const msg = seedMessage(tenantIdOf(), flow.id, 'ROUTE_FAILED', `${DAY} 00:01:00+00`);
  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant-reader' });
  await openFailures(page);
  await page.getByLabel(/Day \(UTC/).fill('');
  await expect(page.locator(`${PAGE}[data-load="ready"]`)).toBeVisible();
  await expect(page.locator('[data-refusal]')).toHaveCount(0);
  await expect(page.locator(`[data-failure-row="${msg}"]`)).toHaveCount(0);
  await expect(page.locator('[role="status"][data-empty="failures-none"]')).toHaveText('No failed messages.');
});

// @negative @role: the list rides READ_MESSAGE_TIMELINE; the other-tenant binding is a CONFIGURATOR
// of tenant B - refused outright (403, the role case), and never lists tenant A's failures.
test("@negative @role the other tenant's configurator is refused and lists none of tenant A's failures", async ({ page }) => {
  const flow = await freshFlow(page, 'tenant-cut');
  const msg = seedMessage(tenantIdOf(), flow.id, 'ROUTE_FAILED', `${DAY} 00:01:00+00`);
  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant' });
  await openFailures(page);
  await expect(page.locator('[role="alert"][data-refusal="failures-refused"]')).toBeVisible();
  await expect(page.locator(`[data-failure-row="${msg}"]`)).toHaveCount(0);
});
