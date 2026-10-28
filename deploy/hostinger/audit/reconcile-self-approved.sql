-- Row 544 (audit tamper-evidence, option A), FR-008: reconcile the `selfApproved` flag of every approval audit event
-- against the business row it describes. READ-ONLY: it reports mismatches, it never blocks or repairs.
--
-- One tenant per run, one psql session: the tenant setting and this file are fed together on stdin (deploy/hostinger/README.md,
-- "Reconciliation of selfApproved"):
--     { echo "select set_config('app.tenant_id', '<tenant uuid>', false);"; cat audit/reconcile-self-approved.sql; } | $PSQL
-- (a role bound by row-level security sees only that tenant; the explicit tenant predicate below also holds for a
-- superuser session, which bypasses RLS.) An unset app.tenant_id makes the query fail, never read across tenants.
--
-- Three kinds of approval are checked (event action -> target token -> business row -> truth):
--   FLOW_APPROVED            interface_version:<id>   interface_version        approver_id = author_id
--   CONTRACT_VERSION_APPROVED contract_version:<id>   contract_version         approver_id = author_id
--   DB_TABLE_APPROVED        db_table_approval:<id>   db_target_approved_table decided_by  = requested_by
-- A row is reported when the event claims a value the business row does not bear out, when the event carries no
-- `selfApproved` flag at all (or a malformed one), or when the business row it names no longer exists. No row at all = every approval
-- reconciles. Columns: kind, audit_event_id, target_id, claimed_self_approved, actual_self_approved.
--
-- Known limit: a DB-table entry that was approved, revoked and approved again has several APPROVED events but one
-- business row; compare the newest event only when such a row is reported. This check detects an application claim
-- that disagrees with the business row; it cannot detect an event that was never written (omission).

with approvals as (
    select e.id as audit_event_id,
           e.action,
           -- total casts (PostgreSQL 16+): a malformed after_state or a non-boolean flag yields NULL = reported, never an abort
           -- an after_state longer than 65536 characters is not parsed at all (the JSON parser's stack-depth check is a hard
           -- error, not a soft one): it yields NULL = reported, like any other malformed value
           case when length(e.after_state) <= 65536 then
                case when pg_input_is_valid(e.after_state, 'jsonb') then
                     case when pg_input_is_valid((e.after_state::jsonb) ->> 'selfApproved', 'boolean')
                          then ((e.after_state::jsonb) ->> 'selfApproved')::boolean end
                end
           end as claimed,
           case when jsonb_typeof(e.target_ids) = 'array' then
           (select substr(t, case e.action when 'FLOW_APPROVED' then 19
                                           when 'CONTRACT_VERSION_APPROVED' then 18
                                           else 19 end)
              from jsonb_array_elements_text(e.target_ids) as t
             where t like case e.action when 'FLOW_APPROVED' then 'interface_version:%'
                                        when 'CONTRACT_VERSION_APPROVED' then 'contract_version:%'
                                        else 'db_table_approval:%' end
             limit 1)
           end as target_id
    from public.audit_event e
    where e.tenant_id = current_setting('app.tenant_id')::uuid
      and e.action in ('FLOW_APPROVED', 'CONTRACT_VERSION_APPROVED', 'DB_TABLE_APPROVED')
)
select a.action as kind, a.audit_event_id, a.target_id, a.claimed as claimed_self_approved,
       (v.approver_id = v.author_id) as actual_self_approved
from approvals a
left join public.interface_version v
       on a.action = 'FLOW_APPROVED' and v.id::text = a.target_id
      and v.tenant_id = current_setting('app.tenant_id')::uuid
where a.action = 'FLOW_APPROVED'
  and (v.id is null or a.claimed is distinct from (v.approver_id = v.author_id))
union all
select a.action, a.audit_event_id, a.target_id, a.claimed, (c.approver_id = c.author_id)
from approvals a
left join public.contract_version c
       on a.action = 'CONTRACT_VERSION_APPROVED' and c.id::text = a.target_id
      and c.tenant_id = current_setting('app.tenant_id')::uuid
where a.action = 'CONTRACT_VERSION_APPROVED'
  and (c.id is null or a.claimed is distinct from (c.approver_id = c.author_id))
union all
select a.action, a.audit_event_id, a.target_id, a.claimed, (d.decided_by = d.requested_by)
from approvals a
left join public.db_target_approved_table d
       on a.action = 'DB_TABLE_APPROVED' and d.id::text = a.target_id
      and d.tenant_id = current_setting('app.tenant_id')::uuid
where a.action = 'DB_TABLE_APPROVED'
  and (d.id is null or a.claimed is distinct from (d.decided_by = d.requested_by))
order by 1, 2;
