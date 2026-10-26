// Demo narration (test code only): the words shown on the on-page banner for beats b and c, built from what the run really
// observed (the intake answer, the WireMock journal entry). Pure functions - tests/demo/narration-text.test.mjs.

import { traceIdOf } from '../base-suite/lib/trace.mjs';

/** Beat b: the intake answer. */
export function intakeLines({ status, trackingId, orderId }) {
  if (status !== 202) throw new Error(`narration: the intake answered ${status}, not 202`);
  if (typeof trackingId !== 'string' || trackingId === '') throw new Error('narration: the 202 carried no tracking id');
  return [`POST /interfaces/.../messages  ->  ${status} Accepted`, `order ${orderId}   tracking id ${trackingId}`];
}

/** Beat c: the journal entry the target stub recorded. `entry` = { body, idempotencyKey, traceparent }. */
export function deliveryLines({ path, entry, sentTraceparent }) {
  if (!entry) throw new Error('narration: the stub journal has no delivery to show');
  const sentTrace = traceIdOf(sentTraceparent) ?? sentTraceparent;
  const gotTrace = traceIdOf(entry.traceparent) ?? entry.traceparent ?? '(none)';
  return [
    `WireMock journal  POST ${path}   Idempotency-Key ${entry.idempotencyKey}`,
    `transformed body  ${JSON.stringify(entry.body)}`,
    `traceparent sent ${sentTraceparent}`,
    `traceparent seen at the target ${entry.traceparent ?? '(none)'}   same trace id: ${sentTrace === gotTrace ? 'YES' : 'NO'}`
  ];
}
