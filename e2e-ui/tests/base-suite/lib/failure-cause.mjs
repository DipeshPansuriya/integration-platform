// T131 (row 323, F21 BS-C, BS-10): which failure the failures row really shows.
//
// A target's 403 and a destination refusal are both PERMANENT (ApiTargetCallTest.java:66 / :155), both
// dead-letter at DELIVER and both read as origin TARGET (lane t112 MessageTrackingService.java:101-104:
// any non-success attempt detail). On screen they look the same; only the last-error text differs
// (ApiTargetCall.java on main):
//   :94-95  "the target answered HTTP <status> for delivery <id>: class <CLASS>"
//   :66-67  "the destination of <uri> is refused (<Refusal>): nothing is sent to it"
//   :50-52, :59-61, :82-84  platform-side refusals: no target_endpoint row / no transform result / TLS
// BS-10 passes ONLY on the target's 403: a destination refusal means the dev-only exception is not working.

/** The destination guard's closed set (platform-config Refusal.java on main). */
export const REFUSALS = Object.freeze(['SCHEME_NOT_ALLOWED', 'CREDENTIALS_IN_ADDRESS', 'NO_HOST', 'NOT_ON_ALLOW_LIST', 'REFUSED_ADDRESS']);

const REFUSAL = new RegExp(`\\b(${REFUSALS.join('|')})\\b`);
const TARGET_HTTP = /\bthe target answered HTTP (\d{3})\b/;
const PLATFORM = [/has no target_endpoint row/, /no transform result exists/, /TLS handshake with .* failed/];

/** { kind: TARGET_HTTP, status } | { kind: DESTINATION_REFUSAL, refusal } | { kind: PLATFORM_REFUSAL, reason } | UNKNOWN | NONE. */
export function causeOf(text) {
  if (typeof text !== 'string' || text.trim() === '') return { kind: 'NONE' };
  const refusal = text.match(REFUSAL);
  if (refusal) return { kind: 'DESTINATION_REFUSAL', refusal: refusal[1] }; // fail closed: a refusal wins
  const platform = PLATFORM.find((pattern) => pattern.test(text));
  if (platform) return { kind: 'PLATFORM_REFUSAL', reason: text.match(platform)[0] };
  const http = text.match(TARGET_HTTP);
  if (http) return { kind: 'TARGET_HTTP', status: Number(http[1]) };
  return { kind: 'UNKNOWN' };
}

/** Problems of BS-10's failures row (T110 Failure shape); [] only for the target's own 403 at DELIVER. */
export function deliverFailureProblems(row, { trackingId, deliveryId }) {
  if (!row) return [`message ${trackingId} is not listed on the failures read`];
  const problems = [];
  if (row.trackingId !== trackingId) problems.push(`tracking id ${row.trackingId}, not ${trackingId}`);
  if (row.deliveryId !== deliveryId) problems.push(`delivery id ${row.deliveryId}, not ${deliveryId}`);
  if (row.failingStage !== 'DELIVER') problems.push(`failing stage ${row.failingStage}, not DELIVER`);
  if (row.errorClass !== 'PERMANENT') problems.push(`error class ${row.errorClass}, not PERMANENT`);
  if (row.origin !== 'TARGET') problems.push(`origin ${row.origin}, not TARGET`);
  const cause = causeOf(row.lastError);
  if (cause.kind === 'DESTINATION_REFUSAL') {
    problems.push(`destination refusal ${cause.refusal}, not the target's 403 - the dev-only exception is not working (BS-10 FAILS): ${row.lastError}`);
  } else if (cause.kind === 'PLATFORM_REFUSAL') {
    problems.push(`platform-side refusal (${cause.reason}), not the target's 403: ${row.lastError}`);
  } else if (cause.kind === 'TARGET_HTTP' && cause.status !== 403) {
    problems.push(`the target answered HTTP ${cause.status}, not the stub's 403`);
  } else if (cause.kind === 'NONE') {
    problems.push('no last error on the row - the target\'s 403 is not shown');
  } else if (cause.kind === 'UNKNOWN') {
    problems.push(`the last error does not carry the target's 403: ${row.lastError}`);
  }
  return problems;
}
