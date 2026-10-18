// T133 (row 323, F21 BS-11, karan-security): a masked field never leaks - T-066 (backlog.md:3236-3252).
//
//   tenant A declares customerId MASKED (T108) -> records carry MASK-PROBE-7Q3Z in customerId
//   -> success path (record 11, stub 200): DELIVERED; the target got the real value (positive control);
//      0 probe hits in the app log slice, the detail (timeline) API body, the failures API body
//   -> failure path (record 12, stub 403 that ECHOES the request body): DEAD_LETTERED; 0 hits in the log
//      slice, the failures API body (last error) and the detail API body - "including error and
//      diagnosis text"
//   -> intake refusal (record 13, amount not a number): 4xx; 0 hits in the log slice
//   -> screens (T112): detail + failures for 11 and 12 - 0 hits in the DOM and in every /api/ response
//      the screens loaded; the masked field shows the mask
//
// Waits for: T108 (how a field is declared masked, the API mask text) and T112 (the screen element).
// Each test is test.fixme NAMING those items while lib/mask-probe.mjs holds TBD(...) - never a silent
// pass; the names are read before any side effect, so a TBD leaves no half-done state.
// Also needs T106 (pipeline) and T110 (timeline / failures reads) like BS-10.
// NOT scanned on purpose: the WireMock journal (the target must get the real value) and the platform's
// payload store (it keeps the record; masking governs what is SHOWN).
// Never run against production; no psql-seeded row (unit/direct-sql.test.mjs scans this file).

import { expect, test } from '@playwright/test';
import { deliveryOfMessageSql, deliveryRowsSql } from '../lib/direct-sql.mjs';
import { bodyProblems, declareCall, echoStubMapping, leakingLines, maskName, probeHits, probeRecord, waitsFor } from '../lib/mask-probe.mjs';
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
const DECLARE = ['maskDeclareApi', 'maskDeclareBody'];
const API_MASK = [...DECLARE, 'maskApiMarker'];
const SCREEN_MASK = [...API_MASK, 'maskScreenField', 'maskScreenMarker'];

/** test.fixme while any of `keys` is TBD - the reason names the T-items, so the report shows what blocks it. */
function waitFor(keys) {
  const items = waitsFor(...keys);
  test.fixme(items.length > 0, `BS-11 waits for ${items.join(', ')}: lib/mask-probe.mjs still holds TBD(${items.join('|')})`);
}

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
  waitFor(DECLARE);
  const [method, path, body] = declareCall();
  await page.goto(`${bsEnv('consoleUrl')}/`);
  must(await inBrowser(page, method, path, body, A.admin), 'mask declaration');
});

test('BS-11.1: success path - delivered with the real value, 0 probe hits in log, detail and failures API', async ({ page }) => {
  waitFor(API_MASK);
  test.setTimeout(3 * 60 * 1000);
  const marker = maskName('maskApiMarker');
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
  expect(bodyProblems(await timelineOf(page, ids.success), { marker }), 'detail (timeline) API').toEqual([]);
  expect(bodyProblems(await failuresOf(page, ids.success)), 'failures API').toEqual([]);
});

test('BS-11.2: failure path - the target echoes the probe in its 403; 0 hits in log, failures and detail API', async ({ page }) => {
  waitFor(API_MASK);
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
  expect(bodyProblems(failures), 'failures API (last error)').toEqual([]);
  expect(bodyProblems(await timelineOf(page, ids.failure), { marker }), 'detail (timeline) API').toEqual([]);
});

test('BS-11.3: intake refusal - a record the contract rejects; 0 probe hits in the answer and the log', async ({ page }) => {
  waitFor(DECLARE);
  const { answer, logFrom } = await postProbe(page, probeRecord(13, { amount: 'not-a-number' }));
  expect(answer.status, 'the contract refuses the record').toBeGreaterThanOrEqual(400);
  expect(answer.status).toBeLessThan(500);
  expect(bodyProblems(answer.data), 'the refusal body').toEqual([]);
  await expectLogClean(logFrom, 'intake refusal');
});

test('BS-11.4: screens - detail and failures show the mask, 0 hits in the DOM and every API answer they load', async ({ page }) => {
  waitFor(SCREEN_MASK);
  const field = maskName('maskScreenField');
  const screenMarker = maskName('maskScreenMarker');
  expect(ids.success && ids.failure, 'BS-11.1 and BS-11.2 ran').toBeTruthy();
  const answers = [];
  page.on('response', async (r) => {
    if (r.url().includes('/api/')) answers.push({ url: r.url(), text: await r.text().catch(() => '') });
  });

  for (const messageId of [ids.success, ids.failure]) {
    await page.goto(`${bsEnv('consoleUrl')}/${fill(nameOf('detailRoute'), { trackingId: messageId })}`);
    const detail = page.locator(nameOf('detailPage'));
    await expect(detail).toHaveAttribute('data-message-loaded', 'true');
    await expect(detail.locator(field), `the masked ${messageId} customerId`).toHaveText(screenMarker);
    expect(probeHits(await page.content()), `detail screen of ${messageId}`).toEqual([]);
  }
  await page.goto(`${bsEnv('consoleUrl')}/${nameOf('failuresRoute')}`);
  await expect(page.locator(nameOf('failuresPage'))).toHaveAttribute('data-failures-loaded', 'true');
  await expect(page.locator(fill(nameOf('failureRow'), { trackingId: ids.failure }))).toBeVisible();
  expect(probeHits(await page.content()), 'failures screen').toEqual([]);

  expect(answers.length, 'the screens loaded their data through the API').toBeGreaterThan(0);
  expect(answers.filter((a) => probeHits(a.text).length > 0).map((a) => a.url), 'API answers carrying the probe').toEqual([]);
});
