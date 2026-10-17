// T131 (row 323, F21 BS-C, BS-10) - the replay oracle, FAILING FIRST. No docker: pure functions of
// tests/base-suite/lib/replay.mjs over rows the base suite reads directly (one read-only SELECT each).
//
// BS-10 / T111 criteria: replay moves DEAD_LETTERED -> DUE -> DELIVERED with the pinned versions; the full
// attempt history is kept (no attempt row changed or deleted - delivery_attempt is append-only,
// V2_3_2); one audit event (actor, tenant, delivery id, pinned versions); tenant B's replay of tenant A's
// delivery changes nothing. The delivery state and event registers are main's CHECKs (V2_3_1:28,
// V2_3_3:14-16 - REPLAY is in the event register already).

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { attemptHistoryProblems, auditProblems, changedKeys, pinProblems, replayWalkProblems, stateWalk } from '../lib/replay.mjs';

const D = '9e8d7c6b-0000-4000-8000-0000000000d1';
const M3 = '9e8d7c6b-0000-4000-8000-0000000000e3';
const V1 = '9e8d7c6b-0000-4000-8000-0000000000c1';
const T_A = '9e8d7c6b-0000-4000-8000-0000000000aa';
const attempt = (id, outcomeClass, n) => ({ id: String(id), layer: '1', outcomeClass, startedAt: `2026-10-20 10:00:0${n}+00`, finishedAt: `2026-10-20 10:00:0${n}.5+00`, detailHash: `h${id}` });

test('stateWalk drops repeats of the same polled state, keeps the order', () => {
  assert.deepEqual(stateWalk(['DEAD_LETTERED', 'DEAD_LETTERED', 'DUE', 'DUE', 'SENDING', 'DELIVERED', 'DELIVERED']), ['DEAD_LETTERED', 'DUE', 'SENDING', 'DELIVERED']);
  assert.deepEqual(stateWalk([]), []);
});

test('DEAD_LETTERED -> DUE -> DELIVERED with a REPLAY event then a DELIVER event: no problem', () => {
  assert.deepEqual(replayWalkProblems({ before: 'DEAD_LETTERED', walk: ['DUE', 'SENDING', 'DELIVERED'], events: ['REPLAY', 'CLAIM', 'DELIVER'] }), []);
  // a poll can miss the short DUE: the REPLAY event is the record of DEAD_LETTERED -> DUE
  assert.deepEqual(replayWalkProblems({ before: 'DEAD_LETTERED', walk: ['DELIVERED'], events: ['REPLAY', 'DELIVER'] }), []);
});

test('the replay must start from DEAD_LETTERED', () => {
  assert.match(replayWalkProblems({ before: 'DELIVERED', walk: ['DELIVERED'], events: ['REPLAY', 'DELIVER'] }).join(';'), /before the replay DELIVERED, not DEAD_LETTERED/);
});

test('it must end DELIVERED and never step outside DUE / SENDING / DELIVERED or backwards', () => {
  assert.match(replayWalkProblems({ before: 'DEAD_LETTERED', walk: ['DUE', 'SENDING'], events: ['REPLAY'] }).join(';'), /ends SENDING, not DELIVERED/);
  assert.match(replayWalkProblems({ before: 'DEAD_LETTERED', walk: ['DUE', 'RETRY_WAIT', 'DELIVERED'], events: ['REPLAY', 'DELIVER'] }).join(';'), /RETRY_WAIT/);
  assert.match(replayWalkProblems({ before: 'DEAD_LETTERED', walk: ['DUE', 'DEAD_LETTERED'], events: ['REPLAY', 'DEAD_LETTER'] }).join(';'), /DEAD_LETTERED/);
  assert.match(replayWalkProblems({ before: 'DEAD_LETTERED', walk: ['SENDING', 'DUE', 'DELIVERED'], events: ['REPLAY', 'DELIVER'] }).join(';'), /backwards/);
  assert.match(replayWalkProblems({ before: 'DEAD_LETTERED', walk: [], events: [] }).join(';'), /no state after the replay/);
});

test('the event log must show REPLAY first, then DELIVER, and no second dead letter', () => {
  assert.match(replayWalkProblems({ before: 'DEAD_LETTERED', walk: ['DELIVERED'], events: ['DELIVER'] }).join(';'), /first new event DELIVER, not REPLAY/);
  assert.match(replayWalkProblems({ before: 'DEAD_LETTERED', walk: ['DELIVERED'], events: ['REPLAY'] }).join(';'), /no DELIVER event after REPLAY/);
  assert.match(replayWalkProblems({ before: 'DEAD_LETTERED', walk: ['DELIVERED'], events: ['REPLAY', 'DEAD_LETTER', 'DELIVER'] }).join(';'), /DEAD_LETTER after REPLAY/);
});

