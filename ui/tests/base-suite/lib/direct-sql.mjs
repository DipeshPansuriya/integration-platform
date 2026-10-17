// T130 (row 323, F21 BS-B, BS-12): the count oracle's direct reads - ONE read-only SELECT each
// (unit/direct-sql.test.mjs), scoped to one tenant by its code. The columns mirror the queue summary's own
// reads (platform-app JdbcQueueSummaryStore.java:43-53 on main): interface_id = the flow, the closed-set
// state, the UTC day of message.accepted_at / delivery.created_at. Provenance reads: the accepted outbox
// row (outbox.aggregate_id = the message id) and the INTAKE stage timing (IntakeService.java:62-64).
// support/db.mjs runs them in a read-only psql session as well (psql.mjs readOnlyPsqlArgs).

const TENANT_CODE = /^[a-z0-9][a-z0-9-]{0,62}$/;

function tenantOf(code) {
  if (typeof code !== 'string' || !TENANT_CODE.test(code)) throw new Error(`direct-sql: ${code} is not a tenant code`);
  return `(select t.id from public.tenant t where t.code = '${code}')`;
}

/** The tenant's id (one row, one column - never empty: the tenant row exists after the boot). */
export function tenantIdSql(code) {
  tenantOf(code); // shape check first
  return `select id::text from public.tenant where code = '${code}'`;
}

/** Rows: message id | flow id | state | UTC day. */
export const messageRowsSql = (code) => "select m.id::text, m.interface_id::text, m.state, to_char(m.accepted_at at time zone 'UTC', 'YYYY-MM-DD')"
  + ` from public.message m where m.tenant_id = ${tenantOf(code)} order by m.accepted_at, m.id`;

/** Rows: message id | flow id | state | UTC day (message id may be NULL - then it prints as an empty field). */
export const deliveryRowsSql = (code) => "select d.message_id::text, d.interface_id::text, d.state, to_char(d.created_at at time zone 'UTC', 'YYYY-MM-DD')"
  + ` from public.delivery d where d.tenant_id = ${tenantOf(code)} order by d.created_at, d.id`;

/** Rows: the aggregate (message) id of every accepted-message outbox row of the tenant. */
export const outboxAggregatesSql = (code) => "select o.aggregate_id::text from public.outbox o"
  + ` where o.topic = 'platform.message.accepted' and o.tenant_id = ${tenantOf(code)} order by o.aggregate_id`;

/** Rows: the message id of every INTAKE stage timing row of the tenant. */
export const intakeTimingSql = (code) => "select s.message_id::text from public.message_stage_timing s"
  + ` where s.stage = 'INTAKE' and s.tenant_id = ${tenantOf(code)} order by s.message_id`;
