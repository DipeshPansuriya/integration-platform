// Row 575 (S3, message tracking view) - the plain-words layer on the message detail and the Messages landing, in the real console
// against the real backend and Keycloak.
//   stage strip   : Received / Routed / Mapped / Delivered, each with a mark AND a word (never colour alone); a stage the data does
//                   not record reads "Not recorded", a stage after the failure "Not reached" - never "0 ms"
//   What to do next: Retry / Replay are named only for tenant admin + support; read-only gets "Only a tenant admin or support user
//                   can ..." (UX only: the server's 403/409 stays the gate - operator-actions.docker.spec.mjs)
//   detail chrome  : breadcrumb, Copy id button, the flow shown by name and linked only for a role that reads configuration
//   Messages page  : tracking-id search (valid UUID -> its address, invalid -> the always-mounted hint) and tabs that are links
// Users: global-setup.mjs seeds menu-su / menu-ro / menu-ap / menu-cf in the default tenant (CONSOLE_MENU_USERS, memory only).

import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { consoleUrl } from './boot-state.mjs';
import { browserLogin } from './keycloak-login.mjs';
import { freshFlow, psql, tenantIdOf } from './screen-fixtures.mjs';

const NO_SESSION = { storageState: { cookies: [], origins: [] } };
const PAGE = '[data-page="message-detail"]';
const DAY = new Date().toISOString().slice(0, 10);
const T0 = Date.parse(`${DAY}T00:01:00Z`);
const at = (ms) => new Date(T0 + ms).toISOString();

const REPLAY_WORDS = 'Then Replay.';
const RETRY_WORDS = 'Use Retry now if you know the cause is fixed.';
const NOT_OPERATOR_REPLAY = 'Only a tenant admin or support user can replay it.';
const NOT_OPERATOR_RETRY = 'Only a tenant admin or support user can retry it.';

function userFor(role) {
  return JSON.parse(process.env.CONSOLE_MENU_USERS)[role];
}

/** A fresh, signed-in context for a menu role (TENANT_ADMIN uses the suite's own signed-in `page`). */
async function signedInAs(browser, role) {
  const context = await browser.newContext(NO_SESSION);
  const page = await context.newPage();
  await browserLogin(page, consoleUrl(), userFor(role));
  await expect(page.locator('[data-session-tenant]')).toHaveText(process.env.CONSOLE_TENANT_CODE);
  return { context, page };
}

function seedMessage(tenantId, flowId, state) {
  const id = randomUUID();
  psql(`insert into public.message (id, tenant_id, interface_id, interface_version_id, source_contract_version_id,`
    + ` business_key, state, accepted_at, updated_at) values ('${id}'::uuid, '${tenantId}'::uuid, '${flowId}'::uuid,`
    + ` gen_random_uuid(), gen_random_uuid(), 'order-${id}', '${state}', '${at(0)}'::timestamptz, '${at(5000)}'::timestamptz)`);
  return id;
}

function seedStage(tenantId, messageId, stage, startMs, finishMs) {
  psql(`insert into public.message_stage_timing (message_id, tenant_id, accepted_at, stage, started_at, finished_at)`
    + ` values ('${messageId}'::uuid, '${tenantId}'::uuid, '${at(0)}'::timestamptz, '${stage}',`
    + ` '${at(startMs)}'::timestamptz, ${finishMs === null ? 'null' : `'${at(finishMs)}'::timestamptz`})`);
}

/** A delivery in `state` with its deciding call; DEAD_LETTERED carries the dead-letter class `outcome`. */
function seedDelivery(tenantId, flowId, messageId, state, outcome) {
  const id = randomUUID();
  const deadClass = state === 'DEAD_LETTERED' ? `'${outcome}'` : 'null';
  const next = state === 'RETRY_WAIT' ? `'${new Date(Date.now() + 3_600_000).toISOString()}'::timestamptz` : 'null';
  psql(`insert into public.delivery (id, tenant_id, state, created_at, updated_at, dead_letter_class, next_attempt_at, interface_id,`
    + ` target_id, message_id) values ('${id}'::uuid, '${tenantId}'::uuid, '${state}', '${at(1400)}'::timestamptz,`
    + ` '${at(5000)}'::timestamptz, ${deadClass}, ${next}, '${flowId}'::uuid, gen_random_uuid(), '${messageId}'::uuid)`);
  psql(`insert into public.delivery_attempt (tenant_id, delivery_id, layer, outcome_class, started_at, finished_at)`
    + ` values ('${tenantId}'::uuid, '${id}'::uuid, 1, '${outcome}', '${at(1400)}'::timestamptz, '${at(1500)}'::timestamptz)`);
  if (state === 'DEAD_LETTERED') {
    psql(`insert into public.delivery_event (tenant_id, delivery_id, type, detail, occurred_at) values`
      + ` ('${tenantId}'::uuid, '${id}'::uuid, 'DEAD_LETTER', '${outcome}', '${at(5000)}'::timestamptz)`);
  }
  return id;
}

