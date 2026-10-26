// Row 323 / T165 (aditya-qa-automation): T112 retry/replay on the failures and message-detail screens, end to end
// against the real backend (T111 endpoints, JDBC store). NOT RUN when written: the screens suite boots docker
// under the PC-wide suite lock; run it with
//   cd ui && npm run test:screens -- tests/screens/operator-actions.docker.spec.mjs
//
// Acceptance covered (Given/When/Then):
//  - Replay on a DEAD_LETTERED, pinned delivery -> 200, the queued text in the row's status region, the row
//    leaves DEAD_LETTERED and exactly one REPLAY event is appended.
//  - A route failure (no delivery) has no button; SUCCESS / PERMANENT attempts on detail have no Retry now.
//  - Not pinned -> 409 "not pinned" text, state unchanged.
//  - Double submit: two concurrent replays of one delivery -> exactly one 200 and one 409, one REPLAY event.
//  - Retry now on detail for a RETRY_WAIT delivery whose last call was TRANSIENT -> queued text, one REDRIVE event.
//  - Replay on a RETRY_WAIT delivery / Retry on a DEAD_LETTERED one -> 409 wrong state, nothing moves.
//  - @negative @role: a READ_ONLY user sees the button on its own tenant's failure, gets the 403 text, nothing moves.
//  - @negative @tenant: tenant B's users POSTing tenant A's delivery id never move it (403 before scoping for
//    the harness roles; a tenant-B admin's 404 is covered in DeliveryOperatorControllerTest - no harness login).
//  - a11y: the button is reachable by Tab, has a visible focus ring, and the answer lands in role=status.
//
// Seeding follows failures.docker.spec.mjs: a fresh flow per scenario through the real API, state rows by psql
// as owner into the suite's own pinned postgres (fixtures only; never production).

import { execFileSync } from 'node:child_process';
import { suitePgContainer } from './suite-pg.mjs';
import { randomUUID } from 'node:crypto';
import process from 'node:process';
import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';

const FAILURES = '[data-page="failures"]';
const DETAIL = '[data-page="message-detail"]';
const NOW = () => new Date().toISOString();

const TEXT = {
  replayed: 'Replay queued: the delivery is due and will be sent again with its pinned versions.',
  retried: 'Retry queued: the delivery is due now and will be sent again with its pinned versions.',
  refused: 'You are not allowed to retry or replay deliveries.',
  notPinned: 'Not replayed: the delivery is not pinned to a mapping and contract version.',
};

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

/** Two POSTs fired in the same tick from the page (the double-click / two-tab case). */
async function twiceAtOnce(page, apiPath) {
  return page.evaluate(async (apiPath) => {
    const xsrf = document.cookie.split('; ').filter((pair) => pair.startsWith('XSRF-TOKEN=')).pop();
    const headers = xsrf ? { 'X-XSRF-TOKEN': decodeURIComponent(xsrf.slice('XSRF-TOKEN='.length)) } : {};
    const one = () => fetch(apiPath, { method: 'POST', headers, credentials: 'include' }).then((a) => a.status);
    return Promise.all([one(), one()]);
  }, apiPath);
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
  // the 201 carries only {id} (FlowAdminController.create); the code comes from the read
  const read = await inBrowser(page, 'GET', `/api/v1/admin/flows/${created.data.id}`);
  expect(read?.data?.code, `scenario ${scenario} reads its flow back: ${JSON.stringify(read?.data ?? read)}`).toBeTruthy();
  return { id: created.data.id, code: read.data.code };
}

function seedMessage(tenantId, flowId, state) {
  const id = randomUUID();
  psql(`insert into public.message (id, tenant_id, interface_id, interface_version_id, source_contract_version_id,`
    + ` business_key, state, accepted_at, updated_at) values ('${id}'::uuid, '${tenantId}'::uuid, '${flowId}'::uuid,`
    + ` gen_random_uuid(), gen_random_uuid(), 'order-${id}', '${state}', '${NOW()}'::timestamptz, '${NOW()}'::timestamptz)`);
  return id;
}

