// T133 (row 323, F21 BS-11, karan-security): a masked field never leaks - T-066 (backlog.md:3236-3252).
//
//   tenant A declares cardNumber MASKED (T108; T206: not customerId - the platform refuses a body customerId that is
//   not the credential's customer with a bare 403 BODY_CUSTOMER before intake, so a probe there never arrived, B36)
//   -> records carry MASK-PROBE-7Q3Z in cardNumber (customerId stays the credential's own customer; cardNumber is
//      mapped to the target's cardRef) and every probe POST must be ACCEPTED 202 - a refusal is a failure, never a pass
//   -> success path (record 11, stub 200): DELIVERED; the target got the real value (positive control);
//      0 probe hits in the app log slice, the detail (timeline) API body, the failures API body
//   -> failure path (record 12, stub 403 that ECHOES the request body): DEAD_LETTERED; 0 hits in the log
//      slice, the failures API body (last error) and the detail API body - "including error and
//      diagnosis text"
//   -> intake refusal (record 13, amount not a number): a CONTRACT refusal 400/422 naming amount (never the 403 identity
//      gate), a green control (same body, valid amount: 202); 0 hits in the answer and the log slice
//   -> screens (T112): detail + failures for 11 and 12 - 0 hits in the DOM and in every /api/ response
//      the screens loaded. The mask itself is checked at API level only (Dipesh, option 1): the T112 screens
//      carry no payload field, so there is no "screen shows the mask" leg.
//
// Gates (lib/pending-names.mjs ON_MAIN via waitsOn): BS-11.0 and 11.3 need T108; 11.1, 11.2 and 11.4 also need
// T110 and DELIVERY_BASE_URL (a delivery that reaches the one WireMock). A missing item is test.fixme naming it,
// never a silent pass; the names are read before any side effect.
// NOT scanned on purpose: the WireMock journal (the target must get the real value) and the platform's
// payload store (it keeps the record; masking governs what is SHOWN).
// Never run against production; no psql-seeded row (unit/direct-sql.test.mjs scans this file).

import { expect, test } from '@playwright/test';
import { deliveryOfMessageSql, deliveryRowsSql } from '../lib/direct-sql.mjs';
import { MASKED_FIELD, PROBE, acceptanceProblems, bodyProblems, contractRefusalProblems, declareCall, echoStubMapping, leakingAnswers, leakingLines, probeHits, probeRecord } from '../lib/mask-probe.mjs';
import { fill, nameOf, waitsOn } from '../lib/pending-names.mjs';
import { FLOW, SOURCE_CONTRACT, TENANTS, expectedTargetBody } from '../lib/tenants.mjs';
import { deliveriesTo, header } from '../lib/wiremock.mjs';
import { appLogFrom, appLogSize, eventually, inBrowser, journal, must, postRecord, setStub, sourceToken } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { readRows } from '../support/db.mjs';
import { addTracking, readRun } from '../support/run-file.mjs';
import { intakeCode, rotatedSecret } from '../support/seed.mjs';

test.describe.configure({ mode: 'serial' });

const PIPELINE_MS = 90000;
const A = TENANTS.A;

