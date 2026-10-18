// T131 (row 323, F21 BS-C, BS-08) - the timeline oracle, FAILING FIRST. No docker: pure functions of
// tests/base-suite/lib/timeline.mjs over the T110 timeline answer shape (lane t112 ui/src/api/messages.ts:
// { trackingId, flowId, state, acceptedAt, stages: [{ stage, startedAt, finishedAt, durationMs,
// sincePreviousMs }], totalMs }).
//
// BS-08: every stage shown with time and duration >= 0, in order. The stage names asserted are the ones on
// main - INTAKE | ROUTE | TRANSFORM | DELIVER (V2_1_1110__message_stage_timing.sql:31) - not T105's
// ROUTED/TRANSFORMED/DELIVERED/FAILED (plan FLAG: T105 cannot write those without a migration).

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { STAGES, screenRowProblems, timelineProblems } from '../lib/timeline.mjs';
import { timelineOfRows } from '../lib/timeline.mjs';

const ID = '0b9f6c1e-0000-4000-8000-000000000001';
const at = (ms) => new Date(Date.UTC(2026, 9, 20, 10, 0, 0, ms)).toISOString();
const stage = (name, start, end) => ({ stage: name, startedAt: at(start), finishedAt: end === null ? null : at(end), durationMs: end === null ? null : end - start, sincePreviousMs: null });
const good = () => ({
  trackingId: ID, flowId: 'f1', state: 'ROUTED', acceptedAt: at(0), totalMs: 400,
  stages: [stage('INTAKE', 0, 10), stage('ROUTE', 20, 60), stage('TRANSFORM', 70, 90), stage('DELIVER', 100, 400)]
});

test('the landed stage set, in pipeline order (main V2_1_1110:31)', () => {
  assert.deepEqual(STAGES, ['INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER']);
});

test('a complete journey has no problem', () => {
  assert.deepEqual(timelineProblems(good(), ID), []);
});

test('a missing stage is named', () => {
  const t = good();
  t.stages = t.stages.filter((s) => s.stage !== 'TRANSFORM');
  assert.deepEqual(timelineProblems(t, ID), ['stage TRANSFORM is missing']);
});

test("T105's names are not the landed set - each one is named, never mapped", () => {
  const t = good();
  t.stages[1].stage = 'ROUTED';
  const problems = timelineProblems(t, ID);
  assert.ok(problems.includes('stage ROUTED is not in the landed set INTAKE, ROUTE, TRANSFORM, DELIVER'), problems.join('; '));
  assert.ok(problems.includes('stage ROUTE is missing'));
});

test('stages out of pipeline order fail - by list order and by start time', () => {
  const swapped = good();
  [swapped.stages[1], swapped.stages[2]] = [swapped.stages[2], swapped.stages[1]];
  assert.ok(timelineProblems(swapped, ID).some((p) => /order/.test(p)));
  const early = good();
  early.stages[2] = stage('TRANSFORM', 5, 90); // starts before ROUTE
  assert.ok(timelineProblems(early, ID).some((p) => /TRANSFORM starts before ROUTE/.test(p)));
});

test('a negative, absent or inconsistent duration fails', () => {
  const negative = good();
  negative.stages[0].durationMs = -1;
  assert.ok(timelineProblems(negative, ID).some((p) => /INTAKE duration -1/.test(p)));
  const open = good();
  open.stages[3] = stage('DELIVER', 100, null);
  assert.ok(timelineProblems(open, ID).some((p) => /DELIVER has not finished/.test(p)));
  const off = good();
  off.stages[1].durationMs = 99; // 20..60 is 40 ms
  assert.ok(timelineProblems(off, ID).some((p) => /ROUTE duration 99 ms.* 40 ms/.test(p)));
});

test('finishing before starting fails even when the duration field says 0', () => {
  const t = good();
  t.stages[1] = { ...stage('ROUTE', 60, 20), durationMs: 0 };
  assert.ok(timelineProblems(t, ID).some((p) => /ROUTE finishes before it starts/.test(p)));
});

test('a duplicated stage and a foreign tracking id fail', () => {
  const dup = good();
  dup.stages.push(stage('DELIVER', 500, 510));
  assert.ok(timelineProblems(dup, ID).some((p) => /DELIVER appears 2 times/.test(p)));
  assert.ok(timelineProblems(good(), '0b9f6c1e-0000-4000-8000-000000000002').some((p) => /tracking id/.test(p)));
});

test('a null, empty or negative total fails', () => {
  for (const totalMs of [null, -5]) assert.ok(timelineProblems({ ...good(), totalMs }, ID).some((p) => /total/.test(p)));
  assert.ok(timelineProblems(null, ID).length > 0);
});

test('the screen rows must show the API stages in the same order, all done, with the same durations', () => {
  const api = good();
  const rows = api.stages.map((s) => ({ stage: s.stage, status: 'done', durationMs: String(s.durationMs) }));
  assert.deepEqual(screenRowProblems(rows, api), []);
  assert.ok(screenRowProblems([...rows].reverse(), api).some((p) => /order/.test(p)));
  const failed = rows.map((r) => (r.stage === 'DELIVER' ? { ...r, status: 'failed' } : r));
  assert.ok(screenRowProblems(failed, api).some((p) => /DELIVER shows failed/.test(p)));
  const wrong = rows.map((r) => (r.stage === 'ROUTE' ? { ...r, durationMs: '41' } : r));
  assert.ok(screenRowProblems(wrong, api).some((p) => /ROUTE shows 41 ms, the API 40 ms/.test(p)));
});

test('timelineOfRows: the T105 rows of one message as a timeline the oracle reads; a blank finish is still running', () => {
  const id = '3c2b1a09-0000-4000-8000-0000000000d9';
  const rows = [
    ['INTAKE', '2026-10-18T10:00:00.000Z', '2026-10-18T10:00:00.010Z'],
    ['ROUTE', '2026-10-18T10:00:00.020Z', '2026-10-18T10:00:00.030Z'],
    ['TRANSFORM', '2026-10-18T10:00:00.040Z', '2026-10-18T10:00:00.050Z'],
    ['DELIVER', '2026-10-18T10:00:00.060Z', '2026-10-18T10:00:00.090Z']
  ];
  const timeline = timelineOfRows(id, rows);
  assert.equal(timeline.trackingId, id);
  assert.deepEqual(timeline.stages[3], { stage: 'DELIVER', startedAt: '2026-10-18T10:00:00.060Z', finishedAt: '2026-10-18T10:00:00.090Z' });
  assert.deepEqual(timelineProblems(timeline, id), []);
  const open = timelineOfRows(id, [...rows.slice(0, 3), ['DELIVER', '2026-10-18T10:00:00.060Z', '']]);
  assert.equal(open.stages[3].finishedAt, null);
  assert.deepEqual(timelineProblems(open, id), ['DELIVER has not finished']);
  assert.deepEqual(timelineProblems(timelineOfRows(id, rows.slice(0, 2)), id), ['stage TRANSFORM is missing', 'stage DELIVER is missing']);
  assert.throws(() => timelineOfRows(id, [['INTAKE', '2026-10-18T10:00:00.000Z']]), /three columns/);
  assert.throws(() => timelineOfRows(id, 'rows'), /rows/);
});
