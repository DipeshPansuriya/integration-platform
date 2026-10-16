-- E11 A1 narrow (T-048, rel10; row278-integration): the append-only stage timing table of the message store
-- family (ADR-014 - it partitions and pairs on the message's accepted_at so a timing can never drift off the
-- message's partition day, and it participates in the daily purge/retention with its siblings V2_1_810/811).
--
-- One row per stage per message: INTAKE is written by the intake transaction (platform-ingest); ROUTE, TRANSFORM
-- and DELIVER are written by their own stages through the StageTimings port - those writers belong to the route,
-- transform and delivery lanes and read this same table later: the message timeline (E13), the reconciler (E12)
-- and the delivery screens (F10).
--
-- Append-only on purpose: the application role gets SELECT and INSERT only. UPDATE/DELETE/TRUNCATE are revoked -
-- a corrected timing would not be a correlation, it would be an edit after the fact. Like the message family,
-- no DEFAULT partition (one would block later CREATE ... PARTITION OF ranges, ADR-014); the horizon is kept
-- extended by the ADR-012 maintenance job, a DDL identity holding the partition DDL, never the application role.
--
-- Columns (the schema E12/E13/F10 read):
--   id           uuid, surrogate - the writer returns it to prove the row landed on the stamped tenant
--   message_id   uuid  - the message's tracking id (the message id, ADR-011 D7: records carry ids only)
--   tenant_id    uuid  - the tenant, always the stamped one (RLS below; never a parameter)
--   accepted_at  timestamptz - copied from the message row (partition key; also the pair key for the UNIQUE)
--   stage        text  - INTAKE | ROUTE | TRANSFORM | DELIVER (the pipeline's closed stage set)
--   started_at   timestamptz - when the stage started
--   finished_at  timestamptz - when the stage finished, NULL while still running
-- The UNIQUE (message_id, accepted_at, stage) keeps one row per stage; a stage that says it ran twice is a bug
-- and re-raises on the insert - there is no UPSERT.

CREATE TABLE public.message_stage_timing (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    message_id  uuid        NOT NULL,
    tenant_id   uuid        NOT NULL REFERENCES public.tenant (id),
    accepted_at timestamptz NOT NULL,
    stage       text        NOT NULL CHECK (stage IN ('INTAKE', 'ROUTE', 'TRANSFORM', 'DELIVER')),
    started_at  timestamptz NOT NULL,
    finished_at timestamptz,
    PRIMARY KEY (id, accepted_at),
    CONSTRAINT message_stage_timing_one_row_per_stage UNIQUE (message_id, accepted_at, stage)
) PARTITION BY RANGE (accepted_at);

DO $$
DECLARE
    day date;
BEGIN
    FOR day IN SELECT generate_series(CURRENT_DATE - 1, CURRENT_DATE + 7, interval '1 day')::date
    LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF public.message_stage_timing FOR VALUES FROM (%L) TO (%L)',
            'message_stage_timing_' || to_char(day, 'YYYYMMDD'), day, day + 1);
    END LOOP;
END
$$;

ALTER TABLE public.message_stage_timing ENABLE ROW LEVEL SECURITY;

CREATE POLICY message_stage_timing_tenant_isolation ON public.message_stage_timing
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);

-- The stage writers insert; every reader (timeline, reconciler, screens) selects. Nothing else - the table is
-- append-only for the application role (V2_3_2, delivery_attempt, is the pattern).
GRANT SELECT, INSERT ON public.message_stage_timing TO ipaas_app;
REVOKE UPDATE, DELETE, TRUNCATE ON public.message_stage_timing FROM ipaas_app;

-- The message id / stage is what the readers look up; accepted_at is the partition's own range.
CREATE INDEX message_stage_timing_message_idx ON public.message_stage_timing (message_id, accepted_at, stage);

-- Rollback (manual, owner only; Flyway community has no undo): DROP TABLE public.message_stage_timing;