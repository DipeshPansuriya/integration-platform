// T112 (E13 base N3, lane D - vijay-developer): the message detail screen's pure view model.
// The timeline answer (GET /api/v1/admin/messages/{trackingId}/timeline, T110) carries only the
// RECORDED stages; the screen shows the whole pipeline (INTAKE, ROUTE, TRANSFORM, DELIVER), marks the
// failing stage and shows every stage after it as "Not reached" - never as a zero duration (the
// backlog negative scenario). No DOM, no fetch - unit-tested under node.
//
// Round 2 (row 328 M1): the failing stage is the SERVER's `failingStage` field only. The ?failed= hash
// parameter is still accepted (the URLs stay unchanged, row 329) but parsed and IGNORED for status -
// nothing the address bar carries can mark a stage "Failed".
// Round 2 (row 329 M3): spec labels (Intake (source intake), Transform (conversion), Deliver (all
// attempts)); a stage with no timing row is "Not recorded" - "Not reached" only after the server's
// failing stage; the target response per delivery is the last attempt's time and outcome.
// Run: node --import ./tests/rules/ts-resolve.mjs --test tests/ui/message-timeline.test.mjs

import test from 'node:test';
import assert from 'node:assert/strict';

const {
  PIPELINE,
  NOT_FOUND_TEXT,
  isTrackingId,
  parseMessageRoute,
  messageHash,
  targetResponses,
  failingStageOf,
  timelineRows,
  totalText,
  formatMs,
  utcText,
  stageLabel,
  detailStateOf
} = await import('../../src/pages/message-timeline.ts');

const ID = '3f2b8a52-6c1d-4e0f-9a7b-1c2d3e4f5a6b';
const T1 = '00000000-0000-4000-8000-0000000000a1';
const T2 = '00000000-0000-4000-8000-0000000000b2';

const at = (ms) => new Date(Date.UTC(2026, 9, 17, 12, 0, 0) + ms).toISOString();

const deliverStages = [
  { stage: 'INTAKE', startedAt: at(0), finishedAt: at(40), durationMs: 40, sincePreviousMs: null },
  { stage: 'ROUTE', startedAt: at(50), finishedAt: at(80), durationMs: 30, sincePreviousMs: 10 },
  { stage: 'TRANSFORM', startedAt: at(100), finishedAt: at(1334), durationMs: 1234, sincePreviousMs: 20 },
  { stage: 'DELIVER', startedAt: at(1400), finishedAt: null, durationMs: null, sincePreviousMs: 66 }
];

/** A dead-lettered message: DELIVER started and never finished; the server says DELIVER failed. */
const deliverFailed = {
  trackingId: ID,
  flowId: 'f-1',
  state: 'ROUTED',
  acceptedAt: at(0),
  stages: deliverStages,
  totalMs: 1400,
  failingStage: 'DELIVER',
  deliveries: [{ deliveryId: 'd-1', targetId: T1, durationMs: 412, outcome: 'PERMANENT' }]
};

/** The same stages still in flight: the server names no failing stage. */
const inFlight = { ...deliverFailed, failingStage: null };

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
  totalMs: 90,
  failingStage: 'ROUTE',
  deliveries: []
};

test('the pipeline is the backend order INTAKE, ROUTE, TRANSFORM, DELIVER', () => {
  assert.deepEqual([...PIPELINE], ['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER']);
});

test('stage labels carry the spec words (row 329); the codes stay the row keys', () => {
  assert.equal(stageLabel('INTAKE'), 'Intake (source intake)');
  assert.equal(stageLabel('ROUTE'), 'Route');
  assert.equal(stageLabel('TRANSFORM'), 'Transform (conversion)');
  assert.equal(stageLabel('DELIVER'), 'Deliver (all attempts)');
  assert.equal(stageLabel('SOMETHING_NEW'), 'SOMETHING_NEW', 'an unknown stage shows as named');
  assert.deepEqual(timelineRows(deliverFailed).map((r) => r.stage), ['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER']);
});

