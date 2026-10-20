// T133 (row 323, F21 BS-11, karan-security): a masked field never leaks - T-066 (backlog.md:3236-3252).
//
//   tenant A declares customerId MASKED (T108) -> records carry MASK-PROBE-7Q3Z in customerId
//   -> success path (record 11, stub 200): DELIVERED; the target got the real value (positive control);
//      0 probe hits in the app log slice, the detail (timeline) API body, the failures API body
//   -> failure path (record 12, stub 403 that ECHOES the request body): DEAD_LETTERED; 0 hits in the log
//      slice, the failures API body (last error) and the detail API body - "including error and
//      diagnosis text"
//   -> intake refusal (record 13, amount not a number): 4xx; 0 hits in the log slice
//   -> last error text: the echo the target sent in its 403 is REDACTED to the mask marker ([MASKED])
//      in the failures API row and in the failures screen's lastError cell (T108's scrub; the screens
//      themselves are not the masking's owner - MessageDetail.tsx:10, Failures.tsx:10)
//   -> activity (E12): the tenant activity feed (GET /api/v1/activity, ActivityEndpointRoles.java:18)
//      holds the audited declare and delivery events - 0 probe hits in the body, before/after state
//      included (T134 point 5)
//   -> screens (T112): detail + failures for 11 and 12 - 0 hits in the DOM and in every /api/ response
//      the screens loaded
//
// Landed (all on feat/e9-e16 d5e2c6b): T108 (the flow draft-save PUT carries maskedFields, the marker
// is [MASKED]; every JSON log line scrubs registered values), T110 / T111 / T112, T106 (pipeline), E12.
// No fixme is left: a name regressing to a TBD makes the scenario red at its first maskName() read,
// never a silent pass.
// NOT scanned on purpose: the WireMock journal (the target must get the real value) and the platform's
// payload store (it keeps the record; masking governs what is SHOWN).
// Never run against production; no psql-seeded row (unit/direct-sql.test.mjs scans this file).

import { expect, test } from '@playwright/test';
import { deliveryOfMessageSql, deliveryRowsSql } from '../lib/direct-sql.mjs';
import { bodyProblems, declareCall, echoStubMapping, leakingLines, maskName, probeHits, probeRecord } from '../lib/mask-probe.mjs';
import { fill, nameOf } from '../lib/pending-names.mjs';
import { FLOW, SOURCE_CONTRACT, TENANTS, expectedTargetBody } from '../lib/tenants.mjs';
import { deliveriesTo } from '../lib/wiremock.mjs';
import { appLogFrom, appLogSize, eventually, inBrowser, journal, must, postRecord, setStub, sourceToken } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { readRows } from '../support/db.mjs';
import { addTracking, readRun } from '../support/run-file.mjs';
import { rotatedSecret } from '../support/seed.mjs';

test.describe.configure({ mode: 'serial' });

const PIPELINE_MS = 90000;
const A = TENANTS.A;

let stubChanged = false;
test.afterAll(async () => {
  if (stubChanged) await setStub(A.stubPath, 200); // never leave tenant A's target refusing for BS-12
});

const code = () => bsEnv('tenantA');
const deliveryOf = (messageId) => {
  const rows = readRows(deliveryOfMessageSql(code(), messageId));
  return rows.length === 1 ? { id: rows[0][0], state: rows[0][1] } : null;
};
const dayOf = (messageId) => readRows(deliveryRowsSql(code())).find(([m]) => m === messageId)?.[3];

async function putEchoStub() {
  const mapping = echoStubMapping(A.stubPath);
  const answer = await fetch(`${bsEnv('wiremockAdmin')}/mappings/${mapping.id}`, {
    method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify(mapping), signal: AbortSignal.timeout(5000)
  });
  if (answer.status !== 200) throw new Error(`WireMock echo stub ${A.stubPath}: HTTP ${answer.status}`);
}