/** test.fixme while an item is not on main - the reason names it, so the report shows what blocks it. */
function waitFor(...items) {
  const reason = waitsOn(...items);
  test.fixme(reason !== null, reason ?? '');
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
  const answer = await postRecord(token, intakeCode(A), SOURCE_CONTRACT.versionLabel, record);
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

test('BS-11.0: tenant A declares cardNumber masked on the orders flow - draft-save, submit, approve, publish', async ({ page }) => {
  waitFor('T108');
  const { flowId } = readRun().seed.A;
  await page.goto(`${bsEnv('consoleUrl')}/`);
  const current = must(await inBrowser(page, 'GET', `/api/v1/admin/flows/${encodeURIComponent(flowId)}`, undefined, A.admin), 'read orders flow');
  const [method, path, body] = declareCall(flowId, current);
  must(await inBrowser(page, method, path, body, A.admin), 'mask declaration (draft-save)');
  must(await inBrowser(page, 'POST', `/api/v1/admin/flows/${flowId}/submit`, { changeNote: `BS-11: ${MASKED_FIELD} masked` }, A.admin), 'submit');
  must(await inBrowser(page, 'POST', `/api/v1/admin/flows/${flowId}/approve`, undefined, A.approver), 'approve by the approver, never the author');
  must(await inBrowser(page, 'POST', `/api/v1/admin/flows/${flowId}/publish`, undefined, A.admin), 'publish');
  const published = must(await inBrowser(page, 'GET', `/api/v1/admin/flows/${encodeURIComponent(flowId)}`, undefined, A.admin), 'read published orders flow');
  expect(published.maskedFields, `the published flow lists ${MASKED_FIELD} as masked`).toContain(MASKED_FIELD);
});

test('BS-11.1: success path - delivered with the real value, 0 probe hits in log, detail and failures API', async ({ page }) => {
  waitFor('T108', 'T110', 'DELIVERY_BASE_URL');
  test.setTimeout(3 * 60 * 1000);
  const record = probeRecord(11);
  const { answer, logFrom } = await postProbe(page, record);
  // B36: the probe must be ACCEPTED - a 403 (or any refusal) is a failure here, never a vacuous pass
  expect(acceptanceProblems(answer), 'the probe record was accepted by intake (202 + tracking id)').toEqual([]);
  expect(probeHits(JSON.stringify(answer.data)), 'the 202 body').toEqual([]);
  ids.success = answer.data.trackingId;
  addTracking('A', [ids.success]);
  // TBD(T106): the relay + consumers that route, transform and send an accepted message
  const delivery = await eventually(() => (deliveryOf(ids.success)?.state === 'DELIVERED' ? deliveryOf(ids.success) : null),
    `the delivery of ${ids.success} delivered`, { timeoutMs: PIPELINE_MS });
  const sends = deliveriesTo(await journal(), A.stubPath).filter((d) => d.deliveryId === delivery.id);
  expect(sends, 'one send').toHaveLength(1);
  expect(sends[0].body, 'positive control: masking must not change what the target receives').toEqual(expectedTargetBody(record));
  expect(sends[0].body.cardRef, 'the target got the real probe value in the masked field').toBe(PROBE);

  await expectLogClean(logFrom, 'success path');
  expect(bodyProblems(await timelineOf(page, ids.success)), 'detail (timeline) API').toEqual([]);
  expect(bodyProblems(await failuresOf(page, ids.success)), 'failures API').toEqual([]);
});

test('BS-11.2: failure path - the target echoes the probe in its 403; 0 hits in log, failures and detail API', async ({ page }) => {
  waitFor('T108', 'T110', 'DELIVERY_BASE_URL');
  test.setTimeout(3 * 60 * 1000);
  await putEchoStub();
  stubChanged = true;
  const record = probeRecord(12);
  const { answer, logFrom } = await postProbe(page, record);
  expect(acceptanceProblems(answer), 'the probe record was accepted by intake (202 + tracking id)').toEqual([]);
  ids.failure = answer.data.trackingId;
  addTracking('A', [ids.failure]);
  const delivery = await eventually(() => (deliveryOf(ids.failure)?.state === 'DEAD_LETTERED' ? deliveryOf(ids.failure) : null),
    `the delivery of ${ids.failure} dead-lettered`, { timeoutMs: PIPELINE_MS });
  // the probe really was in the target's error text - otherwise the scan below proves nothing
  const sends = deliveriesTo(await journal(), A.stubPath).filter((d) => d.deliveryId === delivery.id);
  expect(sends, 'one send reached the echo stub').toHaveLength(1);
  expect(sends[0].body.cardRef, 'the send carried the real probe').toBe(PROBE);
  // positive control: the target's 403 answer really ECHOED the probe (WireMock's journal keeps what it answered)
  const served = ((await journal()).requests ?? []).find((r) => header(r.request.headers, 'Idempotency-Key') === delivery.id);
  expect(served?.response?.status, 'the echo stub answered 403').toBe(403);
  expect(probeHits(served?.response?.body ?? ''), 'the error text of the target carried the probe - the scans below are not vacuous').not.toEqual([]);
  await setStub(A.stubPath, 200);
  stubChanged = false;

  await expectLogClean(logFrom, 'failure path');
  const failures = await failuresOf(page, ids.failure);
  expect(failures.some((f) => f.trackingId === ids.failure), 'the dead letter is listed').toBe(true);
  // measured (T206 run): the platform keeps only "the target answered HTTP 403 ... class PERMANENT" as the last error - it does
  // not store the target's answer text - so there is no mask marker to expect; the guard is 0 probe in any form
  const mine = failures.find((f) => f.trackingId === ids.failure);
  expect(mine.lastError, 'the last error records the target refusal').toMatch(/HTTP 403/);
  expect(bodyProblems(failures), 'failures API (last error): no probe in any form').toEqual([]);
  expect(bodyProblems(await timelineOf(page, ids.failure)), 'detail (timeline) API').toEqual([]);
});

// B37: intake now validates the record against the published contract (T206 found it accepted amount:'not-a-number' with 202).
// The flag INTAKE_CONTRACT_VALIDATION is true; the assertion is unchanged.
test('BS-11.3: intake refusal - a record the contract rejects (400/422 naming amount); control accepted; 0 probe hits in the answer and the log', async ({ page }) => {
  waitFor('T108', 'INTAKE_CONTRACT_VALIDATION');
  const { answer, logFrom } = await postProbe(page, probeRecord(13, { amount: 'not-a-number' }));
  // B36: the refusal must come from the CONTRACT (400/422 + a reason naming amount), not from the 403 identity gate
  expect(contractRefusalProblems(answer, 'amount'), `the contract refuses the record: ${answer.status} ${JSON.stringify(answer.data)}`).toEqual([]);
  expect(bodyProblems(answer.data), 'the refusal body').toEqual([]);
  await expectLogClean(logFrom, 'intake refusal');
  // green control: the same body with a valid amount is accepted - so the refusal above is about amount, nothing else
  const control = await postProbe(page, probeRecord(13));
  expect(acceptanceProblems(control.answer), 'control: the same record with a valid amount is accepted').toEqual([]);
  addTracking('A', [control.answer.data.trackingId]);
  await expectLogClean(control.logFrom, 'intake refusal control');
});

test('BS-11.3b: intake refusal - a record without its business key orderId (the contract requires it): 422 naming orderId; control accepted; 0 probe hits in the answer and the log', async ({ page }) => {
  waitFor('T108');
  const { orderId: _dropped, ...withoutKey } = probeRecord(14);
  const { answer, logFrom } = await postProbe(page, withoutKey);
  expect(contractRefusalProblems(answer, 'orderId'), `intake refuses a record without its business key: ${answer.status} ${JSON.stringify(answer.data)}`).toEqual([]);
  expect(answer.status, 'the intake refusal status').toBe(422);
  // B37: validation runs BEFORE business-key extraction (ADR-011 Decision 4), and this contract requires orderId, so the missing key is
  // reported by the contract with every other violation (was BUSINESS_KEY_UNRESOLVED when intake never read the contract)
  expect(answer.data.code, 'the refusal reason').toBe('PAYLOAD_VALIDATION_FAILED');
  expect(answer.data.violations.map((v) => `${v.field}:${v.code}`), 'the violation names the missing key').toContain('orderId:SCHEMA_REQUIRED');
  expect(bodyProblems(answer.data), 'the refusal body').toEqual([]);
  await expectLogClean(logFrom, 'business-key refusal');
  // green control: the same record WITH its business key is accepted - so the refusal above is about the key, nothing else
  const control = await postProbe(page, probeRecord(14));
  expect(acceptanceProblems(control.answer), 'control: the same record with its business key is accepted').toEqual([]);
  addTracking('A', [control.answer.data.trackingId]);
  await expectLogClean(control.logFrom, 'business-key refusal control');
});

test('BS-11.4: screens - 0 probe hits in the DOM of detail and failures and in every API answer they load', async ({ page }) => {
  waitFor('T108', 'T110', 'DELIVERY_BASE_URL');
  expect(ids.success && ids.failure, 'BS-11.1 and BS-11.2 ran').toBeTruthy();
  const answers = [];
  page.on('response', async (r) => {
    if (r.url().includes('/api/')) answers.push({ url: r.url(), text: await r.text().catch(() => '') });
  });

  for (const messageId of [ids.success, ids.failure]) {
    await page.goto(`${bsEnv('consoleUrl')}/${fill(nameOf('detailRoute'), { trackingId: messageId })}`);
    const detail = page.locator(nameOf('detailPage'));
    await expect(detail).toHaveAttribute('data-message-loaded', 'true');
    expect(probeHits(await page.content()), `detail screen of ${messageId}`).toEqual([]);
  }
  await page.goto(`${bsEnv('consoleUrl')}/${nameOf('failuresRoute')}`);
  await expect(page.locator(nameOf('failuresPage'))).toHaveAttribute('data-failures-loaded', 'true');
  await expect(page.locator(fill(nameOf('failureRow'), { trackingId: ids.failure }))).toBeVisible();
  expect(probeHits(await page.content()), 'failures screen').toEqual([]);

  expect(answers.length, 'the screens loaded their data through the API').toBeGreaterThan(0);
  expect(leakingAnswers(answers), 'API answers carrying the probe').toEqual([]);
});
