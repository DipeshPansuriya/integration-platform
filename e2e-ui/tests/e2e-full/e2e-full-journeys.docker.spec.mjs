// E18 (T-175) the console's UI journeys of the full gate. NOT RUN yet: the Docker slots were held when this was
// written. Runnable once the seeded docker environment exists - the base-suite boot that this lane's config
// (tests/e2e-full/playwright.e2e-full-journeys.config.mjs) reuses.
//
// FAIL-CLOSED: when the boot is absent (e.g. under the default playwright.e2e-full.config.mjs, which boots only the
// screens stack) EVERY journey FAILS with the missing dependency named - never a silent skip, so a journey that did
// not run can never read as green. A journey that waits on an unbuilt item is test.fixme naming that item
// (J3 = the T107 gap, J6 = T120/T144), and tests/e2e-full/unit/gap-watch.test.mjs fails the day either gap closes.
//
// Journeys, each only what needs NO real partner broker: the API target is the boot's own WireMock
// (lib/wiremock.mjs's one stub per tenant path) and intake is driven as the source system (client
// credentials, support/api.mjs postRecord) - the console is source-only refused at intake by design
// (screens happy-path asserts that 403, ApiTargetCall), so the browser is the OBSERVER of the journey:
//   J1  intake for an API target: 202 + tracking id, exactly ONE translated delivery on the stub
//   J2  the tracking detail shows the ONE message's FOUR-stage timeline (INTAKE, ROUTE, TRANSFORM, DELIVER)
//   J3  [fixme T107 gap] the traceparent the source sent is shown in the tracking detail
//   J4  a dead-lettered delivery is visible on the failures screen and replayable from the operator screens
//   J5  tenant isolation: tenant B's session cannot open tenant A's message; A's record never reached B's target
//   J6  [fixme T120/T144] the report screen's counts equal the delivery rows
//   J7  dead-letter, fix the target, press Replay: the SAME delivery row delivers; exactly 2 sends; history kept;
//       tenant B's failures read never shows it
//
// The intake is addressed by the seeded interface code (intakeCode), NOT FLOW.code: "orders" is the flow's
// requested code, the interface gets a generated one (lib/interface-code.mjs), and posting to "orders" is a 404.
//
// Style: the screens suite's selectors (data-* of the built console, never text or position) and the
// base-suite's helpers (imported, never edited, per documents/e2e-full.md "reusing the base-suite seed").

import { randomBytes } from 'node:crypto';
import process from 'node:process';
import { expect, test } from '@playwright/test';
import { attemptRowsSql, deliveryOfMessageSql, deliveryRowsSql, messageRowsSql } from '../base-suite/lib/direct-sql.mjs';
import { BS_STORAGE_STATE } from '../base-suite/lib/suite-config.mjs';
import { SOURCE_CONTRACT, TENANTS, expectedTargetBody, orderRecord } from '../base-suite/lib/tenants.mjs';
import { traceIdOf, traceparentOf } from '../base-suite/lib/trace.mjs';
import { deliveriesTo } from '../base-suite/lib/wiremock.mjs';
import { eventually, inBrowser, journal, postRecord, sameAppProcess, setStub, sourceToken } from '../base-suite/support/api.mjs';
import { bsEnv } from '../base-suite/support/boot-state.mjs';
import { readRows } from '../base-suite/support/db.mjs';
import { readRun, addTracking } from '../base-suite/support/run-file.mjs';
import { intakeCode, rotatedSecret } from '../base-suite/support/seed.mjs';

const PIPELINE_MS = 90_000; // intake -> route -> transform -> deliver on one booted app (bs-07's budget)

// Fail closed: when the journeys' boot contract is not met the base-suite env is absent. Under the default e2e-full
// config (screens boot only) that is the normal case - every journey then FAILS, naming the missing dependency; it
// is never skipped. Only the journeys config boots the services (WireMock + Kafka + source clients + the dev-only
// exception).
let NOT_READY = null;
try {
  bsEnv('consoleUrl');
  bsEnv('issuer');
  bsEnv('wiremockAdmin');
  bsEnv('tenantA');
  bsEnv('tenantB');
} catch (e) {
  NOT_READY = e.message;
}