test('attempt history kept: every earlier row unchanged, then new rows ending in one SUCCESS', () => {
  const before = [attempt(1, 'PERMANENT', 1)];
  assert.deepEqual(attemptHistoryProblems(before, [...before, attempt(2, 'SUCCESS', 3)]), []);
});

test('a changed, deleted or reordered earlier attempt fails', () => {
  const before = [attempt(1, 'PERMANENT', 1)];
  assert.match(attemptHistoryProblems(before, [attempt(2, 'SUCCESS', 3)]).join(';'), /attempt 1 is gone/);
  assert.match(attemptHistoryProblems(before, [{ ...before[0], detailHash: 'changed' }, attempt(2, 'SUCCESS', 3)]).join(';'), /attempt 1 changed: detailHash/);
  assert.match(attemptHistoryProblems([attempt(1, 'PERMANENT', 1), attempt(2, 'PERMANENT', 2)],
    [attempt(2, 'PERMANENT', 2), attempt(1, 'PERMANENT', 1), attempt(3, 'SUCCESS', 3)]).join(';'), /order/);
});

test('no new attempt, or a replay whose last attempt is not SUCCESS, fails; no earlier attempt fails', () => {
  const before = [attempt(1, 'PERMANENT', 1)];
  assert.match(attemptHistoryProblems(before, before).join(';'), /no new attempt/);
  assert.match(attemptHistoryProblems(before, [...before, attempt(2, 'TRANSIENT', 3)]).join(';'), /last new attempt TRANSIENT, not SUCCESS/);
  assert.match(attemptHistoryProblems([], [attempt(2, 'SUCCESS', 3)]).join(';'), /no attempt before the replay/);
});

test('pins: the delivery keeps the mapping and contract versions it arrived with, and they are set', () => {
  const pins = { mappingVersionId: M3, contractVersionId: V1 };
  assert.deepEqual(pinProblems(pins, { ...pins }), []);
  assert.match(pinProblems(pins, { ...pins, mappingVersionId: 'm4' }).join(';'), /mappingVersionId .* -> m4/);
  assert.match(pinProblems({ mappingVersionId: null, contractVersionId: V1 }, { mappingVersionId: null, contractVersionId: V1 }).join(';'), /mappingVersionId is not pinned/);
});

const audit = (extra = {}) => ({ id: '41', tenantId: T_A, actor: 'default', action: 'DELIVERY_REPLAYED', text: `["${D}"] pinned mapping ${M3} contract ${V1}`, ...extra });
const expected = { tenantId: T_A, actor: 'default', action: 'DELIVERY_REPLAYED', deliveryId: D, pins: { mappingVersionId: M3, contractVersionId: V1 } };

test('exactly one new audit event names the delivery, its actor, tenant, action and both pins', () => {
  assert.deepEqual(auditProblems([audit()], expected), []);
  assert.deepEqual(auditProblems([audit(), { ...audit(), id: '42', text: 'another delivery' }], expected), [], 'rows about other work are not counted');
});

test('none, two, or one missing a field each fail', () => {
  assert.match(auditProblems([], expected).join(';'), /0 audit events name delivery/);
  assert.match(auditProblems([audit(), audit({ id: '43' })], expected).join(';'), /2 audit events name delivery/);
  assert.match(auditProblems([audit({ actor: 'approver' })], expected).join(';'), /actor approver, not default/);
  assert.match(auditProblems([audit({ tenantId: 'other' })], expected).join(';'), /tenant other/);
  assert.match(auditProblems([audit({ action: 'X' })], expected).join(';'), /action X/);
  assert.match(auditProblems([audit({ text: `["${D}"] contract ${V1}` })], expected).join(';'), /mappingVersionId/);
});

test('changedKeys: a snapshot compare names every key whose value moved - [] is "nothing changed"', () => {
  const snap = { state: 'DEAD_LETTERED', attempts: [attempt(1, 'PERMANENT', 1)], events: ['DEAD_LETTER'], audits: 0 };
  assert.deepEqual(changedKeys(snap, structuredClone(snap)), []);
  assert.deepEqual(changedKeys(snap, { ...snap, state: 'DUE', audits: 1 }), ['state', 'audits']);
  assert.deepEqual(changedKeys(snap, { state: 'DEAD_LETTERED', attempts: snap.attempts, events: snap.events }), ['audits']);
});
