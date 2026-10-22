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
 * The JSON text of `record` padded in orderId so the UTF-8 body is exactly `bytes` long (all ASCII, so the
 * length is exact by construction) - the intake cap is a strict ">" on the body length (IntakeEndpointShapeTest:96).
 */
export function exactBytesBody(record, bytes) {
  const plain = JSON.stringify(record);
  if (Buffer.byteLength(plain, 'utf8') > bytes) throw new Error(`exactBytesBody: ${bytes} bytes is smaller than the record (${Buffer.byteLength(plain, 'utf8')})`);
  const padded = JSON.stringify({ ...record, orderId: record.orderId + 'x'.repeat(bytes - Buffer.byteLength(plain, 'utf8')) });
  return padded;
}

/** The other tenant's markers and any of its ids (`ids`) found in an answer's text: [] when nothing leaks. */
export function foreignIdHits(text, tenant, ids) {
  const body = String(text ?? '');
  return [...leakHits(body, tenant), ...ids.filter((id) => body.includes(id))];
}
