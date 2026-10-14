-- E14 T-056 (ADR-012 section 1, the closed register; sanjay-developer): the four job_definition rows - the ONLY
-- jobs the platform runs. db-scheduler's own state lives in scheduler.scheduled_tasks; these rows are the
-- schedule of record, so their values here are the seed cadence each job runs at (an operator changes a row
-- through administration; the change takes effect at the next schedule sync, no restart):
--   DELIVERY_RETRY      the E9 delivery retry loop (every tenant); its real body arrives in wave 1 (T-141, E9),
--                       so wave 0 runs it as a no-op wrapper recording - one run per minute.
--   PAYLOAD_PURGE       daily, off-peak; drops a day's partitions (wave 1+ body).
--   DAILY_REPORT        just after midnight; the dashboard day roll-up (wave 1+ body).
--   CREDENTIAL_CLEANUP  hourly; expires the operator credential store (wave 1+ body).
-- Timing values: UTC. Late thresholds are the missed-detection trip wires of T-062 (ADR-012 section 6).
-- Rollback (Flyway Community has no undo; run as the migration owner, then drop this version from
-- flyway_schema_history): DELETE FROM scheduler.job_definition WHERE job_type IN ('DELIVERY_RETRY',
-- 'PAYLOAD_PURGE', 'DAILY_REPORT', 'CREDENTIAL_CLEANUP');

insert into scheduler.job_definition
    (job_type, schedule_kind, schedule, enabled, paused, max_run_seconds, late_threshold_seconds)
values
    ('DELIVERY_RETRY', 'FIXED_DELAY', '60', true, false, 300, 60),
    ('PAYLOAD_PURGE', 'CRON', '0 0 2 * * *', true, false, 3600, 600),
    ('DAILY_REPORT', 'CRON', '0 5 0 * * *', true, false, 1800, 600),
    ('CREDENTIAL_CLEANUP', 'FIXED_DELAY', '3600', true, false, 600, 300)
on conflict (job_type) do nothing;