/** A dead-lettered (PERMANENT) message: Received, Routed, Mapped done, Delivered failed. */
async function deadLetterMessage(page, scenario) {
  const flow = await freshFlow(page, scenario);
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTED');
  seedStage(tenantId, msg, 'INTAKE', 0, 40);
  seedStage(tenantId, msg, 'ROUTE', 50, 80);
  seedStage(tenantId, msg, 'TRANSFORM', 100, 1334);
  seedStage(tenantId, msg, 'DELIVER', 1400, null);
  seedDelivery(tenantId, flow.id, msg, 'DEAD_LETTERED', 'PERMANENT');
  return { flow, msg };
}

async function openDetail(page, messageId, hash = '') {
  await page.goto(`${consoleUrl()}/#/messages/${messageId}${hash}`);
  await expect(page.locator(`${PAGE}[data-message-loaded="true"]`)).toBeVisible();
}

const ACTION_BUTTONS = 'button[data-action="retry"], button[data-action="replay"]';

/** The Failures screen row for a message, once the flow is picked. */
async function openFailuresRow(page, msg) {
  await page.goto(`${consoleUrl()}/#/failures`);
  // All flows (the default): support and read-only cannot list flows, so the Flow filter has no options for them.
  await expect(page.locator(`[data-failure-row="${msg}"]`)).toBeVisible();
  return page.locator(`[data-failure-row="${msg}"]`);
}

const strip = (page) => page.locator('[data-stage-strip]');
const nextStep = (page) => page.locator('[data-next-step]');

// --- the stage strip --------------------------------------------------------------------------------------------------------

test('Scenario: a dead-lettered message - the strip reads Received, Routed, Mapped done and Delivered failed, each with a mark AND a word', async ({ page }) => {
  const { msg } = await deadLetterMessage(page, 'strip-dead');
  await openDetail(page, msg);
  await expect(strip(page)).toHaveAttribute('aria-label', 'Message progress');
  const items = strip(page).locator('[data-strip-stage]');
  await expect(items).toHaveCount(4);
  const expected = [
    ['INTAKE', 'Received', '✓', 'Done'],
    ['ROUTE', 'Routed', '✓', 'Done'],
    ['TRANSFORM', 'Mapped', '✓', 'Done'],
    ['DELIVER', 'Delivered', '✗', 'Failed'],
  ];
  for (const [index, [stage, label, mark, word]] of expected.entries()) {
    const item = items.nth(index);
    await expect(item).toHaveAttribute('data-strip-stage', stage);
    await expect(item.locator('.stage-step-label')).toHaveText(label);
    await expect(item.locator('.stage-step-mark')).toHaveText(mark);
    await expect(item.locator('.stage-step-mark'), 'the glyph is decoration; the word carries the state').toHaveAttribute('aria-hidden', 'true');
    await expect(item.locator('.stage-step-word'), `${label}: word visible`).toHaveText(word);
    await expect(item.locator('.stage-step-word')).toBeVisible();
  }
  await expect(items.nth(3), 'the failing step is the current one').toHaveAttribute('aria-current', 'step');
  for (const index of [0, 1, 2]) await expect(items.nth(index)).not.toHaveAttribute('aria-current', 'step');
});

test('@negative Scenario: a route-failed message - Mapped and Delivered read "Not reached", never a zero duration', async ({ page }) => {
  const flow = await freshFlow(page, 'strip-route');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTE_FAILED');
  seedStage(tenantId, msg, 'INTAKE', 0, 40);
  seedStage(tenantId, msg, 'ROUTE', 50, 90);
  await openDetail(page, msg);
  const items = strip(page).locator('[data-strip-stage]');
  await expect(items.nth(1).locator('.stage-step-mark')).toHaveText('✗');
  await expect(items.nth(1).locator('.stage-step-word')).toHaveText('Failed');
  for (const index of [2, 3]) {
    await expect(items.nth(index).locator('.stage-step-mark')).toHaveText('○');
    await expect(items.nth(index).locator('.stage-step-word')).toHaveText('Not reached');
    await expect(items.nth(index)).not.toContainText(/\b0\s*(ms|s)\b/);
  }
  await expect(nextStep(page)).toContainText('No destination matched this message.');
  await expect(nextStep(page), 'the guide is static text, not a live region that re-announces').not.toHaveAttribute('role', 'status');
});