test('the failing stage is the server field only', () => {
  assert.equal(failingStageOf(deliverFailed), 'DELIVER');
  assert.equal(failingStageOf(routeFailed), 'ROUTE');
  assert.equal(failingStageOf(inFlight), null);
  assert.equal(failingStageOf({ ...routeFailed, failingStage: null }), null, 'the state alone does not mark a failure');
  assert.equal(failingStageOf({ ...inFlight, failingStage: undefined }), null, 'a missing field is no failure');
  assert.equal(failingStageOf({ ...inFlight, failingStage: 'BOGUS' }), null, 'only a pipeline stage can fail');
});

test('@negative a forged ?failed=DELIVER on an in-flight message marks nothing Failed', () => {
  const { failedHint } = parseMessageRoute(`${ID}?failed=DELIVER`);
  assert.equal(failedHint, 'DELIVER', 'the URL parameter is still accepted (URLs unchanged)');
  for (const hint of [failedHint, 'TRANSFORM', 'ROUTE']) {
    const rows = timelineRows(inFlight, hint); // an extra argument is ignored - status has no hint input
    assert.deepEqual(rows.map((r) => r.status), ['done', 'done', 'done', 'running'], `hint ${hint}`);
    assert.ok(!rows.some((r) => r.status === 'failed'), `hint ${hint}`);
    assert.equal(failingStageOf(inFlight, hint), null, `hint ${hint}`);
  }
  // and a hint never moves the server's failing stage either
  assert.equal(failingStageOf(routeFailed, 'DELIVER'), 'ROUTE');
  assert.deepEqual(
    timelineRows(routeFailed, 'DELIVER').map((r) => r.status),
    ['done', 'failed', 'not-reached', 'not-reached']
  );
});

test('the detail route keeps its URL shape: #/messages/<trackingId>[?failed=<STAGE>] parses and round-trips', () => {
  assert.deepEqual(parseMessageRoute(ID), { trackingId: ID, failedHint: null });
  assert.deepEqual(parseMessageRoute(`${ID}?failed=DELIVER`), { trackingId: ID, failedHint: 'DELIVER' });
  assert.equal(messageHash(ID, 'DELIVER'), `#/messages/${ID}?failed=DELIVER`);
  assert.equal(messageHash(ID, null), `#/messages/${ID}`);
});

test('server DELIVER marks DELIVER failed with nothing after it - a dead letter never reads "Running"', () => {
  const rows = timelineRows(deliverFailed);
  assert.deepEqual(rows.map((r) => r.status), ['done', 'done', 'done', 'failed']);
  assert.equal(rows.at(-1).stage, 'DELIVER', 'no row follows the failed DELIVER');
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
  const rows = timelineRows(routeFailed);
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
  const rows = timelineRows(noRouteTiming);
  assert.equal(rows[1].status, 'failed');
  assert.equal(rows[1].durationText, 'Not recorded');
  assert.equal(rows[1].startedAt, null);
  assert.deepEqual(rows.slice(2).map((r) => r.status), ['not-reached', 'not-reached']);
});

test('a stage still running (no failure) shows "Running"; unrecorded later stages are "Not recorded", not "Not reached"', () => {
  const running = {
    ...routeFailed,
    state: 'ROUTED',
    failingStage: null,
    stages: [routeFailed.stages[0], { stage: 'ROUTE', startedAt: at(50), finishedAt: null, durationMs: null, sincePreviousMs: 10 }],
    totalMs: 50
  };
  const rows = timelineRows(running);
  assert.deepEqual(rows.map((r) => r.status), ['done', 'running', 'not-recorded', 'not-recorded']);
  assert.equal(rows[1].durationText, 'Running');
  assert.equal(rows[1].finishedText, 'Running');
  assert.equal(rows[2].durationText, 'Not recorded');
});