/** A delivery in `state`; pinned = both ADR-013 pins set (replay/retry refuse an unpinned delivery). */
function seedDelivery(tenantId, flowId, messageId, state, { pinned = true, outcome = 'PERMANENT' } = {}) {
  const id = randomUUID();
  const pins = pinned ? 'gen_random_uuid(), gen_random_uuid()' : 'null, null';
  const deadClass = state === 'DEAD_LETTERED' ? `'${outcome}'` : 'null';
  const next = state === 'RETRY_WAIT' ? `'${new Date(Date.now() + 3_600_000).toISOString()}'::timestamptz` : 'null';
  psql(`insert into public.delivery (id, tenant_id, state, created_at, updated_at, dead_letter_class, next_attempt_at,`
    + ` interface_id, target_id, message_id, mapping_version_id, contract_version_id) values ('${id}'::uuid,`
    + ` '${tenantId}'::uuid, '${state}', '${NOW()}'::timestamptz, '${NOW()}'::timestamptz, ${deadClass}, ${next},`
    + ` '${flowId}'::uuid, gen_random_uuid(), '${messageId}'::uuid, ${pins})`);
  psql(`insert into public.delivery_attempt (tenant_id, delivery_id, layer, outcome_class, started_at, finished_at)`
    + ` values ('${tenantId}'::uuid, '${id}'::uuid, 1, '${outcome}', '${NOW()}'::timestamptz, '${NOW()}'::timestamptz)`);
  if (state === 'DEAD_LETTERED') {
    psql(`insert into public.delivery_event (tenant_id, delivery_id, type, detail, occurred_at) values`
      + ` ('${tenantId}'::uuid, '${id}'::uuid, 'DEAD_LETTER', '${outcome}', '${NOW()}'::timestamptz)`);
  }
  return id;
}

const stateOf = (deliveryId) => psql(`select state from public.delivery where id = '${deliveryId}'::uuid`);
const eventsOf = (deliveryId, type) =>
  Number(psql(`select count(*) from public.delivery_event where delivery_id = '${deliveryId}'::uuid and type = '${type}'`));

async function openFailures(page, flowId) {
  await page.goto(consoleUrl());
  await page.click('[data-nav="failures"]');
  await expect(page.locator(`${FAILURES}[data-failures-loaded="true"]`)).toBeVisible();
  await page.getByRole('combobox', { name: 'Flow', exact: true }).selectOption(flowId);
  await expect(page.locator(`${FAILURES}[data-failures-loaded="true"]`)).toBeVisible();
}

async function openDetail(page, messageId) {
  await page.goto(`${consoleUrl()}#/messages/${messageId}`);
  await expect(page.locator(`${DETAIL}[data-message-loaded="true"]`)).toBeVisible();
}

const replayButton = (page, messageId) =>
  page.locator(`[data-failure-row="${messageId}"] [data-action-cell] button[data-action="replay"]`);
// B28 B2: Replay on the failures screen asks first - the first press turns the button into "Confirm replay", the next sends.
const armAndPress = async (button) => { await button.click(); await expect(button).toHaveText('Confirm replay'); await button.click(); };
const answerOf = (page, deliveryId) => page.locator(`[role="status"][data-action-answer="${deliveryId}"]`);

test('failures: Replay moves a pinned dead-lettered delivery to due, says so, and appends one REPLAY event', async ({ page }) => {
  const flow = await freshFlow(page, 'replay');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTED');
  const delivery = seedDelivery(tenantId, flow.id, msg, 'DEAD_LETTERED');
  const routeOnly = seedMessage(tenantId, flow.id, 'ROUTE_FAILED');

  await openFailures(page, flow.id);
  await expect(page.locator(`${FAILURES} table[data-failures] th[scope="col"]`).last()).toHaveText('Action');
  await expect(page.locator(`[data-failure-row="${routeOnly}"]`).getByRole('button', { name: /retry|replay/i })).toHaveCount(0); // the Copy button is not an action

  const button = replayButton(page, msg);
  await expect(button).toHaveAttribute('data-delivery-id', delivery);
  const answered = page.waitForResponse((r) => r.url().endsWith(`/deliveries/${delivery}/replay`) && r.request().method() === 'POST');
  await armAndPress(button);
  expect((await answered).status()).toBe(200);
  await expect(answerOf(page, delivery)).toHaveText(TEXT.replayed);

  // the worker may already have picked it up; what must hold is that it left DEAD_LETTERED exactly once
  expect(eventsOf(delivery, 'REPLAY')).toBe(1);
  expect(['DUE', 'SENDING', 'DELIVERED', 'RETRY_WAIT', 'DEAD_LETTERED']).toContain(stateOf(delivery));
});

