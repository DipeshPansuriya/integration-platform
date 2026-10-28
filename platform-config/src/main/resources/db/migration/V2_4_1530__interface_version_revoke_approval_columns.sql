-- Row 570 (M3, follow-up of row 544): the application role may no longer UPDATE interface_version.approver_id or
-- decided_at. "The approver comes from the session actor" (V2_4_1500) then holds on every UPDATE path the role has: the
-- only writer of those two columns is config_approve_version (SECURITY DEFINER, owner). Submit, publish and archive
-- write status / change_note / submitted_at / published_at only; the transition guard trigger still fills decided_at
-- (a trigger assignment is not checked against column privileges). A direct status = 'APPROVED' stays refused by the
-- guard (an approved version names its approver). Numbered V2_4_1530: V2_4_1510 (regrant_operator_allowed_host_write) is another change and V2_4_1520 is left free.
--
-- ROLLBACK (Flyway Community has no undo; the rollback test runs exactly these lines):
-- ROLLBACK-BEGIN
-- GRANT UPDATE (approver_id, decided_at) ON public.interface_version TO ipaas_app;
-- ROLLBACK-END

REVOKE UPDATE (approver_id, decided_at) ON public.interface_version FROM ipaas_app;