test('@negative Scenario: a stage the data does not record reads "Not recorded", not 0 ms', async ({ page }) => {
  const flow = await freshFlow(page, 'strip-unrecorded');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTED');
  seedStage(tenantId, msg, 'INTAKE', 0, 40); // no ROUTE / TRANSFORM / DELIVER rows and no failure: nothing to say they happened
  await openDetail(page, msg);
  const items = strip(page).locator('[data-strip-stage]');
  await expect(items.nth(0).locator('.stage-step-word')).toHaveText('Done');
  for (const index of [1, 2, 3]) {
    await expect(items.nth(index).locator('.stage-step-mark')).toHaveText('–');
    await expect(items.nth(index).locator('.stage-step-word')).toHaveText('Not recorded');
    await expect(items.nth(index)).not.toContainText(/\b0\s*(ms|s)\b/);
  }
});

// --- What to do next: Retry / Replay wording by role ------------------------------------------------------------------------

test('Scenario: tenant admin and support are told to Replay a dead-lettered message; read-only is told who can and sees no button', async ({ page, browser }) => {
  test.setTimeout(90_000); // three sign-ins
  const { msg } = await deadLetterMessage(page, 'words-dead');

  const row = await openFailuresRow(page, msg); // tenant admin (the suite's signed-in page)
  await expect(row.locator('button[data-action="replay"]'), 'tenant admin: Replay is offered').toBeVisible();
  await openDetail(page, msg);
  await expect(nextStep(page)).toContainText('The target refused the message. Fix the mapping or the target settings.');
  await expect(nextStep(page)).toContainText(REPLAY_WORDS);
  await expect(nextStep(page)).not.toContainText('Only a tenant admin or support user');
  await expect(nextStep(page).getByRole('link', { name: 'Open the flow' })).toBeVisible();

  const support = await signedInAs(browser, 'SUPPORT');
  await openDetail(support.page, msg);
  await expect(nextStep(support.page)).toContainText(REPLAY_WORDS);
  await expect(nextStep(support.page), 'support cannot read configuration: no flow link').toHaveCount(1);
  await expect((await openFailuresRow(support.page, msg)).locator('button[data-action="replay"]'), 'support: Replay is offered').toBeVisible();
  await expect(nextStep(support.page).getByRole('link', { name: 'Open the flow' })).toHaveCount(0);
  await support.context.close();

  const reader = await signedInAs(browser, 'READ_ONLY');
  await openDetail(reader.page, msg);
  await expect(nextStep(reader.page)).toContainText(NOT_OPERATOR_REPLAY);
  await expect(nextStep(reader.page), 'read-only is not told to Replay').not.toContainText(REPLAY_WORDS);
  await expect(reader.page.locator(`${PAGE} ${ACTION_BUTTONS}`), 'read-only: no Retry / Replay button on the detail').toHaveCount(0);
  // Not asserted: the Failures LIST still offers Replay to read-only and the server refuses it with the 403 text - that contract is pinned by
  // operator-actions.docker.spec.mjs ('a READ_ONLY user ... is refused the replay'). Only the detail screen hides it (S3).
  await reader.context.close();
});

test('Scenario: a delivery waiting to retry - Retry now is named and offered to tenant admin and support only', async ({ page, browser }) => {
  test.setTimeout(90_000); // three sign-ins
  const flow = await freshFlow(page, 'words-retry');
  const tenantId = tenantIdOf();
  const msg = seedMessage(tenantId, flow.id, 'ROUTED');
  seedStage(tenantId, msg, 'INTAKE', 0, 40);
  seedStage(tenantId, msg, 'ROUTE', 50, 80);
  seedStage(tenantId, msg, 'TRANSFORM', 100, 300);
  seedStage(tenantId, msg, 'DELIVER', 400, null);
  seedDelivery(tenantId, flow.id, msg, 'RETRY_WAIT', 'TRANSIENT');

  await openDetail(page, msg);
  await expect(nextStep(page)).toContainText('This delivery is waiting to be sent again. The platform retries by itself.');
  await expect(nextStep(page)).toContainText(RETRY_WORDS);
  await expect(page.locator(`${PAGE} button[data-action="retry"]`), 'tenant admin: Retry now is offered').toBeVisible();

  const support = await signedInAs(browser, 'SUPPORT');
  await openDetail(support.page, msg);
  await expect(nextStep(support.page)).toContainText(RETRY_WORDS);
  await expect(support.page.locator(`${PAGE} button[data-action="retry"]`), 'support: Retry now is offered').toBeVisible();
  await support.context.close();

  const reader = await signedInAs(browser, 'READ_ONLY');
  await openDetail(reader.page, msg);
  await expect(nextStep(reader.page)).toContainText(NOT_OPERATOR_RETRY);
  await expect(nextStep(reader.page)).not.toContainText('Retry now');
  await expect(reader.page.locator(`${PAGE} ${ACTION_BUTTONS}`), 'read-only: no Retry now button on the detail').toHaveCount(0);
  await reader.context.close();
});