test.beforeEach(() => {
  if (NOT_READY) {
    throw new Error(`E18 UI journey cannot run - BLOCKED dependency (services boot): ${NOT_READY}. The journeys need the `
      + 'base-suite boot; run: npx playwright test -c tests/e2e-full/playwright.e2e-full-journeys.config.mjs '
      + '(under the docker lock). This is a FAILURE, not a skip.');
  }
});

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

// run-scoped: the journeys ride ONE walk over the seeded flow (order ids at an offset no other suite's scenario
// uses, so a shared docker session never collides on an order id). Tests run in file order on one worker; a failed
// J1 makes J2/J5 FAIL on their own precondition - nothing downstream is skipped.
let token = null;
let pageHandle = null;
const ORDER_OFFSET = 9000;

test.beforeAll(async ({ browser }) => {
  if (NOT_READY) return; // every test fails loudly in beforeEach
  // a hand-made context does not inherit `use.storageState`: pass the signed-in base-suite state explicitly
  pageHandle = await browser.newContext({ storageState: BS_STORAGE_STATE })
    .then((context) => context.newPage());
  await pageHandle.goto(`${bsEnv('consoleUrl')}/`);
});

test.afterAll(async () => {
  if (NOT_READY) return;
  // the stub ALWAYS answers 200 again, whatever a journey left behind
  try {
    await setStub(TENANTS.A.stubPath, 200);
  } finally {
    await pageHandle.close();
  }
});

