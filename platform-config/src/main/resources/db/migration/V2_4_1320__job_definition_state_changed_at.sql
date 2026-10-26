-- E14 follow-up (arjun-fullstack; design note 2026-10-22, option B; ADR-012 section 5 polling surface): a job that was
-- only DISABLED, or only RESUMED, left no trace the polling surface could see - `pausedAt` is set by pausing alone and
-- cleared by resume, and nothing in the application flips `enabled` (the seed or a later migration / manual SQL does).
-- `state_changed_at` is the one stamp for "this job's (enabled, paused) pair changed", written by a trigger so that
-- EVERY writer - the registry crossing, a migration, manual SQL - is covered, not just the pause endpoint.
--
-- Backfill: a paused row keeps its true time (paused_at); every other row gets the migration time. That makes each
-- non-paused job appear once in the first `?since=` poll after deploy - a false positive the agent absorbs, never a
-- false negative. A job type seeded later counts as changed when it appears (the column default).
--
-- The table is platform-scoped (ADR-012 section 4): no tenant_id, no row-level security, access by the ipaas_app grant,
-- which already covers a new column. The trigger function is NOT SECURITY DEFINER (it only edits NEW) and PUBLIC holds
-- no EXECUTE on it. DB clock (now()), not the application Clock - accepted in the design note.
--
-- Rollback (Flyway Community has no undo; run as the migration owner, then delete this version from
-- flyway_schema_history):
--   DROP TRIGGER job_definition_touch_state ON scheduler.job_definition;
--   DROP FUNCTION scheduler.job_definition_touch_state();
--   ALTER TABLE scheduler.job_definition DROP COLUMN state_changed_at;

ALTER TABLE scheduler.job_definition ADD COLUMN state_changed_at timestamp with time zone;

UPDATE scheduler.job_definition SET state_changed_at = COALESCE(paused_at, now());

ALTER TABLE scheduler.job_definition
    ALTER COLUMN state_changed_at SET NOT NULL,
    ALTER COLUMN state_changed_at SET DEFAULT now();

CREATE FUNCTION scheduler.job_definition_touch_state() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.enabled, NEW.paused) IS DISTINCT FROM (OLD.enabled, OLD.paused) THEN
        NEW.state_changed_at := now();
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION scheduler.job_definition_touch_state() FROM PUBLIC;

CREATE TRIGGER job_definition_touch_state
    BEFORE UPDATE OF enabled, paused ON scheduler.job_definition
    FOR EACH ROW EXECUTE FUNCTION scheduler.job_definition_touch_state();
