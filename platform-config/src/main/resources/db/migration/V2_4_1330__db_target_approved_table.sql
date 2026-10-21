-- E16 finding F3 (arjun-fullstack; design note 2026-10-22 option B; Dipesh decisions D1-D4 of 2026-10-22):
-- `approvedTables` lived in the same system `settings` JSON as `table`, so the person who picks the table also wrote
-- the list that is meant to restrict it. The approved tables of a DB target move to their own tenant table that no
-- configurator can edit:
--   * a CONFIGURATOR (EDIT_CONFIGURATION) REQUESTS a table (an INSERT, status PENDING_APPROVAL);
--   * a DIFFERENT tenant admin / approver (APPROVE_DB_TABLE) APPROVES or REVOKES it through the SECURITY DEFINER
--     function below (D1: the tenant's approver, not the platform operator). The requester never approves their own
--     request, even in a one-admin tenant (D4; the contract-approval rule of V2_2_5_1:25-48);
--   * the app role holds SELECT and INSERT only - no UPDATE - so a decision moves only through the function (the
--     V2_2_5_1 pattern: the tenant comes from the stamped session setting, never an argument);
--   * D3: no column-level list. Column control belongs in the target database's own column grants.
-- Revoking is the safe direction, so the four-eyes CHECK binds the APPROVAL (decided_by), not the revoke: the
-- requester may withdraw an approved table (revoked_by is recorded beside it).
--
-- D2: every existing `approvedTables` entry of a DB system moves here as PENDING_APPROVAL (a DB send is refused until
-- someone approves it), requested_by = 'migration'; the key is then removed from the system's settings. The backfill
-- runs BEFORE row-level security is forced, one tenant at a time with that tenant stamped, so it also works for an
-- owner that holds neither SUPERUSER nor BYPASSRLS (public.system has FORCE ROW LEVEL SECURITY since V2_4_1261).
-- Entries that are not plain identifiers cannot be stored and are dropped with the key (they could never be sent to).
--
-- Rollback (manual; Flyway Community has no undo; the settings keys are NOT restored - re-enter them from the rows):
--   DROP FUNCTION public.config_decide_db_table_approval(uuid, text);
--   DROP FUNCTION public.db_target_fingerprint(jsonb);
--   DROP TABLE public.db_target_approved_table;

-- Karan's re-attack review (2026-10-22), change 1: an approval is bound to WHERE the table lives. The fingerprint is a
-- SHA-256 of the settings that decide the destination database (host, port, database, restricted account). It is
-- stored with the request, the approval is refused when it no longer matches, and a send or publish counts an
-- approval only while the system's CURRENT settings still hash to it - so a configurator cannot get a table approved
-- on one database and then repoint the system. Pure function: no table access, no privilege needed.
CREATE FUNCTION public.db_target_fingerprint(p_settings jsonb) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE SET search_path = pg_catalog, pg_temp AS $$
    SELECT pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
        coalesce(p_settings ->> 'host', '') || E'\x1f' || coalesce(p_settings ->> 'port', '') || E'\x1f'
        || coalesce(p_settings ->> 'database', '') || E'\x1f' || coalesce(p_settings ->> 'user', ''),
        'UTF8')), 'hex')
$$;

CREATE TABLE public.db_target_approved_table (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES public.tenant (id),
    system_id    uuid        NOT NULL,
    table_name   text        NOT NULL CHECK (table_name ~ '^[A-Za-z_][A-Za-z_0-9]{0,62}$'),
    status       text        NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REVOKED')),
    requested_by text        NOT NULL,
    requested_at timestamp with time zone NOT NULL DEFAULT now(),
    decided_by   text,
    decided_at   timestamp with time zone,
    revoked_by   text,
    revoked_at   timestamp with time zone,
    target_hash  text        NOT NULL,
    CONSTRAINT db_target_approved_table_system_composite FOREIGN KEY (tenant_id, system_id)
        REFERENCES public.system (tenant_id, id),
    CONSTRAINT db_target_approved_table_four_eyes CHECK (decided_by IS DISTINCT FROM requested_by),
    CONSTRAINT db_target_approved_table_state CHECK (
        (status = 'PENDING_APPROVAL' AND decided_by IS NULL AND revoked_by IS NULL)
        OR (status = 'APPROVED' AND decided_by IS NOT NULL AND revoked_by IS NULL)
        OR (status = 'REVOKED' AND decided_by IS NOT NULL AND revoked_by IS NOT NULL))
);

-- one live (pending or approved) row per system and table name, whatever the letter case; a revoked row frees it
CREATE UNIQUE INDEX db_target_approved_table_live
    ON public.db_target_approved_table (tenant_id, system_id, lower(table_name)) WHERE status <> 'REVOKED';

-- D2 backfill (before FORCE): per tenant, stamped
DO $backfill$
DECLARE
    tenant uuid;
    inserted integer;
    total integer := 0;
