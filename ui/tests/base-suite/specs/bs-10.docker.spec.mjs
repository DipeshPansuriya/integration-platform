// T131 (row 323, F21 BS-C) BS-10 - THE SHOWCASE: the trace + failed-then-fixed journey (WD8-WD12).
// backlog.md:3472-3494 (replay Gherkin, narrowed to one dead letter replayed in the same run).
//
//   tenant A's stub on the exception host answers 403 (PERMANENT, ApiTargetCallTest.java:155)
//   -> post -> the delivery dead-letters after ONE send that really reached the stub
//   -> the failures screen lists it: failing stage DELIVER, error class PERMANENT, the TARGET's 403 as
//      last error. A destination refusal (REFUSED_ADDRESS / NOT_ON_ALLOW_LIST) is PERMANENT too
//      (ApiTargetCallTest.java:66) and looks the same on screen - it FAILS this scenario: it means the
//      dev-only exception is not working (lib/failure-cause.mjs)
//   -> the detail marks the failing stage
//   -> tenant B replaying A's delivery gets 404 and NOTHING changes (asserted while it is still dead-lettered)
//   -> the stub is restored to 200 -> press Replay
//   -> DEAD_LETTERED -> DUE -> DELIVERED with the pinned versions; attempt history kept; ONE audit event
//      (actor, tenant, delivery id, pinned versions); reopen: delivered; dashboard failed -1 / converted +1
//
// Status lives in the gate (waitsOn / lib/pending-names.mjs) and the run report _reports/2026-10-22-base-suite-run.md, not in this header.
// Never run against production; never a psql-seeded row (unit/direct-sql.test.mjs scans this file).

import { expect, test } from '@playwright/test';
import { FIGURES, delta } from '../lib/counts.mjs';
import { attemptRowsSql, auditRowsSql, deliveryOfMessageSql, deliveryRowsSql, eventTypesSql, operatorIdsSql, tenantIdSql } from '../lib/direct-sql.mjs';
import { causeOf, deliverFailureProblems } from '../lib/failure-cause.mjs';
import { fill, nameOf, waitsOn } from '../lib/pending-names.mjs';
import { attemptHistoryProblems, auditProblems, changedKeys, pinProblems, replayWalkProblems, stateWalk } from '../lib/replay.mjs';
import { FLOW, SOURCE_CONTRACT, TENANTS, expectedTargetBody, orderRecord } from '../lib/tenants.mjs';
import { deliveriesTo } from '../lib/wiremock.mjs';
import { eventually, inBrowser, journal, must, postRecord, setStub, sourceToken } from '../support/api.mjs';
import { bsEnv } from '../support/boot-state.mjs';
import { readOne, readRows } from '../support/db.mjs';
import { addTracking, readRun } from '../support/run-file.mjs';
import { intakeCode, rotatedSecret } from '../support/seed.mjs';

// BS-10 needs features not on main yet: ONE flag per T-item (ON_MAIN in pending-names.mjs). While one is
// missing the file is test.fixme - reported as fixme with the item named, never a silent pass.
const WAITS = waitsOn('T105', 'T111', 'T112');
test.fixme(WAITS !== null, WAITS ?? '');

test.describe.configure({ mode: 'serial' });

const PIPELINE_MS = 90000;
const A = TENANTS.A;
const QUEUE = '[data-page="queue-dashboard"]';

let stubAt403 = false;
test.afterAll(async () => {
  if (stubAt403) await setStub(A.stubPath, 200); // never leave tenant A's target refusing for BS-12
});

const code = () => bsEnv('tenantA');
const deliveryOf = (messageId) => {
  const rows = readRows(deliveryOfMessageSql(code(), messageId));
  if (rows.length !== 1) return null; // single target: exactly one delivery per message
  const [id, state, mappingVersionId, contractVersionId] = rows[0];
  return { id, state, pins: { mappingVersionId, contractVersionId } };
};
const attemptsOf = (deliveryId) => readRows(attemptRowsSql(code(), deliveryId))
  .map(([id, layer, outcomeClass, startedAt, finishedAt, detailHash]) => ({ id, layer, outcomeClass, startedAt, finishedAt, detailHash }));
const eventsOf = (deliveryId) => readRows(eventTypesSql(code(), deliveryId)).map(([type]) => type);
const auditsOf = () => readRows(auditRowsSql(code())).map(([id, tenantId, actor, action, text]) => ({ id, tenantId, actor, action, text }));
const sendsOf = async (deliveryId) => deliveriesTo(await journal(), A.stubPath).filter((d) => d.deliveryId === deliveryId);

