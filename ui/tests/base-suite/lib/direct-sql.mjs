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

// T131 (row 323, F21 BS-C, BS-10): the journey's direct reads - the same rules: ONE read-only SELECT,
// scoped to the tenant by its code, every id a UUID literal. Pins: delivery.mapping_version_id
// (V2_3_930) and contract_version_id (V2_3_3_1). Attempts: the detail as its md5 only (the text can hold
// "|" or a line break; "kept unchanged" needs only its hash - the text itself is read through the
// failures API). audit_event is T109's (lane t109 2344b8d V2_2_1220, NOT on main): provisional columns.

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function uuidOf(id) {
  if (typeof id !== 'string' || !UUID.test(id)) throw new Error(`direct-sql: ${id} is not a UUID`);
  return `'${id}'`;
}

/** Rows: delivery id | state | mapping version id | contract version id - of one message. */
export const deliveryOfMessageSql = (code, messageId) => {
  const id = uuidOf(messageId);
  return 'select d.id::text, d.state, d.mapping_version_id::text, d.contract_version_id::text from public.delivery d'
    + ` where d.tenant_id = ${tenantOf(code)} and d.message_id = ${id} order by d.created_at, d.id`;
};

/** Rows: attempt id | layer | outcome class | started | finished | md5 of the detail - of one delivery, in order. */
export const attemptRowsSql = (code, deliveryId) => {
  const id = uuidOf(deliveryId);
  return "select a.id::text, a.layer::text, a.outcome_class, a.started_at::text, a.finished_at::text, md5(coalesce(a.detail, ''))"
    + ` from public.delivery_attempt a where a.tenant_id = ${tenantOf(code)} and a.delivery_id = ${id} order by a.id`;
};

/** Rows: the type of every delivery_event of one delivery, in order. */
export const eventTypesSql = (code, deliveryId) => {
  const id = uuidOf(deliveryId);
  return `select e.type from public.delivery_event e where e.tenant_id = ${tenantOf(code)} and e.delivery_id = ${id} order by e.id`;
};

/** Rows: audit id | tenant id | actor | action | target ids + before/after state as one line - of the tenant, in order. */
export const auditRowsSql = (code) => "select a.id::text, a.tenant_id::text, a.actor, a.action,"
  + " translate(a.target_ids::text || ' ' || coalesce(a.before_state, '') || ' ' || coalesce(a.after_state, ''), chr(124) || chr(10) || chr(13), '   ')"
  + ` from public.audit_event a where a.tenant_id = ${tenantOf(code)} order by a.id`;
