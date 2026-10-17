// T112 (E13 base N3, lane D - vijay-developer): the message detail screen's pure view model.
// The timeline answer (GET /api/v1/admin/messages/{trackingId}/timeline, T110) carries only the
// RECORDED stages; the screen shows the whole pipeline (INTAKE, ROUTE, TRANSFORM, DELIVER), marks the
// failing stage and shows every stage after it as "Not reached" - never as a zero duration (the
// backlog negative scenario). No DOM, no fetch - unit-tested under node.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/message-timeline.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';

const {
  PIPELINE,
  NOT_FOUND_TEXT,
  isTrackingId,
  parseMessageRoute,
  messageHash,
  failingStageOf,
  timelineRows,
  totalText,
  formatMs,
  utcText,
  stageLabel,
  detailStateOf
} = await import('../../src/pages/message-timeline.ts');

const ID = '3f2b8a52-6c1d-4e0f-9a7b-1c2d3e4f5a6b';

const at = (ms) => new Date(Date.UTC(2026, 9, 17, 12, 0, 0) + ms).toISOString();

/** A message whose DELIVER stage started and never finished (dead-lettered). */
const deliverFailed = {
  trackingId: ID,
  flowId: 'f-1',
  state: 'ROUTED',
  acceptedAt: at(0),
  stages: [
    { stage: 'INTAKE', startedAt: at(0), finishedAt: at(40), durationMs: 40, sincePreviousMs: null },
    { stage: 'ROUTE', startedAt: at(50), finishedAt: at(80), durationMs: 30, sincePreviousMs: 10 },
    { stage: 'TRANSFORM', startedAt: at(100), finishedAt: at(1334), durationMs: 1234, sincePreviousMs: 20 },
    { stage: 'DELIVER', startedAt: at(1400), finishedAt: null, durationMs: null, sincePreviousMs: 66 }
  ],
  totalMs: 1400
};

/** A message refused by routing: INTAKE then ROUTE (finished), nothing after. */
const routeFailed = {
  trackingId: ID,
  flowId: 'f-1',
  state: 'ROUTE_FAILED',
  acceptedAt: at(0),
  stages: [
    { stage: 'INTAKE', startedAt: at(0), finishedAt: at(40), durationMs: 40, sincePreviousMs: null },
    { stage: 'ROUTE', startedAt: at(50), finishedAt: at(90), durationMs: 40, sincePreviousMs: 10 }
  ],
  totalMs: 90
};

test('the pipeline is the backend order INTAKE, ROUTE, TRANSFORM, DELIVER', () => {
  assert.deepEqual([...PIPELINE], ['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER']);
  assert.equal(stageLabel('TRANSFORM'), 'Transform');
  assert.equal(stageLabel('SOMETHING_NEW'), 'SOMETHING_NEW', 'an unknown stage shows as named');
});

test('the failing stage: ROUTE_FAILED is ROUTE; a ROUTED message takes the Failures link hint (TRANSFORM/DELIVER only)', () => {
  assert.equal(failingStageOf('ROUTE_FAILED', null), 'ROUTE');
  assert.equal(failingStageOf('ROUTE_FAILED', 'DELIVER'), 'ROUTE', 'the message state wins over the hint');
  assert.equal(failingStageOf('ROUTED', 'DELIVER'), 'DELIVER');
  assert.equal(failingStageOf('ROUTED', 'TRANSFORM'), 'TRANSFORM');
  assert.equal(failingStageOf('ROUTED', null), null, 'no hint, no failure claimed');
  assert.equal(failingStageOf('ROUTED', 'INTAKE'), null, 'a routed message cannot have failed before routing');
  assert.equal(failingStageOf('ROUTED', 'bogus'), null);
  assert.equal(failingStageOf('ACCEPTED', 'DELIVER'), null, 'an unrouted message never reached delivery');
});

test('every pipeline stage shows its times, duration and gap; the failing stage is marked', () => {
  const rows = timelineRows(deliverFailed, 'DELIVER');
  assert.deepEqual(rows.map((r) => r.stage), ['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER']);
  assert.deepEqual(rows.map((r) => r.status), ['done', 'done', 'done', 'failed']);
  assert.equal(rows[0].durationText, '40 ms');
  assert.equal(rows[0].sinceText, 'First stage');
  assert.equal(rows[1].sinceText, '10 ms');
  assert.equal(rows[2].durationText, '1.234 s');
  assert.equal(rows[2].startedAt, at(100));
  assert.equal(rows[2].startedText, utcText(at(100)));
  assert.equal(rows[3].durationText, 'Did not finish', 'a failed stage without an end is not a zero duration');
  assert.equal(rows[3].finishedText, 'Did not finish');
  assert.equal(rows[3].sinceText, '66 ms');
  assert.equal(rows[3].durationMs, null);
});