/** Everything a refused replay must leave as it was. */
const snapshot = async (messageId, deliveryId) => ({
  state: deliveryOf(messageId)?.state, pins: deliveryOf(messageId)?.pins, attempts: attemptsOf(deliveryId), events: eventsOf(deliveryId),
  audits: auditsOf().map((a) => a.id), sends: (await sendsOf(deliveryId)).length
});

/** Tenant A's dashboard figures for one flow and UTC day (QueueDashboard.tsx:41, :58-60 on main). */
async function dashboardFigures(page, flowId, day) {
  await page.goto(`${bsEnv('consoleUrl')}/`);
  await page.click('[data-nav="queue"]');
  await expect(page.locator(`${QUEUE}[data-queue-loaded="true"]`)).toBeVisible();
  const row = page.locator(`[data-queue-row="${flowId}"][data-day="${day}"]`);
  await expect(row, `dashboard row for flow ${flowId} on ${day}`).toBeVisible();
  return Object.fromEntries(await Promise.all(FIGURES.map(async (f) => [f, Number(await row.getAttribute(`data-count-${f}`))])));
}

test('BS-10: a 403 dead letter on the failures screen, replayed after the target is fixed - delivered, audited, counted', async ({ page }) => {
  test.setTimeout(6 * 60 * 1000); // two real pipeline passes plus the screens

  // fail closed BEFORE the stub is touched or anything is posted
  const [replayMethod, replayPath] = nameOf('replayApi').split(' ');
  const replayAction = nameOf('replayAction');
  const auditAction = nameOf('auditReplayAction');
  const operators = readRows(operatorIdsSql(code())); // the replay is audited under the bound user's id
  expect(operators, "exactly one TENANT_ADMIN app_user in tenant A: the replaying operator").toHaveLength(1);
  const auditActor = operators[0][0];
  const { flowId } = readRun().seed.A;
  const record = orderRecord(A, 10);
  let messageId;
  let delivery;
  let day;
  let failedFigures;

  await test.step("tenant A's stub on the exception host answers 403", async () => {
    await setStub(A.stubPath, 403);
    stubAt403 = true;
  });

  await test.step('post: the delivery dead-letters after ONE send that reached the stub', async () => {
    await page.goto(`${bsEnv('consoleUrl')}/`);
    const { clientId, secretValue } = await rotatedSecret(page, A, readRun().seed.A.credentialId);
    const answer = await postRecord(await sourceToken(clientId, secretValue), intakeCode(A), SOURCE_CONTRACT.versionLabel, record);
    expect(answer.status, JSON.stringify(answer.data)).toBe(202);
    messageId = answer.data.trackingId;
    addTracking('A', [messageId]);
    // TBD(T106): the relay + consumers that route, transform and send an accepted message
    delivery = await eventually(() => {
      const d = deliveryOf(messageId);
      return d?.state === 'DEAD_LETTERED' ? d : null;
    }, `the delivery of ${messageId} dead-lettered`, { timeoutMs: PIPELINE_MS });
    const sends = await sendsOf(delivery.id);
    expect(sends, 'exactly ONE send reached the stub (PERMANENT never redelivers) - 0 means the guard refused it').toHaveLength(1);
    expect(sends[0].body, 'the contract-translated body').toEqual(expectedTargetBody(record));
    day = readRows(deliveryRowsSql(code())).find(([m]) => m === messageId)[3];
    failedFigures = await dashboardFigures(page, flowId, day);
  });

  await test.step("the failures screen lists it: DELIVER, PERMANENT, the target's 403 - not a destination refusal", async () => {
    const failures = must(await inBrowser(page, 'GET', `${nameOf('failuresApi')}?flowId=${flowId}&date=${day}`, undefined, A.admin), 'failures read');
    const row = failures.find((f) => f.trackingId === messageId);
    expect(deliverFailureProblems(row, { trackingId: messageId, deliveryId: delivery.id })).toEqual([]);

    await page.goto(`${bsEnv('consoleUrl')}/${nameOf('failuresRoute')}`);
    await expect(page.locator(nameOf('failuresPage'))).toHaveAttribute('data-failures-loaded', 'true');
    const shown = page.locator(fill(nameOf('failureRow'), { trackingId: messageId }));
    await expect(shown).toHaveAttribute('data-failing-stage', 'DELIVER');
    await expect(shown).toHaveAttribute('data-origin', 'TARGET');
    await expect(shown.locator(nameOf('failureErrorClass'))).toHaveText('PERMANENT');
    expect(causeOf(await shown.locator(nameOf('failureLastError')).textContent()), "the screen's last error is the target's 403").toEqual({ kind: 'TARGET_HTTP', status: 403 });
  });

  await test.step('open the message: the detail marks DELIVER as the failing stage', async () => {
    await page.locator(fill(nameOf('failureDetailLink'), { trackingId: messageId })).click();
    const detail = page.locator(nameOf('detailPage'));
    await expect(detail).toHaveAttribute('data-message-loaded', 'true');
    await expect(detail.locator(nameOf('detailFailingStage'))).toHaveAttribute('data-failing-stage', 'DELIVER');
    await expect(detail.locator(`${nameOf('detailStageRow')}[data-stage-row="DELIVER"]`)).toHaveAttribute('data-stage-status', 'failed');
  });

  await test.step("tenant B replaying tenant A's delivery gets 404 and nothing changes", async () => {
    const before = await snapshot(messageId, delivery.id);
    const forged = await inBrowser(page, replayMethod, fill(replayPath, { deliveryId: delivery.id }), undefined, TENANTS.B.admin);
    expect(forged.status, JSON.stringify(forged.data)).toBe(404);
    expect(changedKeys(before, await snapshot(messageId, delivery.id)), 'state, pins, attempts, events, audit events and sends unchanged').toEqual([]);
  });

  const attemptsBefore = attemptsOf(delivery.id);
  const eventsBefore = eventsOf(delivery.id);
  const auditIdsBefore = new Set(auditsOf().map((a) => a.id));
  expect(attemptsBefore.some((a) => a.outcomeClass === 'PERMANENT'), 'the 403 attempt is in the history').toBe(true);

  await test.step('restore the stub to 200, press Replay: DEAD_LETTERED -> DUE -> DELIVERED', async () => {
    await setStub(A.stubPath, 200);
    stubAt403 = false;
    await page.goto(`${bsEnv('consoleUrl')}/${nameOf('failuresRoute')}`);
    await expect(page.locator(nameOf('failuresPage'))).toHaveAttribute('data-failures-loaded', 'true');
    await page.locator(`${fill(nameOf('failureRow'), { trackingId: messageId })} ${replayAction}`).click();

    await eventually(() => eventsOf(delivery.id).slice(eventsBefore.length).includes('REPLAY'), 'the REPLAY event', { timeoutMs: 15000 });
    const samples = [];
    await eventually(() => {
      const state = deliveryOf(messageId)?.state;
      samples.push(state);
      return state === 'DELIVERED' || state === 'DEAD_LETTERED';
    }, 'the replayed delivery settles', { timeoutMs: PIPELINE_MS, everyMs: 100 });
    expect(replayWalkProblems({ before: delivery.state, walk: stateWalk(samples), events: eventsOf(delivery.id).slice(eventsBefore.length) })).toEqual([]);

    const sends = await sendsOf(delivery.id);
    expect(sends, 'the 403 send and ONE replayed send, same delivery id').toHaveLength(2);
    expect(sends[1].body, 'sent with the versions it arrived with: the same translation').toEqual(expectedTargetBody(record));
  });

  await test.step('pinned versions, attempt history kept, one audit event', async () => {
    const after = deliveryOf(messageId);
    expect(pinProblems(delivery.pins, after.pins)).toEqual([]);
    expect(attemptHistoryProblems(attemptsBefore, attemptsOf(delivery.id))).toEqual([]);
    const newAudits = auditsOf().filter((a) => !auditIdsBefore.has(a.id));
    expect(auditProblems(newAudits, {
      tenantId: readOne(tenantIdSql(code())), actor: auditActor, action: auditAction, deliveryId: delivery.id, pins: delivery.pins
    })).toEqual([]);
  });

  await test.step('reopen: delivered, gone from the failures screen; dashboard failed -1 / converted +1', async () => {
    await page.goto(`${bsEnv('consoleUrl')}/${fill(nameOf('detailRoute'), { trackingId: messageId })}`);
    const detail = page.locator(nameOf('detailPage'));
    await expect(detail).toHaveAttribute('data-message-loaded', 'true');
    await expect(detail.locator(nameOf('detailFailingStage'))).toHaveCount(0);
    await expect(detail.locator(`${nameOf('detailStageRow')}[data-stage-row="DELIVER"]`)).toHaveAttribute('data-stage-status', 'done');

    await page.goto(`${bsEnv('consoleUrl')}/${nameOf('failuresRoute')}`);
    await expect(page.locator(nameOf('failuresPage'))).toHaveAttribute('data-failures-loaded', 'true');
    await expect(page.locator(fill(nameOf('failureRow'), { trackingId: messageId }))).toHaveCount(0);

    const moved = delta(failedFigures, await dashboardFigures(page, flowId, day));
    expect(moved, 'the same delivery moved from failed to converted').toEqual({ queued: 0, processed: 0, converted: 1, failed: -1 });
  });
});
