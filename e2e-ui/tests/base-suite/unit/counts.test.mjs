// T130 (row 323, F21 BS-B, BS-12) - the dashboard-count oracle, FAILING FIRST. No docker: pure
// functions of tests/base-suite/lib/counts.mjs.
//
// BS-12: per flow and UTC day, the dashboard's queued / processed / converted / failed equal a DIRECT
// count of message + delivery rows, 0 difference, and every counted row came from the real pipeline
// (no hand-seeded rows). The oracle derives the four figures exactly as the service does, measured on
// main: QueueSummaryService.java:18-23 (the closed derivation) and :46-69 (the switch, rows without a
// flow skipped at :47-49 and :59-61); day = UTC day of accepted_at / created_at
// (JdbcQueueSummaryStore.java:23-24, :43, :50).

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { countsOf, delta, deriveCounts, differences, provenanceGaps } from '../lib/counts.mjs';

const DAY = '2026-10-20';
const msg = (flowId, state, day = DAY) => ({ flowId, state, day });
const dlv = (flowId, state, day = DAY) => ({ flowId, state, day });

test("the derivation is the queue summary service's closed register", () => {
  const derived = deriveCounts(
    [msg('f1', 'ACCEPTED'), msg('f1', 'ROUTED'), msg('f1', 'ROUTE_FAILED')],
    ['PENDING', 'HELD', 'SENDING', 'RETRY_WAIT', 'DUE', 'DELIVERED', 'DEAD_LETTERED'].map((state) => dlv('f1', state))
  );
  assert.deepEqual(countsOf(derived, 'f1', DAY), { flowId: 'f1', day: DAY, queued: 6, processed: 1, converted: 1, failed: 2 });
});

test('the figures group by flow and UTC day', () => {
  const derived = deriveCounts(
    [msg('f1', 'ROUTED'), msg('f2', 'ROUTED'), msg('f1', 'ROUTED', '2026-10-21')],
    [dlv('f1', 'DELIVERED'), dlv('f2', 'DEAD_LETTERED')]
  );
  assert.deepEqual(countsOf(derived, 'f1', DAY), { flowId: 'f1', day: DAY, queued: 0, processed: 1, converted: 1, failed: 0 });
  assert.deepEqual(countsOf(derived, 'f2', DAY), { flowId: 'f2', day: DAY, queued: 0, processed: 1, converted: 0, failed: 1 });
  assert.deepEqual(countsOf(derived, 'f1', '2026-10-21'), { flowId: 'f1', day: '2026-10-21', queued: 0, processed: 1, converted: 0, failed: 0 });
  assert.deepEqual(countsOf(derived, 'f9', DAY), { flowId: 'f9', day: DAY, queued: 0, processed: 0, converted: 0, failed: 0 });
});

test('rows with no flow stay out, as the service skips them', () => {
  const derived = deriveCounts([msg(null, 'ROUTED')], [dlv(null, 'DELIVERED'), dlv('f1', 'DELIVERED')]);
  assert.equal(derived.size, 1);
  assert.equal(countsOf(derived, 'f1', DAY).converted, 1);
});

test('a state outside the register fails loudly, never counts silently', () => {
  assert.throws(() => deriveCounts([msg('f1', 'DELIVERED')], []), /unknown message state DELIVERED/);
  assert.throws(() => deriveCounts([], [dlv('f1', 'ROUTED')]), /unknown delivery state ROUTED/);
});

test('differences lists every figure that differs; a 0 difference is an empty list', () => {
  const direct = { queued: 2, processed: 1, converted: 1, failed: 2 };
  // the screen's data attributes are text (queue-dashboard spec :105-108)
  assert.deepEqual(differences({ queued: '2', processed: '1', converted: '1', failed: '2' }, direct), []);
  assert.deepEqual(differences({ queued: '2', processed: '1', converted: '0', failed: '3' }, direct), [
    { field: 'converted', dashboard: 0, direct: 1 },
    { field: 'failed', dashboard: 3, direct: 2 }
  ]);
  assert.deepEqual(differences({ queued: null, processed: '1', converted: '1', failed: '2' }, direct),
    [{ field: 'queued', dashboard: null, direct: 2 }], 'a missing figure is a difference, never a zero');
});

test('delta is after minus before for each figure', () => {
  assert.deepEqual(delta({ queued: 0, processed: 3, converted: 1, failed: 2 }, { queued: 0, processed: 3, converted: 2, failed: 1 }),
    { queued: 0, processed: 0, converted: 1, failed: -1 });
});

test('provenance: every counted row came through intake in this run, none was hand-seeded', () => {
  const clean = {
    messageIds: ['m1', 'm2'],
    trackingIds: ['m1', 'm2'],
    outboxAggregateIds: ['m1', 'm2'],
    intakeTimingMessageIds: ['m1', 'm2'],
    deliveryMessageIds: ['m1', 'm2']
  };
  assert.deepEqual(provenanceGaps(clean), []);

  // the queue-dashboard spec's psql seed shape: a message with no outbox row, no INTAKE timing and no 202
  const seeded = { ...clean, messageIds: ['m1', 'm2', 'm3'], deliveryMessageIds: ['m1', 'm2', 'm3', null] };
  assert.deepEqual(provenanceGaps(seeded), [
    'message m3 was not posted through the intake API in this run',
    'message m3 has no platform.message.accepted outbox row',
    'message m3 has no INTAKE stage timing row',
    'a delivery names no message (message_id null)'
  ]);

  const lost = { ...clean, messageIds: ['m1'], outboxAggregateIds: ['m1'], intakeTimingMessageIds: ['m1'], deliveryMessageIds: ['m1'] };
  assert.deepEqual(provenanceGaps(lost), ['tracking id m2 answered 202 but has no message row']);
});
