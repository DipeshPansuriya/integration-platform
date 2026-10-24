// E18 (T-175) the console's UI journeys of the full gate, authored on feature/playwright-author (lane
// playwright-author), NOT RUN in this lane (the Docker slots were held and the brief forbids a run): every
// scenario is runnable once the seeded docker environment exists - the base-suite boot this lane's config
// (tests/e2e-full/playwright.e2e-full-journeys.config.mjs) reuses. Until then Playwright marks each skipped
// test with the exact dependency it waits on (the fail-closed contract of the base-suite boot).
//
// Journeys, each only what needs NO real partner broker: the API target is the boot's own WireMock
// (lib/wiremock.mjs's one stub per tenant path) and intake is driven as the source system (client
// credentials, support/api.mjs postRecord) - the console is source-only refused at intake by design
// (screens happy-path asserts that 403, ApiTargetCall), so the browser is the OBSERVER of the journey:
//   J1  intake for an API target: 202 + tracking id, exactly ONE translated delivery on the stub
//   J2  the tracking detail shows the ONE message's FOUR-stage timeline (INTAKE, ROUTE, TRANSFORM, DELIVER)
//   J3  the traceparent the source sent is shown in the tracking detail [BLOCKED product gap: the timeline
//       API carries no traceparent field on main - T107 carries it as the Kafka header only]
//   J4  a dead-lettered delivery is visible on the failures screen and replayable from the operator screens
//
// Style: the screens suite's selectors (data-* of the built console, never text or position) and the
// base-suite's helpers (imported, never edited, per documents/e2e-full.md "reusing the base-suite seed").

import { randomBytes } from 'node:crypto';
import process from 'node:process';
import { expect, test } from '@playwright/test';
import { deliveryRowsSql } from '../base-suite/lib/direct-sql.mjs';
import { SOURCE_CONTRACT, TENANTS, expectedTargetBody, orderRecord } from '../base-suite/lib/tenants.mjs';
import { traceIdOf, traceparentOf } from '../base-suite/lib/trace.mjs';
import { deliveriesTo } from '../base-suite/lib/wiremock.mjs';
import { eventually, inBrowser, journal, postRecord, sameAppProcess, setStub, sourceToken } from '../base-suite/support/api.mjs';
import { bsEnv } from '../base-suite/support/boot-state.mjs';
import { readRows } from '../base-suite/support/db.mjs';
import { readRun, addTracking } from '../base-suite/support/run-file.mjs';
import { intakeCode, rotatedSecret } from '../base-suite/support/seed.mjs';

test.describe.configure({ mode: 'serial' });

const PIPELINE_MS = 90_000; // intake -> route -> transform -> deliver on one booted app (bs-07's budget)

// Fail closed BEFORE the first boot when the journeys' boot contract is not met: the base-suite
// global-setup (the config above) fails its own run the same way, so this guard names the dependency
// in the skip annotation instead of a confusing boot error of a mis-configured environment.
let NOT_READY = null;
try {
  bsEnv('consoleUrl');
  bsEnv('issuer');
  bsEnv('wiremockAdmin');
} catch (e) {
  NOT_READY = e.message;
}
test.skip(!!NOT_READY, `BLOCKED dependency (services boot): ${NOT_READY ?? ''} - the journeys need the base-suite `
  + 'boot (WireMock + Kafka + source credentials + the dev-only exception); run through the lane config '
  + 'tests/e2e-full/playwright.e2e-full-journeys.config.mjs');

/** The tenant's source client, rotated again for this run (secret in memory only), and its token. */
async function sourceOf(page, tenant) {
  const { credentialId } = readRun().seed[tenant.key];
  const { clientId, secretValue } = await rotatedSecret(page, tenant, credentialId);
  return sourceToken(clientId, secretValue);
}

const tenantCode = () => bsEnv('tenantA');
/** The tenant's delivery rows of one message id: [messageId, flowId, state, day]. */
const deliveriesOf = () => readRows(deliveryRowsSql(tenantCode())).map(([messageId, flowId, state, day]) => ({ messageId, flowId, state, day }));
const deliveredStubHits = async (record) =>
  (await deliveriesTo(await journal(), TENANTS.A.stubPath)).filter((d) => d.body?.referenceNo === record.orderId);