// J1 ----------------------------------------------------------------------------------------------
test('J1 intake for the API target: 202, and exactly ONE translated delivery, without any partner broker', async () => {
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
// its [data-timeline] table lists EVERY pipeline stage row with its status.
test('J2 the tracking detail shows the four-stage timeline: INTAKE, ROUTE, TRANSFORM, DELIVER', async ({ page }) => {
  const trackingId = process.env.E18_TRACKING_ID;
  expect(trackingId, 'J1 stored the tracking id').toMatch(/^[0-9a-f-]{36}$/);

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
// test.fixme, never a runtime skip: the gap is named, and tests/e2e-full/unit/gap-watch.test.mjs fails the day the
// tracking read or screen carries the traceparent, telling whoever sees it to turn this into a running test.
test.fixme('J3 [T107 gap: the tracking detail carries no traceparent on main] the traceparent the source sent is shown in the tracking detail', async ({ page }) => {
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

  const timeline = await inBrowser(page, 'GET', `/api/v1/admin/messages/${answer.data.trackingId}/timeline`);
  expect(timeline.status).toBe(200);
  expect(String(timeline.data?.traceparent), 'the shown context IS the journey\'s one id: its trace id')
    .toBe(traceIdOf(tp) ?? String(tp));
});

// J4 ----------------------------------------------------------------------------------------------
// The dead-letter journey: the stub answers 403 (PERMANENT, ApiTargetCallTest), the delivery dead-letters,
// the failures screen shows it, and the operators' screens offer the Replay; the stub is restored to 200 by
// finally/afterAll so the suite leaves the environment as it found it.
test('J4 a dead-lettered delivery is visible on the failures screen and replayable from the operator screens', async ({ page }) => {
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

// J5 ----------------------------------------------------------------------------------------------
// Wrong tenant, end to end: A's message (J1) is invisible to tenant B's console session and read APIs, and nothing of
// A's reached B's target or B's rows (direct SQL + WireMock journal counts).
test('J5 tenant B cannot open tenant A\'s message (screen + API), and A\'s record never reached B\'s target or rows', async ({ page }) => {
  const trackingId = process.env.E18_TRACKING_ID;
  expect(trackingId, 'J1 stored the tracking id').toMatch(/^[0-9a-f-]{36}$/);

  await page.goto(`${bsEnv('consoleUrl')}/`);
  const forged = await inBrowser(page, 'GET', `/api/v1/admin/messages/${trackingId}/timeline`, undefined, TENANTS.B.admin);
  expect([403, 404], `B reads A's timeline: ${JSON.stringify(forged.data)}`).toContain(forged.status);
  expect(JSON.stringify(forged.data ?? ''), "nothing of A's in B's answer").not.toMatch(/\bA-(CUST|ORD)-|DELIVERED/);

  // the screen, as tenant B's session (the harness login marker rides every request of this page)
  await page.setExtraHTTPHeaders({ 'X-Harness-Login': TENANTS.B.admin });
  await page.goto('about:blank');
  await page.goto(`${bsEnv('consoleUrl')}/#/messages/${trackingId}`);
  await expect(page.locator('[data-page="message-detail"]')).toBeVisible();
  await expect(page.locator('[data-page="message-detail"]')).toHaveAttribute('data-load', /^(not-found|refused)$/);
  await expect(page.locator('[data-tracking-id]'), 'no tracking id of A shown to B').toHaveCount(0);
  await expect(page.locator('[data-message-state]'), 'no state of A shown to B').toHaveCount(0);

  // direct counts: B's tenant owns no row of A's message, and B's target saw nothing marked A
  const codeB = bsEnv('tenantB');
  expect(readRows(messageRowsSql(codeB)).filter(([id]) => id === trackingId), "B's messages hold none of A's").toHaveLength(0);
  expect(readRows(deliveryRowsSql(codeB)).filter(([messageId]) => messageId === trackingId), "B's deliveries hold none of A's").toHaveLength(0);
  const toB = await deliveriesTo(await journal(), TENANTS.B.stubPath);
  expect(toB.filter((d) => String(d.body?.referenceNo ?? '').startsWith('A-')), "0 of A's records on B's stub").toHaveLength(0);
});

// J6 ----------------------------------------------------------------------------------------------
test.fixme('J6 [T120/T144: the report is the stored daily roll-up; the DAILY_REPORT job rebuilds YESTERDAY only and this boot has no job trigger or clock advance] the report screen counts equal the delivery rows', async ({ page }) => {
  // Body kept runnable for the day the boot can refresh today's roll-up (AdjustableClock + a job run): the screen's
  // per-row `Deliveries` sum must equal the tenant's delivery rows of the day, from SQL.
  const rows = deliveriesOf();
  expect(rows.length, 'the walk produced deliveries').toBeGreaterThan(0);
  const day = rows[0].day;
  await page.goto(`${bsEnv('consoleUrl')}/#/reports`);
  await page.locator('[data-day-filter]').fill(day);
  await expect(page.locator('[data-delivery-report]')).toBeVisible();
  const shown = await page.locator('[data-report-row] td:nth-child(5)').allTextContents();
  expect(shown.reduce((sum, n) => sum + Number(n), 0), 'report deliveries = delivery rows of the day')
    .toBe(rows.filter((d) => d.day === day).length);
});

// J7 ----------------------------------------------------------------------------------------------
// Dead-letter, fix the target, press Replay in the console: the SAME delivery row delivers (E18-C2 "dead-letter then
// replay"). Real WireMock counts: ONE refused send, then exactly ONE accepted send, both with the delivery id as
// Idempotency-Key; the attempt history is append-only; tenant B's failures read never shows the dead letter.
test('J7 a dead letter replayed from the console after the target is fixed: the same delivery row delivers, 2 sends, history kept, invisible to tenant B', async ({ page }) => {
  test.setTimeout(6 * 60 * 1000);
  const record = orderRecord(TENANTS.A, ORDER_OFFSET + 4);
  const code = tenantCode();
  const deliveryOf = (messageId) => {
    const rows = readRows(deliveryOfMessageSql(code, messageId));
    return rows.length ? { id: rows[0][0], state: rows[0][1], count: rows.length } : null;
  };
  const sendsOf = async () => (await deliveriesTo(await journal(), TENANTS.A.stubPath)).filter((d) => d.body?.referenceNo === record.orderId);

  await setStub(TENANTS.A.stubPath, 403);
  try {
    await page.goto(`${bsEnv('consoleUrl')}/`);
    const answer = await postRecord(await sourceOf(page, TENANTS.A), intakeCode(TENANTS.A), SOURCE_CONTRACT.versionLabel, record);
    expect(answer.status, JSON.stringify(answer.data)).toBe(202);
    const trackingId = answer.data.trackingId;
    addTracking('A', [trackingId]);

    const dead = await eventually(() => {
      const d = deliveryOf(trackingId);
      return d?.state === 'DEAD_LETTERED' ? d : null;
    }, `the delivery of ${trackingId} DEAD_LETTERED`, { timeoutMs: PIPELINE_MS });
    const refused = await sendsOf();
    expect(refused, 'exactly ONE send reached the stub before the dead letter (PERMANENT never redelivers)').toHaveLength(1);
    expect(refused[0].deliveryId, 'the send carries the delivery id').toBe(dead.id);
    const attemptsBefore = readRows(attemptRowsSql(code, dead.id));
    expect(attemptsBefore.length, 'the refused send is on the attempt history').toBeGreaterThan(0);

    // wrong tenant: B's failures read does not list A's dead letter
    const bFailures = await inBrowser(page, 'GET', '/api/v1/admin/messages/failures', undefined, TENANTS.B.admin);
    expect([200, 403, 404], JSON.stringify(bFailures.data)).toContain(bFailures.status);
    expect(JSON.stringify(bFailures.data ?? ''), "A's dead letter is not in B's failures").not.toContain(trackingId);

    // fix the target, then press Replay on the failures screen
    await setStub(TENANTS.A.stubPath, 200);
    await page.goto(`${bsEnv('consoleUrl')}/#/failures`);
    await expect(page.locator('[data-page="failures"][data-failures-loaded="true"]')).toBeVisible();
    const row = page.locator(`[data-failure-row="${trackingId}"]`);
    await expect(row).toBeVisible();
    await row.locator('[data-action="replay"]').click();
    await expect(row.locator('[data-action-answer]')).toContainText('Replay queued');

    await eventually(() => (deliveryOf(trackingId)?.state === 'DELIVERED' ? true : null),
      'the replayed delivery DELIVERED', { timeoutMs: PIPELINE_MS });
    const after = deliveryOf(trackingId);
    expect(after.count, 'ONE delivery row for the message (the SAME row, not a new one)').toBe(1);
    expect(after.id, 'the same delivery id').toBe(dead.id);

    const attemptsAfter = readRows(attemptRowsSql(code, dead.id));
    expect(attemptsAfter.length, 'a new attempt was appended').toBeGreaterThan(attemptsBefore.length);
    expect(attemptsAfter.slice(0, attemptsBefore.length), 'the earlier attempts are unchanged (append-only)').toEqual(attemptsBefore);

    const sends = await eventually(async () => {
      const all = await sendsOf();
      return all.length >= 2 ? all : null;
    }, 'the replay send on the stub', { timeoutMs: 15_000 });
    expect(sends, 'exactly 2 sends in total: one refused, one accepted').toHaveLength(2);
    expect(new Set(sends.map((d) => d.deliveryId)), 'both sends carry the one delivery id').toEqual(new Set([dead.id]));
    expect(sends[1].body, 'the replay sends the contract-translated body').toEqual(expectedTargetBody(record));

    await page.goto(`${bsEnv('consoleUrl')}/#/messages/${trackingId}`);
    await expect(page.locator('[data-message-state]')).toHaveText('DELIVERED');
  } finally {
    await setStub(TENANTS.A.stubPath, 200); // no journey leaves a 403 behind
  }
});
