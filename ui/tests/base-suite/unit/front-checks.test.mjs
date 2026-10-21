// lane qa-release (2026-10-22): the pure oracles the front-half specs use.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { TENANTS, orderRecord } from '../lib/tenants.mjs';
import { exactBytesBody, foreignIdHits, intakeStageProblems, uuidShaped } from '../lib/front-checks.mjs';

const TID = 'f0000000-0000-4000-8000-00000000000d';
const FLOW = 'a0000000-0000-4000-8000-00000000000a';
const intake = { stage: 'INTAKE', startedAt: '2026-10-22T00:00:00.000Z', finishedAt: '2026-10-22T00:00:00.040Z', durationMs: 40, sincePreviousMs: null };
const base = { trackingId: TID, flowId: FLOW, stages: [intake], totalMs: 40 };
const problems = (over) => intakeStageProblems({ ...base, ...over }, TID, FLOW).join(' | ');

test('intakeStageProblems: a timed INTAKE stage on the right message and flow is clean', () => {
  assert.deepEqual(intakeStageProblems(base, TID, FLOW), []);
  // later stages being present is fine: the oracle only demands INTAKE
  assert.deepEqual(intakeStageProblems({ ...base, stages: [intake, { ...intake, stage: 'ROUTE' }], totalMs: 80 }, TID, FLOW), []);
});

test('intakeStageProblems names each defect', () => {
  assert.deepEqual(intakeStageProblems(null, TID, FLOW), ['no timeline answer']);
  assert.match(problems({ trackingId: 'x' }), /tracking id x/);
  assert.match(problems({ flowId: 'y' }), /flow y/);
  assert.match(problems({ stages: [] }), /INTAKE is missing/);
  assert.match(problems({ stages: [{ ...intake, durationMs: -1 }] }), /duration -1/);
  assert.match(problems({ stages: [{ ...intake, finishedAt: null }] }), /has not finished/);
  assert.match(problems({ stages: [{ ...intake, startedAt: 'no' }] }), /no start time/);
  assert.match(problems({ stages: [{ ...intake, finishedAt: '2026-10-21T00:00:00Z' }] }), /finishes before it starts/);
});

test('exactBytesBody: a valid order record whose JSON is exactly N bytes', () => {
  for (const n of [4096, 1_048_576, 1_048_577]) {
    const body = exactBytesBody(orderRecord(TENANTS.A, 1), n);
    assert.equal(Buffer.byteLength(body, 'utf8'), n);
    const parsed = JSON.parse(body);
    assert.equal(parsed.customerId, 'A-CUST-0001');
    assert.equal(typeof parsed.amount, 'number');
    assert.ok(parsed.orderId.startsWith('A-ORD-0001'));
  }
  assert.throws(() => exactBytesBody(orderRecord(TENANTS.A, 1), 10), /smaller than/);
});

test("foreignIdHits: the other tenant's markers and ids in an answer", () => {
  const text = JSON.stringify({ a: 'B-ORD-0002', b: FLOW, c: 'A-ORD-0001' });
  assert.deepEqual(foreignIdHits(text, TENANTS.A, [FLOW, TID]), ['B-ORD-0002', FLOW]);
  assert.deepEqual(foreignIdHits('{}', TENANTS.A, [FLOW]), []);
  assert.deepEqual(foreignIdHits(null, TENANTS.B, [FLOW]), []);
});

test('uuidShaped', () => {
  assert.equal(uuidShaped(TID), true);
  assert.equal(uuidShaped('nope'), false);
  assert.equal(uuidShaped(undefined), false);
});