test('only INTAKE timed (main today): every other stage reads "Not recorded" while nothing failed', () => {
  const intakeOnly = { ...inFlight, stages: deliverStages.slice(0, 1), totalMs: 40, deliveries: [] };
  const rows = timelineRows(intakeOnly);
  assert.deepEqual(rows.map((r) => r.status), ['done', 'not-recorded', 'not-recorded', 'not-recorded']);
  assert.deepEqual(rows.slice(1).map((r) => r.durationText), ['Not recorded', 'Not recorded', 'Not recorded']);
});

test('an unrecorded stage BEFORE the failing one is "Not recorded" (it was passed, never timed)', () => {
  const skippedTransform = { ...deliverFailed, stages: deliverStages.filter((s) => s.stage !== 'TRANSFORM') };
  const rows = timelineRows(skippedTransform);
  assert.equal(rows[2].stage, 'TRANSFORM');
  assert.equal(rows[2].status, 'not-recorded');
  assert.equal(rows[2].durationText, 'Not recorded');
});

test('a recorded zero duration is real and shown as 0 ms', () => {
  const instant = {
    ...routeFailed,
    state: 'ROUTED',
    failingStage: null,
    stages: [{ stage: 'INTAKE', startedAt: at(0), finishedAt: at(0), durationMs: 0, sincePreviousMs: null }],
    totalMs: 0
  };
  assert.equal(timelineRows(instant)[0].durationText, '0 ms');
  assert.equal(totalText(0), '0 ms');
});

test('target response: one delivery is one "Target response" line, last attempt duration with its outcome', () => {
  assert.deepEqual(targetResponses(deliverFailed), [
    { key: 'd-1', label: 'Target response', text: '412 ms (Permanent)', durationMs: 412 }
  ]);
  const ok = { ...inFlight, deliveries: [{ deliveryId: 'd-9', targetId: T1, durationMs: 412, outcome: 'SUCCESS' }] };
  assert.equal(targetResponses(ok)[0].text, '412 ms (Success)');
  const timedOut = { ...inFlight, deliveries: [{ deliveryId: 'd-9', targetId: T1, durationMs: 30000, outcome: 'TRANSIENT' }] };
  assert.equal(targetResponses(timedOut)[0].text, '30.000 s (Transient)');
});

test('target response: two or more deliveries are one line per target', () => {
  const fanOut = {
    ...inFlight,
    deliveries: [
      { deliveryId: 'd-1', targetId: T1, durationMs: 80, outcome: 'SUCCESS' },
      { deliveryId: 'd-2', targetId: T2, durationMs: null, outcome: null }
    ]
  };
  assert.deepEqual(
    targetResponses(fanOut).map((r) => [r.label, r.text]),
    [
      [`Target response - ${T1}`, '80 ms (Success)'],
      [`Target response - ${T2}`, 'Not reached']
    ]
  );
});

test('@negative target response with no attempt (or no delivery) is "Not reached", never 0 ms', () => {
  const noAttempt = { ...inFlight, deliveries: [{ deliveryId: 'd-1', targetId: T1, durationMs: null, outcome: null }] };
  assert.deepEqual(targetResponses(noAttempt), [
    { key: 'd-1', label: 'Target response', text: 'Not reached', durationMs: null }
  ]);
  assert.deepEqual(targetResponses(routeFailed), [
    { key: 'none', label: 'Target response', text: 'Not reached', durationMs: null }
  ]);
  assert.deepEqual(targetResponses({ ...routeFailed, deliveries: undefined }).map((r) => r.text), ['Not reached']);
  const realZero = { ...inFlight, deliveries: [{ deliveryId: 'd-1', targetId: T1, durationMs: 0, outcome: 'SUCCESS' }] };
  assert.equal(targetResponses(realZero)[0].text, '0 ms (Success)', 'a measured 0 ms is real');
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