/** Posts one probe record as tenant A; returns { answer, logFrom }. */
async function postProbe(page, record) {
  await page.goto(`${bsEnv('consoleUrl')}/`);
  const { clientId, secretValue } = await rotatedSecret(page, A, readRun().seed.A.credentialId);
  const token = await sourceToken(clientId, secretValue);
  const logFrom = await appLogSize();
  const answer = await postRecord(token, FLOW.code, SOURCE_CONTRACT.versionLabel, record);
  return { answer, logFrom };
}

/** The log slice since `from` holds 0 probe hits (lines reported redacted). */
async function expectLogClean(from, what) {
  const slice = await appLogFrom(from);
  expect(slice.length, `${what}: the log tap captured the run (an empty slice proves nothing)`).toBeGreaterThan(0);
  expect(leakingLines(slice), `${what}: app log lines carrying the probe`).toEqual([]);
}

const timelineOf = async (page, messageId) =>
  must(await inBrowser(page, 'GET', fill(nameOf('timelineApi'), { trackingId: messageId }), undefined, A.admin), `timeline of ${messageId}`);
const failuresOf = async (page, messageId) =>
  must(await inBrowser(page, 'GET', `${nameOf('failuresApi')}?flowId=${readRun().seed.A.flowId}&date=${dayOf(messageId)}`, undefined, A.admin), 'failures read');

const ids = {};

test('BS-11.0: tenant A declares customerId masked on the orders interface', async ({ page }) => {
  const [method, path, body] = declareCall();
  await page.goto(`${bsEnv('consoleUrl')}/`);
  must(await inBrowser(page, method, path, body, A.admin), 'mask declaration');
});

test('BS-11.1: success path - delivered with the real value, 0 probe hits in log, detail and failures API', async ({ page }) => {
  test.setTimeout(3 * 60 * 1000);
  const record = probeRecord(11);
  const { answer, logFrom } = await postProbe(page, record);
  expect(answer.status, `intake: ${answer.status}`).toBe(202);
  expect(probeHits(JSON.stringify(answer.data)), 'the 202 body').toEqual([]);
  ids.success = answer.data.trackingId;
  addTracking('A', [ids.success]);
  // TBD(T106): the relay + consumers that route, transform and send an accepted message
  const delivery = await eventually(() => (deliveryOf(ids.success)?.state === 'DELIVERED' ? deliveryOf(ids.success) : null),
    `the delivery of ${ids.success} delivered`, { timeoutMs: PIPELINE_MS });
  const sends = deliveriesTo(await journal(), A.stubPath).filter((d) => d.deliveryId === delivery.id);
  expect(sends, 'one send').toHaveLength(1);
  expect(sends[0].body, 'positive control: masking must not change what the target receives').toEqual(expectedTargetBody(record));

  await expectLogClean(logFrom, 'success path');
  // the API shows no payload field, so no marker is expected on a delivered message - only 0 leaks
  expect(bodyProblems(await timelineOf(page, ids.success)), 'detail (timeline) API').toEqual([]);
  expect(bodyProblems(await failuresOf(page, ids.success)), 'failures API').toEqual([]);
});

