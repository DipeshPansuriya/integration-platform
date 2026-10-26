// lane qa-release (2026-10-22): the pure oracles the front-half specs share (unit/front-checks.test.mjs).

import { leakHits } from './tenants.mjs';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ms = (iso) => (typeof iso === 'string' ? Date.parse(iso) : Number.NaN);

export function uuidShaped(value) {
  return typeof value === 'string' && UUID.test(value);
}

/**
 * Problems of a timeline read when the message only went through intake (no delivery yet or none expected):
 * the message and flow are the right ones and INTAKE is there with a start, a finish and a duration >= 0.
 * Later stages may or may not be present - the front half never waits for the pipeline.
 */
export function intakeStageProblems(timeline, trackingId, flowId) {
  if (!timeline || !Array.isArray(timeline.stages)) return ['no timeline answer'];
  const problems = [];
  if (timeline.trackingId !== trackingId) problems.push(`tracking id ${timeline.trackingId}, not ${trackingId}`);
  if (timeline.flowId !== flowId) problems.push(`flow ${timeline.flowId}, not ${flowId}`);
  const intake = timeline.stages.find((s) => s.stage === 'INTAKE');
  if (!intake) return [...problems, 'stage INTAKE is missing'];
  const start = ms(intake.startedAt);
  const end = ms(intake.finishedAt);
  if (Number.isNaN(start)) problems.push('INTAKE has no start time');
  if (intake.finishedAt === null || intake.finishedAt === undefined) problems.push('INTAKE has not finished');
  else if (!Number.isNaN(start) && !Number.isNaN(end) && end < start) problems.push('INTAKE finishes before it starts');
  if (typeof intake.durationMs !== 'number' || intake.durationMs < 0) problems.push(`INTAKE duration ${intake.durationMs} is not >= 0`);
  return problems;
}

/**
 * The JSON text of `record` plus a `padding` field so the UTF-8 body is exactly `bytes` long (all ASCII, so the
 * length is exact by construction) - the intake cap is a strict ">" on the body length (IntakeEndpointShapeTest:96).
 * The padding is a NON-key field: the business key (orderId) stays short, because main refuses a key over 2048
 * UTF-8 bytes with 422 BUSINESS_KEY_TOO_LONG (merge bda37b3), which would hide the body-size answer.
 */
export function exactBytesBody(record, bytes) {
  const empty = JSON.stringify({ ...record, padding: '' });
  const base = Buffer.byteLength(empty, 'utf8');
  if (base > bytes) throw new Error(`exactBytesBody: ${bytes} bytes is smaller than the record (${base})`);
  return JSON.stringify({ ...record, padding: 'x'.repeat(bytes - base) });
}

/** The JSON text of `record` whose business key (orderId) is exactly `keyBytes` UTF-8 bytes (ASCII padding). */
export function longKeyBody(record, keyBytes) {
  if (keyBytes < record.orderId.length) throw new Error(`longKeyBody: ${keyBytes} bytes is smaller than the key (${record.orderId.length})`);
  return JSON.stringify({ ...record, orderId: record.orderId + 'k'.repeat(keyBytes - record.orderId.length) });
}

/** The other tenant's markers and any of its ids (`ids`) found in an answer's text: [] when nothing leaks. */
export function foreignIdHits(text, tenant, ids) {
  const body = String(text ?? '');
  return [...leakHits(body, tenant), ...ids.filter((id) => body.includes(id))];
}