test('failures: an unpinned dead-lettered delivery is refused with the not-pinned text and does not move', async ({ page }) => {
  const flow = await freshFlow(page, 'not-pinned');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTED');
  const delivery = seedDelivery(tenantId, flow.id, msg, 'DEAD_LETTERED', { pinned: false });

  await openFailures(page, flow.id);
  await armAndPress(replayButton(page, msg));
  await expect(answerOf(page, delivery)).toHaveText(TEXT.notPinned);
  expect(stateOf(delivery)).toBe('DEAD_LETTERED');
  expect(eventsOf(delivery, 'REPLAY')).toBe(0);
});

test('double submit: two replays of one delivery at once -> exactly one 200 and one 409, one REPLAY event', async ({ page }) => {
  const flow = await freshFlow(page, 'double');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTED');
  const delivery = seedDelivery(tenantId, flow.id, msg, 'DEAD_LETTERED');

  const statuses = await twiceAtOnce(page, `/api/v1/admin/messages/deliveries/${delivery}/replay`);
  expect(statuses.sort()).toEqual([200, 409]);
  expect(eventsOf(delivery, 'REPLAY')).toBe(1);
});

test('failures: the button is disabled while its POST is in flight and stays disabled after the replay, ignoring a second press', async ({ page }) => {
  const flow = await freshFlow(page, 'in-flight');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTED');
  const delivery = seedDelivery(tenantId, flow.id, msg, 'DEAD_LETTERED');

  await openFailures(page, flow.id);
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  let posts = 0;
  await page.route(`**/deliveries/${delivery}/replay`, async (route) => { posts += 1; await gate; await route.continue(); });
  const button = replayButton(page, msg);
  await armAndPress(button);
  await expect(button).toBeDisabled();
  release();
  await expect(answerOf(page, delivery)).toHaveText(TEXT.replayed);
  // T165 M4 (OperatorActionButton.tsx:3-4): once the delivery moved the button stays aria-disabled and ignores presses
  await expect(button).toHaveAttribute('aria-disabled', 'true');
  // a POST could leave after the click resolves, so the proof is that none arrives within 1.5 s (waitForRequest times out)
  const replayed = page.waitForRequest((r) => r.method() === 'POST' && r.url().includes(`/deliveries/${delivery}/replay`), { timeout: 1500 })
    .then(() => true, (e) => { if (e.name === 'TimeoutError') return false; throw e; });
  await button.click({ force: true }); // Playwright waits on aria-disabled; force sends the press the button must ignore
  expect(await replayed, 'no replay POST within 1.5 s of the ignored press').toBe(false);
  await expect(answerOf(page, delivery)).toHaveText(TEXT.replayed);
  expect(posts, 'a press on the moved delivery sends no second POST').toBe(1);
  await page.unroute(`**/deliveries/${delivery}/replay`);
  expect(eventsOf(delivery, 'REPLAY')).toBe(1);
});

test('detail: Retry now on a RETRY_WAIT delivery whose last call was TRANSIENT queues it and appends one REDRIVE', async ({ page }) => {
  const flow = await freshFlow(page, 'retry');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTED');
  const delivery = seedDelivery(tenantId, flow.id, msg, 'RETRY_WAIT', { outcome: 'TRANSIENT' });

  await openDetail(page, msg);
  const holder = page.locator(`[data-delivery-action="${delivery}"]`);
  await expect(holder).toContainText('Delivery to');
  const button = holder.locator('button[data-action="retry"]');
  await expect(button).toHaveText('Retry now');
  await button.click();
  await expect(answerOf(page, delivery)).toHaveText(TEXT.retried);
  expect(eventsOf(delivery, 'REDRIVE')).toBe(1);
});

test('detail: no Retry now for a delivery whose last call was SUCCESS or PERMANENT', async ({ page }) => {
  const flow = await freshFlow(page, 'no-retry');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTED');
  const ok = seedDelivery(tenantId, flow.id, msg, 'DELIVERED', { outcome: 'SUCCESS' });
  const msg2 = seedMessage(tenantId, flow.id, 'ROUTED');
  const permanent = seedDelivery(tenantId, flow.id, msg2, 'DEAD_LETTERED', { outcome: 'PERMANENT' });

  await openDetail(page, msg);
  await expect(page.locator(`[data-delivery-action="${ok}"] button[data-action="retry"]`)).toHaveCount(0);
  await openDetail(page, msg2);
  await expect(page.locator(`[data-delivery-action="${permanent}"] button[data-action="retry"]`)).toHaveCount(0);
});