test('@negative stages after the failing stage are "Not reached" - never a zero duration', () => {
  const rows = timelineRows(routeFailed, 'ROUTE');
  assert.deepEqual(rows.map((r) => r.status), ['done', 'failed', 'not-reached', 'not-reached']);
  for (const row of rows.slice(2)) {
    assert.equal(row.durationText, 'Not reached');
    assert.equal(row.sinceText, 'Not reached');
    assert.equal(row.startedText, 'Not reached');
    assert.equal(row.finishedText, 'Not reached');
    assert.equal(row.durationMs, null);
    assert.equal(row.startedAt, null);
    assert.ok(!/\b0\s*(ms|s)\b/.test(row.durationText), 'no zero duration text');
  }
  assert.equal(rows[1].durationText, '40 ms', 'the failing stage keeps its own recorded duration');
});

test('a failing stage the backend never timed is marked failed with "Not recorded", not "Not reached"', () => {
  const noRouteTiming = { ...routeFailed, stages: routeFailed.stages.slice(0, 1), totalMs: 40 };
  const rows = timelineRows(noRouteTiming, 'ROUTE');
  assert.equal(rows[1].status, 'failed');
  assert.equal(rows[1].durationText, 'Not recorded');
  assert.equal(rows[1].startedAt, null);
  assert.deepEqual(rows.slice(2).map((r) => r.status), ['not-reached', 'not-reached']);
});

test('a stage still running (no failure) shows "Running"; unrecorded later stages are "Not reached"', () => {
  const running = {
    ...routeFailed,
    state: 'ROUTED',
    stages: [routeFailed.stages[0], { stage: 'ROUTE', startedAt: at(50), finishedAt: null, durationMs: null, sincePreviousMs: 10 }],
    totalMs: 50
  };
  const rows = timelineRows(running, null);
  assert.deepEqual(rows.map((r) => r.status), ['done', 'running', 'not-reached', 'not-reached']);
  assert.equal(rows[1].durationText, 'Running');
  assert.equal(rows[1].finishedText, 'Running');
});

test('an unrecorded stage BEFORE the failing one is "Not recorded" (it was passed, never timed)', () => {
  const skippedTransform = { ...deliverFailed, stages: deliverFailed.stages.filter((s) => s.stage !== 'TRANSFORM') };
  const rows = timelineRows(skippedTransform, 'DELIVER');
  assert.equal(rows[2].stage, 'TRANSFORM');
  assert.equal(rows[2].status, 'not-recorded');
  assert.equal(rows[2].durationText, 'Not recorded');
});

test('a recorded zero duration is real and shown as 0 ms', () => {
  const instant = { ...routeFailed, state: 'ROUTED', stages: [{ stage: 'INTAKE', startedAt: at(0), finishedAt: at(0), durationMs: 0, sincePreviousMs: null }], totalMs: 0 };
  assert.equal(timelineRows(instant, null)[0].durationText, '0 ms');
  assert.equal(totalText(0), '0 ms');
});

test('total and durations format as ms / s / min; a missing total is worded', () => {
  assert.equal(totalText(1400), '1.400 s');
  assert.equal(totalText(null), 'Not available');
  assert.equal(formatMs(999), '999 ms');
  assert.equal(formatMs(61500), '1 min 1.500 s');
});

test('UTC timestamps read as date, time and "UTC"', () => {
  assert.equal(utcText('2026-10-17T12:00:01.250Z'), '2026-10-17 12:00:01.250 UTC');
  assert.equal(utcText('2026-10-17T12:00:01Z'), '2026-10-17 12:00:01 UTC');
});

test('the detail route: #/messages/<trackingId>[?failed=<STAGE>] parses and round-trips', () => {
  assert.deepEqual(parseMessageRoute(ID), { trackingId: ID, failedHint: null });
  assert.deepEqual(parseMessageRoute(`${ID}?failed=DELIVER`), { trackingId: ID, failedHint: 'DELIVER' });
  assert.equal(messageHash(ID, 'DELIVER'), `#/messages/${ID}?failed=DELIVER`);
  assert.equal(messageHash(ID, null), `#/messages/${ID}`);
  assert.deepEqual(parseMessageRoute(messageHash(ID, 'ROUTE').replace('#/messages/', '')), { trackingId: ID, failedHint: 'ROUTE' });
});

test('@negative a tracking id must be a uuid; 404 and a malformed id read the same "no message" words', () => {
  assert.equal(isTrackingId(ID), true);
  assert.equal(isTrackingId('not-a-uuid'), false);
  assert.equal(isTrackingId(''), false);
  assert.equal(detailStateOf(404), 'not-found');
  assert.equal(detailStateOf(403), 'refused');
  assert.equal(detailStateOf(500), 'error');
  assert.equal(NOT_FOUND_TEXT, 'No message with this tracking id');
  assert.ok(!/tenant/i.test(NOT_FOUND_TEXT), 'never hints at another tenant');
});
