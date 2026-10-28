-- Row 570 (C2, follow-up of row 544): the application role may no longer INSERT an audit_outbox row that is already
-- marked relayed (such a row never reaches the ledger: an omission). Table-level INSERT (V2_4_3) is replaced by INSERT
-- on the columns the writers name: tenant_id, actor, action, target_ids, before_state, after_state, event_at, trace_id.
-- id (generated), created_at (server time, no back-dating) and relayed_at stay default-only. A column added to
-- audit_outbox later is NOT insertable until a migration grants it; AuditLedgerLockdownContainerTest pins the list.
-- Backward compatible with the option-A application (its INSERTs name exactly these columns). Numbered after
-- V2_4_1510 (platform-config) / V2_4_1500, the highest on main when this was written.
--
-- ROLLBACK (Flyway Community has no undo; the rollback test runs exactly these lines; REVOKE also removes the column grants):
-- ROLLBACK-BEGIN
-- REVOKE INSERT ON public.audit_outbox FROM ipaas_app;
-- GRANT INSERT ON public.audit_outbox TO ipaas_app;
-- ROLLBACK-END

REVOKE INSERT ON public.audit_outbox FROM ipaas_app;
GRANT INSERT (tenant_id, actor, action, target_ids, before_state, after_state, event_at, trace_id)
    ON public.audit_outbox TO ipaas_app;