test('wrong state through the API: replay of RETRY_WAIT and retry of DEAD_LETTERED are 409 and move nothing', async ({ page }) => {
  const flow = await freshFlow(page, 'wrong-state');
  const tenantId = tenantIdOf();
  const waiting = seedDelivery(tenantId, flow.id, seedMessage(tenantId, flow.id, 'ROUTED'), 'RETRY_WAIT', { outcome: 'TRANSIENT' });
  const dead = seedDelivery(tenantId, flow.id, seedMessage(tenantId, flow.id, 'ROUTED'), 'DEAD_LETTERED');

  const replay = await inBrowser(page, 'POST', `/api/v1/admin/messages/deliveries/${waiting}/replay`);
  expect(replay.status).toBe(409);
  expect(replay.data).toMatchObject({ error: 'wrong state', state: 'RETRY_WAIT' });
  const retry = await inBrowser(page, 'POST', `/api/v1/admin/messages/deliveries/${dead}/retry`);
  expect(retry.status).toBe(409);
  expect(retry.data).toMatchObject({ error: 'wrong state', state: 'DEAD_LETTERED' });
  expect(stateOf(waiting)).toBe('RETRY_WAIT');
  expect(stateOf(dead)).toBe('DEAD_LETTERED');
});

test('@negative @role a READ_ONLY user sees its own failure, is refused the replay with the 403 text, nothing moves', async ({ page }) => {
  const otherFlow = process.env.CONSOLE_OTHER_TENANT_FLOW;
  test.skip(!otherFlow, 'the harness exported no tenant-B flow');
  const tenantB = psql(`select tenant_id::text from public.interface where id = '${otherFlow}'::uuid`);
  const msg = seedMessage(tenantB, otherFlow, 'ROUTED');
  const delivery = seedDelivery(tenantB, otherFlow, msg, 'DEAD_LETTERED');

  await page.setExtraHTTPHeaders({ 'X-Harness-Login': 'other-tenant-reader' });
  await openFailures(page, otherFlow);
  await armAndPress(replayButton(page, msg));
  await expect(answerOf(page, delivery)).toHaveText(TEXT.refused);
  expect(stateOf(delivery)).toBe('DEAD_LETTERED');
  expect(eventsOf(delivery, 'REPLAY')).toBe(0);
});

test("@negative @tenant tenant B's users cannot move tenant A's delivery by id", async ({ page }) => {
  const flow = await freshFlow(page, 'tenant-cut');
  const tenantId = tenantIdOf();
  const delivery = seedDelivery(tenantId, flow.id, seedMessage(tenantId, flow.id, 'ROUTED'), 'DEAD_LETTERED');

  for (const login of ['other-tenant', 'other-tenant-reader']) {
    await page.setExtraHTTPHeaders({ 'X-Harness-Login': login });
    await page.goto(consoleUrl());
    const answer = await inBrowser(page, 'POST', `/api/v1/admin/messages/deliveries/${delivery}/replay`);
    // neither harness role holds REPLAY_DEAD_LETTER: refused before scoping; never 200, never a state leak
    expect([403, 404], `${login} answered ${answer.status}`).toContain(answer.status);
    expect(JSON.stringify(answer.data ?? {})).not.toContain('DEAD_LETTERED');
  }
  expect(stateOf(delivery)).toBe('DEAD_LETTERED');
  expect(eventsOf(delivery, 'REPLAY')).toBe(0);
});

test('a11y: Replay is reachable by Tab with a visible focus ring and Enter announces the answer in role=status', async ({ page }) => {
  const flow = await freshFlow(page, 'keyboard');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTED');
  const delivery = seedDelivery(tenantId, flow.id, msg, 'DEAD_LETTERED');

  await openFailures(page, flow.id);
  const button = replayButton(page, msg);
  await page.locator(`[data-message-link="${msg}"]`).focus();
  for (let i = 0; i < 10 && !(await button.evaluate((el) => el === document.activeElement)); i += 1) await page.keyboard.press('Tab');
  await expect(button).toBeFocused();
  expect(await button.evaluate((el) => getComputedStyle(el).outlineStyle)).not.toBe('none');
  await page.keyboard.press('Enter'); // asks
  await expect(button).toHaveText('Confirm replay');
  await page.keyboard.press('Enter'); // sends
  await expect(answerOf(page, delivery)).toHaveText(TEXT.replayed);
});