// run-scoped: the journeys ride ONE serial walk over the seeded flow (order ids at an offset no other
// suite's scenario uses, so a shared docker session never collides on an order id)
let token = null;
let pageHandle = null;
const ORDER_OFFSET = 9000;

test.beforeAll(async ({ browser }) => {
  pageHandle = await browser.newContext()
    .then((context) => context.newPage());
  await pageHandle.goto(`${bsEnv('consoleUrl')}/`);
});

test.afterAll(async () => {
  // the stub ALWAYS answers 200 again, whatever a journey left behind
  try {
    await setStub(TENANTS.A.stubPath, 200);
  } finally {
    await pageHandle.close();
  }
});

// J1 ----------------------------------------------------------------------------------------------
test('intake for the API target: 202, and exactly ONE translated delivery, without any partner broker', async () => {
  token = await sourceOf(pageHandle, TENANTS.A);
  expect(token, 'a source client token from the boot realm').toBeTruthy();

  const record = orderRecord(TENANTS.A, ORDER_OFFSET + 1);
  const answer = await postRecord(token, intakeCode(TENANTS.A), SOURCE_CONTRACT.versionLabel, record);
  expect(answer.status, JSON.stringify(answer.data)).toBe(202);
  expect(typeof answer.data?.trackingId).toBe('string');
  addTracking('A', [answer.data.trackingId]);
  process.env.E18_TRACKING_ID = answer.data.trackingId;

  // the delivery is the API adapter's send (the WireMock journal, never a real broker)
  const delivered = await eventually(async () => {
    const hits = await deliveredStubHits(record);
    return hits.length > 0 ? hits : null;
  }, `a delivery of ${record.orderId} on ${TENANTS.A.stubPath}`, { timeoutMs: PIPELINE_MS });
  expect(delivered, 'exactly ONE delivery').toHaveLength(1);
  expect(delivered[0].body, 'the contract-translated body {clientId, referenceNo, invoiceValue}').toEqual(expectedTargetBody(record));

  const row = deliveriesOf().find((d) => d.messageId === answer.data.trackingId);
  expect(row, 'one delivery row for the message').toBeTruthy();
  await eventually(() => (deliveriesOf().some((d) => d.messageId === answer.data.trackingId && d.state === 'DELIVERED') ? true : null),
    'the delivery row DELIVERED', { timeoutMs: 15_000 });
  expect(deliveriesOf().find((d) => d.messageId === answer.data.trackingId).state, 'the delivery settles DELIVERED')
    .toBe('DELIVERED');
  expect(sameAppProcess(), '0 deploys: the one app process still serves').toBe(true);
});

// J2 ----------------------------------------------------------------------------------------------
// The tracking screen is #/messages/<trackingId> (MessageDetail.tsx: no menu entry, reached from Failures);
// its [data-timeline] table lists EVERY pipeline stage row with its status (MessageDetail.tsx:180-209).
test('the tracking detail shows the four-stage timeline: INTAKE, ROUTE, TRANSFORM, DELIVER', async ({ page }) => {
  const trackingId = process.env.E18_TRACKING_ID;
  expect(trackingId, 'J1 stored the tracking id (serial walk)').toMatch(/^[0-9a-f-]{36}$/);

  await page.goto(`${bsEnv('consoleUrl')}/#/messages/${trackingId}`);
  await expect(page.locator('[data-page="message-detail"][data-message-loaded="true"]')).toBeVisible();

  await expect(page.locator('[data-tracking-id]')).toHaveText(trackingId);
  // the MESSAGE state is the routing outcome (ACCEPTED / ROUTED / ROUTE_FAILED, lib/counts.mjs:13); DELIVERED is a DELIVERY state, proven
  // by J1 and by the DELIVER stage row below (run 2 of 2026-10-22 read ROUTED here: a test defect, not a product one)
  // the dd shows the word; the raw enum rides in data-state-raw
  await expect(page.locator('[data-message-state]')).toHaveAttribute('data-state-raw', 'ROUTED');
  for (const stage of ['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER']) {
    const row = page.locator(`[data-timeline] tr[data-stage-row="${stage}"]`);
    await expect(row, `the ${stage} stage row is on the timeline`).toHaveCount(1);
    await expect(row).toHaveAttribute('data-stage-status', 'done'); // every stage completed the journey
  }
});