test('BS-11.2: failure path - the target echoes the probe in its 403; 0 hits in log, failures and detail API', async ({ page }) => {
  test.setTimeout(3 * 60 * 1000);
  const marker = maskName('maskApiMarker');
  await putEchoStub();
  stubChanged = true;
  const record = probeRecord(12);
  const { answer, logFrom } = await postProbe(page, record);
  expect(answer.status, `intake: ${answer.status}`).toBe(202);
  ids.failure = answer.data.trackingId;
  addTracking('A', [ids.failure]);
  const delivery = await eventually(() => (deliveryOf(ids.failure)?.state === 'DEAD_LETTERED' ? deliveryOf(ids.failure) : null),
    `the delivery of ${ids.failure} dead-lettered`, { timeoutMs: PIPELINE_MS });
  // the probe really was in the target's error text - otherwise the scan below proves nothing
  const sends = deliveriesTo(await journal(), A.stubPath).filter((d) => d.deliveryId === delivery.id);
  expect(sends, 'one send reached the echo stub').toHaveLength(1);
  await setStub(A.stubPath, 200);
  stubChanged = false;

  await expectLogClean(logFrom, 'failure path');
  const failures = await failuresOf(page, ids.failure);
  expect(failures.some((f) => f.trackingId === ids.failure), 'the dead letter is listed').toBe(true);
  const dead = failures.find((f) => f.trackingId === ids.failure);
  expect(String(dead?.lastError ?? ''), 'the echoed 403 text is redacted to the marker, not dropped').toContain(marker);
  expect(bodyProblems(failures), 'failures API (last error)').toEqual([]);
  expect(bodyProblems(await timelineOf(page, ids.failure), { marker }), 'detail (timeline) API').toEqual([]);
});

test('BS-11.3: intake refusal - a record the contract rejects; 0 probe hits in the answer and the log', async ({ page }) => {
  const { answer, logFrom } = await postProbe(page, probeRecord(13, { amount: 'not-a-number' }));
  expect(answer.status, 'the contract refuses the record').toBeGreaterThanOrEqual(400);
  expect(answer.status).toBeLessThan(500);
  expect(bodyProblems(answer.data), 'the refusal body').toEqual([]);
  await expectLogClean(logFrom, 'intake refusal');
});

test('BS-11.4: screens - 0 probe hits in the DOM and every API answer; the echoed error shows the mask, not the value', async ({ page }) => {
  const lastErrorCell = maskName('maskScreenLastCell'); // the failures row cell only - T112 shows no payload field
  const marker = maskName('maskApiMarker');
  expect(ids.success && ids.failure, 'BS-11.1 and BS-11.2 ran').toBeTruthy();
  const answers = [];
  page.on('response', async (r) => {
    if (r.url().includes('/api/')) answers.push({ url: r.url(), text: await r.text().catch(() => '') });
  });

  for (const messageId of [ids.success, ids.failure]) {
    await page.goto(`${bsEnv('consoleUrl')}/${fill(nameOf('detailRoute'), { trackingId: messageId })}`);
    await expect(page.locator(nameOf('detailPage'))).toHaveAttribute('data-message-loaded', 'true');
    expect(probeHits(await page.content()), `detail screen of ${messageId}`).toEqual([]);
  }
  await page.goto(`${bsEnv('consoleUrl')}/${nameOf('failuresRoute')}`);
  await expect(page.locator(nameOf('failuresPage'))).toHaveAttribute('data-failures-loaded', 'true');
  const failureRow = page.locator(fill(nameOf('failureRow'), { trackingId: ids.failure }));
  await expect(failureRow).toBeVisible();
  await expect(failureRow.locator(lastErrorCell), 'the echoed 403 text shows [MASKED], never the value').toContainText(marker);
  expect(probeHits(await page.content()), 'failures screen').toEqual([]);

  expect(answers.length, 'the screens loaded their data through the API').toBeGreaterThan(0);
  expect(answers.filter((a) => probeHits(a.text).length > 0).map((a) => a.url), 'API answers carrying the probe').toEqual([]);
});

test('BS-11.5: activity - the declare and the probe records leave no probe in the tenant activity feed (E12)', async ({ page }) => {
  expect(ids.success && ids.failure, 'BS-11.1 and BS-11.2 ran').toBeTruthy();
  const activity = must(await inBrowser(page, 'GET', '/api/v1/activity', undefined, A.admin), 'activity read');
  expect(activity.length, 'the feed holds the audited declare and delivery events').toBeGreaterThan(0);
  expect(bodyProblems(activity), 'activity body, before/after state included (T134 point 5)').toEqual([]);
});
