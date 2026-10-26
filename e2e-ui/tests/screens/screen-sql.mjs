// Pure fixture builders the lane-screens-gap docker specs share (no docker, no playwright import, so
// `node --test` exercises them: tests/screens/unit/screen-sql.test.mjs). Every value that reaches `psql -c`
// is validated here (uuid / ISO day / non-negative integer) instead of being interpolated as-is.

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The UTC calendar day (YYYY-MM-DD), shifted by whole days; the backend's report and activity days are UTC. */
export function utcDay(offsetDays = 0, now = new Date()) {
  return new Date(now.getTime() + offsetDays * 86_400_000).toISOString().slice(0, 10);
}

export function assertUuid(value, name) {
  if (typeof value !== 'string' || !UUID.test(value)) throw new Error(`${name} must be a uuid, got ${JSON.stringify(value)}`);
  return value;
}

export function assertIsoDay(value) {
  const parsed = typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T00:00:00Z`) : null;
  const ok = parsed !== null && !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
  if (!ok) throw new Error(`day must be a real YYYY-MM-DD, got ${JSON.stringify(value)}`);
  return value;
}

function count(value, name) {
  if (!Number.isInteger(value) || value < 0) throw new Error(`${name} must be a non-negative integer, got ${JSON.stringify(value)}`);
  return value;
}

/**
 * One public.delivery_daily_aggregate row. The table is ENABLE + FORCE row-level security
 * (V2_4_1300), so the session binds app.tenant_id first; the insert then passes WITH CHECK.
 * Why a fixture row at all: the table is written only by the DAILY_REPORT scheduler job
 * (DailyReportPlaceholderHandler, "yesterday" per active tenant) - no API produces it.
 */
export function aggregateRowSql({ tenantId, day, interfaceId, targetId, successes = 0, failuresTransient = 0,
  failuresAmbiguous = 0, failuresPermanent = 0, retriesMade = 0, retriedThenSucceeded = 0, stillStuck = 0 }) {
  const counts = [count(successes, 'successes'), count(failuresTransient, 'failuresTransient'),
    count(failuresAmbiguous, 'failuresAmbiguous'), count(failuresPermanent, 'failuresPermanent'),
    count(retriesMade, 'retriesMade'), count(retriedThenSucceeded, 'retriedThenSucceeded'), count(stillStuck, 'stillStuck')];
  const tenant = assertUuid(tenantId, 'tenantId');
  return `select set_config('app.tenant_id', '${tenant}', false);`
    + ' insert into public.delivery_daily_aggregate (tenant_id, day, interface_id, target_id, successes,'
    + ' failures_transient, failures_ambiguous, failures_permanent, retries_made, retried_then_succeeded, still_stuck)'
    + ` values ('${tenant}'::uuid, '${assertIsoDay(day)}'::date, '${assertUuid(interfaceId, 'interfaceId')}'::uuid,`
    + ` '${assertUuid(targetId, 'targetId')}'::uuid, ${counts.join(', ')})`;
}