test('@negative @role Scenario: approver and configurator cannot read a message timeline - typing its address shows the NoAccess alert', async ({ page, browser }) => {
  test.setTimeout(60_000);
  const { msg } = await deadLetterMessage(page, 'words-noaccess');
  for (const role of ['APPROVER', 'CONFIGURATOR']) {
    const user = await signedInAs(browser, role);
    await user.page.goto(`${consoleUrl()}/#/messages/${msg}`);
    await expect(user.page.locator('main [role="alert"][data-no-access="true"]'), `${role}: NoAccess`).toBeVisible();
    await expect(user.page.locator('[data-next-step]'), `${role}: no guide behind the alert`).toHaveCount(0);
    await user.context.close();
  }
});

// --- the detail chrome ------------------------------------------------------------------------------------------------------

test('Scenario: breadcrumb Messages > short id, and the flow is linked by name for a role that reads configuration only', async ({ page, browser }) => {
  test.setTimeout(60_000);
  const { flow, msg } = await deadLetterMessage(page, 'chrome');
  await openDetail(page, msg);
  const crumb = page.getByRole('navigation', { name: 'Breadcrumb' });
  await expect(crumb.getByRole('link', { name: 'Messages' })).toHaveAttribute('href', '#/messages');
  await expect(crumb.locator('[aria-current="page"]')).toHaveText(`${msg.slice(0, 8)}...${msg.slice(-4)}`);
  const flowLink = page.locator(`${PAGE} a[href="#/flow/${flow.id}"]`).first();
  await expect(flowLink, 'tenant admin: the flow is a link').toBeVisible();
  await expect(flowLink, 'shown by name once the flow list answers').not.toHaveText(flow.id);

  const support = await signedInAs(browser, 'SUPPORT');
  await openDetail(support.page, msg);
  await expect(support.page.locator(`${PAGE} a[href="#/flow/${flow.id}"]`), 'support: plain text, no link').toHaveCount(0);
  await expect(support.page.locator(PAGE)).toContainText(flow.id);
  await support.context.close();
});

test('Scenario: Copy id puts the tracking id on the clipboard and says so in a status region', async ({ page }) => {
  const { msg } = await deadLetterMessage(page, 'copy');
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await openDetail(page, msg);
  const button = page.locator('[data-action="copy-id"]');
  await expect(button).toHaveText('Copy id');
  await expect(page.locator('[data-copy-status]')).toHaveAttribute('role', 'status');
  await button.click();
  await expect(page.locator('[data-copy-status]')).toHaveText('Copied the tracking id.');
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(msg);
});

// --- the Messages landing ---------------------------------------------------------------------------------------------------

for (const role of ['SUPPORT', 'READ_ONLY']) {
  test(`Scenario: ${role} on #/messages - search by tracking id, the tabs are links`, async ({ browser }) => {
    const { context, page } = await signedInAs(browser, role);
    await page.goto(`${consoleUrl()}/#/messages`);
    await expect(page.getByRole('heading', { level: 1, name: 'Messages' })).toBeVisible();
    const tabs = page.getByRole('navigation', { name: 'Messages views' });
    await expect(tabs.getByRole('link', { name: 'Results' })).toHaveAttribute('aria-current', 'page');
    await expect(tabs.getByRole('link', { name: 'Needs attention' })).toHaveAttribute('href', '#/failures');
    await expect(page.locator('[role="tab"]'), 'tabs are links, not role=tab buttons').toHaveCount(0);

    const hint = page.locator('#messages-tracking-hint');
    await expect(hint, 'the live region is mounted before anything is wrong').toHaveAttribute('role', 'status');
    await expect(hint).toHaveText('');
    const input = page.getByLabel('Tracking id');
    await input.fill('not-a-uuid');
    await page.getByRole('button', { name: 'Search' }).click();
    await expect(hint).toHaveText('Enter a tracking id (36 characters)');
    await expect(input).toHaveAttribute('aria-invalid', 'true');
    await expect(input).toHaveAttribute('aria-describedby', 'messages-tracking-hint');
    expect(new URL(page.url()).hash, 'an invalid id stays on the landing').toBe('#/messages');

    const id = randomUUID();
    await input.fill(`  ${id}  `);
    await page.getByRole('button', { name: 'Search' }).click();
    await page.waitForURL((url) => url.hash === `#/messages/${id}`);
    await context.close();
  });
}
