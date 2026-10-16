// T130 (row 323, F21 BS-B, BS-12): the dashboard-count oracle.
//
// BS-12: per flow and UTC day the dashboard's queued / processed / converted / failed must equal a
// DIRECT count of message + delivery rows - 0 difference - and every counted row must have come from
// the real pipeline in this run (no hand-seeded rows). deriveCounts is the queue summary's own closed
// derivation, measured on main: QueueSummaryService.java:18-23 and :46-69 (rows without a flow are
// skipped, :47-49 / :59-61); the day is the UTC day of accepted_at / created_at
// (JdbcQueueSummaryStore.java:23-24, :43, :50). An independent re-derivation from the rows, not a
// call of the service, so a service bug shows as a difference instead of agreeing with itself.

export const FIGURES = Object.freeze(['queued', 'processed', 'converted', 'failed']);

const MESSAGE_FIGURE = Object.freeze({ ACCEPTED: 'queued', ROUTED: 'processed', ROUTE_FAILED: 'failed' });
const DELIVERY_FIGURE = Object.freeze({
  PENDING: 'queued', HELD: 'queued', SENDING: 'queued', RETRY_WAIT: 'queued', DUE: 'queued',
  DELIVERED: 'converted', DEAD_LETTERED: 'failed'
});

const zero = (flowId, day) => ({ flowId, day, queued: 0, processed: 0, converted: 0, failed: 0 });

/** Map "flowId|day" -> { flowId, day, queued, processed, converted, failed } from rows { flowId, state, day }. */
export function deriveCounts(messageRows, deliveryRows) {
  const counts = new Map();
  const add = (rows, register, kind) => {
    for (const row of rows) {
      const figure = register[row.state];
      if (!figure) throw new Error(`unknown ${kind} state ${row.state} - outside the table's CHECK register`);
      if (row.flowId == null) continue; // the service counts work per flow only
      const key = `${row.flowId}|${row.day}`;
      if (!counts.has(key)) counts.set(key, zero(row.flowId, row.day));
      counts.get(key)[figure] += 1;
    }
  };
  add(messageRows, MESSAGE_FIGURE, 'message');
  add(deliveryRows, DELIVERY_FIGURE, 'delivery');
  return counts;
}

/** One (flow, day) group of a derivation; a group with no rows is all zeros. */
export function countsOf(derived, flowId, day) {
  return { ...(derived.get(`${flowId}|${day}`) ?? zero(flowId, day)) };
}

const asNumber = (value) => (value === null || value === undefined || value === '' ? null : Number(value));

/** Every figure where the dashboard (text data attributes) and the direct count differ; [] is a 0 difference. */
export function differences(dashboard, direct) {
  return FIGURES.flatMap((field) => {
    const shown = asNumber(dashboard?.[field]);
    return shown === direct[field] ? [] : [{ field, dashboard: shown, direct: direct[field] }];
  });
}

/** after - before, figure by figure (BS-10: failed -1 / converted +1 after the replay). */
export function delta(before, after) {
  return Object.fromEntries(FIGURES.map((field) => [field, after[field] - before[field]]));
}

/**
 * Why a counted row may NOT have come through the intake API in this run - [] when every one did. The
 * intake transaction writes the message row, its platform.message.accepted outbox row (aggregate = the
 * message id) and its INTAKE stage timing together (IntakeService.java:62-64 on main) and answers the
 * tracking id = the message id (AcceptedMessage.java:11); a psql-seeded row (the queue-dashboard
 * spec's fixture shape, :69-80) has none of them, and its deliveries name no message.
 */
export function provenanceGaps({ messageIds, trackingIds, outboxAggregateIds, intakeTimingMessageIds, deliveryMessageIds }) {
  const posted = new Set(trackingIds);
  const counted = new Set(messageIds);
  const outbox = new Set(outboxAggregateIds);
  const timed = new Set(intakeTimingMessageIds);
  const gaps = [];
  for (const id of messageIds) {
    if (!posted.has(id)) gaps.push(`message ${id} was not posted through the intake API in this run`);
    if (!outbox.has(id)) gaps.push(`message ${id} has no platform.message.accepted outbox row`);
    if (!timed.has(id)) gaps.push(`message ${id} has no INTAKE stage timing row`);
  }
  for (const id of trackingIds) {
    if (!counted.has(id)) gaps.push(`tracking id ${id} answered 202 but has no message row`);
  }
  for (const id of deliveryMessageIds) {
    if (id == null) gaps.push('a delivery names no message (message_id null)');
    else if (!counted.has(id)) gaps.push(`a delivery names message ${id}, which is not a counted message row`);
  }
  return gaps;
}