// J3 ----------------------------------------------------------------------------------------------
test('the traceparent the source sent is shown in the tracking detail', async ({ page }) => {
  await page.goto(`${bsEnv('consoleUrl')}/`); // the page fixture starts at about:blank: no cookie to read until the console is open
  const tp = traceparentOf(randomBytes(16), randomBytes(8)); // the fresh trace id the journey claims
  const record = orderRecord(TENANTS.A, ORDER_OFFSET + 2);
  const answer = await postRecord(await sourceOf(page, TENANTS.A), intakeCode(TENANTS.A), SOURCE_CONTRACT.versionLabel, record,
    { traceparent: tp });
  expect(answer.status, JSON.stringify(answer.data)).toBe(202);
  await eventually(async () => {
    const hits = await deliveredStubHits(record);
    return hits.length > 0 ? hits : null;
  }, `a delivery of ${record.orderId} on ${TENANTS.A.stubPath}`, { timeoutMs: PIPELINE_MS });

  // what the tracking READ answers today (main): no traceparent field - the journey's product gap
  const timeline = await inBrowser(page, 'GET', `/api/v1/admin/messages/${answer.data.trackingId}/timeline`);
  expect(timeline.status).toBe(200);
  const shown = timeline.data?.traceparent;
  if (shown === undefined || shown === null) {
    test.skip(true, 'BLOCKED product gap: the tracking detail carries no traceparent on main (T107 surfaces it as '
      + 'the Kafka record header only; the timeline API response has no such field) - run when the '
      + 'tracking read or detail screen shows the correlation context');
    return;
  }
  expect(String(shown), 'the shown context IS the journey\'s one id: its trace id')
    .toBe(traceIdOf(tp) ?? String(tp));
});

// J4 ----------------------------------------------------------------------------------------------
// The dead-letter journey: the stub answers 403 (PERMANENT, ApiTargetCallTest), the delivery dead-letters,
// the failures screen shows it, and the operators' screens offer the Replay; the stub is restored to 200 by
// afterAll so the suite leaves the environment as it found it.
test('a dead-lettered delivery is visible on the failures screen and replayable from the operator screens', async ({ page }) => {
  const record = orderRecord(TENANTS.A, ORDER_OFFSET + 3);
  await page.goto(`${bsEnv('consoleUrl')}/`); // same: the in-page API helpers need the console origin
  await setStub(TENANTS.A.stubPath, 403); // the refusal that dead-letters
  try {
    const answer = await postRecord(await sourceOf(page, TENANTS.A), intakeCode(TENANTS.A), SOURCE_CONTRACT.versionLabel, record);
    expect(answer.status, JSON.stringify(answer.data)).toBe(202);

    await eventually(() =>
      (deliveriesOf().some((d) => d.messageId === answer.data.trackingId && d.state === 'DEAD_LETTERED') ? true : null),
    'the delivery DEAD_LETTERED', { timeoutMs: PIPELINE_MS });

    // the failures screen (the operator's dead-letter list) shows the message, its stage and its action
    await page.goto(`${bsEnv('consoleUrl')}/#/failures`);
    await expect(page.locator('[data-page="failures"][data-failures-loaded="true"]')).toBeVisible();
    const row = page.locator(`[data-failure-row="${answer.data.trackingId}"][data-failing-stage="DELIVER"]`);
    await expect(row).toBeVisible();
    const replay = row.locator('[data-action="replay"]');
    await expect(replay, 'the row carries the Replay of the dead-lettered delivery').toBeVisible();

    // the tracking detail's own delivery action: Replay of the DEAD_LETTERED delivery (T165 M2)
    await page.goto(`${bsEnv('consoleUrl')}/#/messages/${answer.data.trackingId}`);
    const detailAction = page.locator('[data-delivery-action]').filter({ has: page.locator('[data-action="replay"]') });
    await expect(detailAction).toBeVisible();
    await detailAction.locator('[data-action="replay"]').click();
    await expect(page.locator('[data-action-answer]')).toContainText('Replay queued');
  } finally {
    await setStub(TENANTS.A.stubPath, 200); // no journey leaves a 403 behind
  }
});