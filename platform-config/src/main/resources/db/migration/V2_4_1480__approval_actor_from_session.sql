-- Row 544 (audit tamper-evidence, option A): the approver of a flow version or a contract version comes from the
-- session actor the application stamped in the same transaction (app.actor_id), exactly as the DB-table approval
-- already does (config_decide_db_table_approval, V2_4_1420) - one source for "who approved". Version provisional: the
-- integrator renumbers it at the seam (V2_4_1440 is the highest in platform-config; V2_4_1470 is the audit migration
-- in platform-app). It names no audit table, so platform-config-only database runs stay valid.
--
-- Both functions keep their 2-argument signature (grants and owner are kept by CREATE OR REPLACE). New first step,
-- before any lookup, no refusal row, no state change:
--   * no stamped actor            -> 'REFUSED_NO_ACTOR'   (fail closed: an application that does not stamp is refused)
--   * actor <> p_approver_id      -> 'REFUSED_ACTOR_MISMATCH'
-- otherwise the approver written (and the refusal row's actor) is the stamped actor. Verdicts for every existing case
-- (APPROVED, VERSION_NOT_FOUND, REFUSED_<status>) are unchanged.
--
-- Deploy: the previous application never stamps, so with this migration applied it would get REFUSED_NO_ACTOR for every
-- flow/contract approval; platform-app is stopped before the migration (deploy/hostinger/README.md, "Audit ledger
-- lockdown"). The opposite order (new application against the old function) is harmless: the stamp is ignored.
--
-- ROLLBACK (Flyway Community has no undo; the rollback test runs exactly these lines). Restores the V2_4_1420 bodies:
-- ROLLBACK-BEGIN
-- CREATE OR REPLACE FUNCTION public.config_approve_version(p_version_id uuid, p_approver_id uuid)
-- RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $f$
-- DECLARE
--     v public.interface_version%ROWTYPE;
-- BEGIN
--     SELECT * INTO v FROM public.interface_version
--         WHERE id = p_version_id AND tenant_id = current_setting('app.tenant_id')::uuid FOR UPDATE;
--     IF NOT FOUND THEN
--         RETURN 'VERSION_NOT_FOUND';
--     ELSIF v.status <> 'PENDING_APPROVAL' THEN
--         INSERT INTO public.config_version_refusal (tenant_id, version_id, refusal, actor_id)
--             VALUES (v.tenant_id, p_version_id, 'NOT_PENDING_APPROVAL', p_approver_id);
--         RETURN 'REFUSED_' || v.status;
--     END IF;
--     UPDATE public.interface_version SET status = 'APPROVED', approver_id = p_approver_id, decided_at = now()
--         WHERE id = p_version_id;
--     RETURN 'APPROVED';
-- END $f$;
-- CREATE OR REPLACE FUNCTION public.config_approve_contract_version(p_version_id uuid, p_approver_id uuid)
-- RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $f$
-- DECLARE
--     v public.contract_version%ROWTYPE;
-- BEGIN
--     SELECT * INTO v FROM public.contract_version
--         WHERE id = p_version_id AND tenant_id = current_setting('app.tenant_id')::uuid FOR UPDATE;
--     IF NOT FOUND THEN
--         RETURN 'VERSION_NOT_FOUND';
--     ELSIF v.status <> 'PENDING_APPROVAL' THEN
--         RETURN 'REFUSED_' || v.status;
--     END IF;
--     UPDATE public.contract_version SET status = 'APPROVED', approver_id = p_approver_id WHERE id = p_version_id;
--     RETURN 'APPROVED';
-- END $f$;
-- ROLLBACK-END

CREATE OR REPLACE FUNCTION public.config_approve_version(p_version_id uuid, p_approver_id uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v     public.interface_version%ROWTYPE;
    actor text := nullif(pg_catalog.current_setting('app.actor_id', true), '');
BEGIN
    IF actor IS NULL THEN
        RETURN 'REFUSED_NO_ACTOR';
    ELSIF actor IS DISTINCT FROM p_approver_id::text THEN
        RETURN 'REFUSED_ACTOR_MISMATCH';
    END IF;
    SELECT * INTO v FROM public.interface_version
        WHERE id = p_version_id
          AND tenant_id = pg_catalog.current_setting('app.tenant_id')::uuid
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'VERSION_NOT_FOUND';
    ELSIF v.status <> 'PENDING_APPROVAL' THEN
        INSERT INTO public.config_version_refusal (tenant_id, version_id, refusal, actor_id)
            VALUES (v.tenant_id, p_version_id, 'NOT_PENDING_APPROVAL', actor::uuid);
        RETURN 'REFUSED_' || v.status;
    END IF;
    UPDATE public.interface_version
        SET status = 'APPROVED', approver_id = actor::uuid, decided_at = pg_catalog.now()
        WHERE id = p_version_id;
    RETURN 'APPROVED';
END $$;

CREATE OR REPLACE FUNCTION public.config_approve_contract_version(p_version_id uuid, p_approver_id uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v     public.contract_version%ROWTYPE;
    actor text := nullif(pg_catalog.current_setting('app.actor_id', true), '');
BEGIN
    IF actor IS NULL THEN
        RETURN 'REFUSED_NO_ACTOR';
    ELSIF actor IS DISTINCT FROM p_approver_id::text THEN
        RETURN 'REFUSED_ACTOR_MISMATCH';
    END IF;
    SELECT * INTO v FROM public.contract_version
        WHERE id = p_version_id
          AND tenant_id = pg_catalog.current_setting('app.tenant_id')::uuid
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'VERSION_NOT_FOUND';
    ELSIF v.status <> 'PENDING_APPROVAL' THEN
        RETURN 'REFUSED_' || v.status;
    END IF;
    UPDATE public.contract_version
        SET status = 'APPROVED', approver_id = actor::uuid
        WHERE id = p_version_id;
    RETURN 'APPROVED';
END $$;