BEGIN
    FOR tenant IN SELECT id FROM public.tenant LOOP
        PERFORM set_config('app.tenant_id', tenant::text, true);
        INSERT INTO public.db_target_approved_table (tenant_id, system_id, table_name, status, requested_by,
                                                     target_hash)
            SELECT tenant, s.id, e.name, 'PENDING_APPROVAL', 'migration', public.db_target_fingerprint(s.settings)
            FROM public.system s
            CROSS JOIN LATERAL (
                SELECT DISTINCT ON (lower(x.name)) x.name
                FROM jsonb_array_elements_text(s.settings -> 'approvedTables') AS x(name)
                WHERE x.name ~ '^[A-Za-z_][A-Za-z_0-9]{0,62}$'
                ORDER BY lower(x.name), x.name) e
            WHERE s.tenant_id = tenant AND s.type = 'DB' AND jsonb_typeof(s.settings -> 'approvedTables') = 'array';
        GET DIAGNOSTICS inserted = ROW_COUNT;
        total := total + inserted;
        UPDATE public.system SET settings = settings - 'approvedTables'
            WHERE tenant_id = tenant AND type = 'DB' AND settings -> 'approvedTables' IS NOT NULL;
    END LOOP;
    RAISE NOTICE 'db_target_approved_table backfill: % approved-table entries moved as PENDING_APPROVAL', total;
END
$backfill$;

ALTER TABLE public.db_target_approved_table ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.db_target_approved_table FORCE ROW LEVEL SECURITY;

CREATE POLICY db_target_approved_table_tenant_isolation ON public.db_target_approved_table
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- no UPDATE and no DELETE for the app: a decision moves only through the definer function
GRANT SELECT, INSERT ON public.db_target_approved_table TO ipaas_app;


-- Karan's change 2: the decider is NOT an argument. The repository stamps the validated session user into the
-- transaction-local setting app.actor_id (as the platform stamps app.tenant_id) in the same transaction, and this
-- function reads only that. The database does not authenticate end users (the app connects as one role), so the stamp
-- is the app's; the function refuses to run without it.
-- Karan's change 6: search_path is pinned (catalog first, pg_temp last) and every name below is schema-qualified.
--
-- Answers: APPROVED | REVOKED | NOT_FOUND | REFUSED_SELF_APPROVAL | REFUSED_TARGET_CHANGED | REFUSED_NO_ACTOR |
-- REFUSED_UNKNOWN_DECISION | REFUSED_<status> (the row's current status, when the move is not allowed from it).
-- The state is checked first, so a decided row is REFUSED_<status> whoever asks; self-approval answers by name
-- before the table CHECK would fire; an approval is refused when the system was repointed since the request.
CREATE FUNCTION public.config_decide_db_table_approval(p_id uuid, p_decision text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    r       public.db_target_approved_table%ROWTYPE;
    actor   text := nullif(pg_catalog.current_setting('app.actor_id', true), '');
    current_hash text;
BEGIN
    IF p_decision IS NULL OR p_decision NOT IN ('APPROVE', 'REVOKE') THEN
        RETURN 'REFUSED_UNKNOWN_DECISION';
    END IF;
    IF actor IS NULL THEN
        RETURN 'REFUSED_NO_ACTOR';
    END IF;
    SELECT * INTO r FROM public.db_target_approved_table
        WHERE id = p_id
          AND tenant_id = pg_catalog.current_setting('app.tenant_id')::uuid
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF p_decision = 'APPROVE' THEN
        IF r.status <> 'PENDING_APPROVAL' THEN
            RETURN 'REFUSED_' || r.status;
        ELSIF r.requested_by = actor THEN
            RETURN 'REFUSED_SELF_APPROVAL';
        END IF;
        SELECT public.db_target_fingerprint(s.settings) INTO current_hash FROM public.system s
            WHERE s.id = r.system_id AND s.tenant_id = r.tenant_id;
        IF current_hash IS DISTINCT FROM r.target_hash THEN
            RETURN 'REFUSED_TARGET_CHANGED';
        END IF;
        UPDATE public.db_target_approved_table
            SET status = 'APPROVED', decided_by = actor, decided_at = pg_catalog.now()
            WHERE id = p_id;
        RETURN 'APPROVED';
    END IF;
    IF r.status <> 'APPROVED' THEN
        RETURN 'REFUSED_' || r.status;
    END IF;
    UPDATE public.db_target_approved_table
        SET status = 'REVOKED', revoked_by = actor, revoked_at = pg_catalog.now()
        WHERE id = p_id;
    RETURN 'REVOKED';
END $$;

REVOKE ALL ON FUNCTION public.config_decide_db_table_approval(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.config_decide_db_table_approval(uuid, text) TO ipaas_app;
