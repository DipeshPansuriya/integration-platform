-- IB08 Phase 3: add the CALLBACK_SEND job to the closed register (ADR-012).
-- FIXED_DELAY 15 seconds: walks active tenants and dispatches SCHEDULED-mode callback responses.
-- IMMEDIATE-mode callbacks are dispatched asynchronously from the response capture adapter.

INSERT INTO scheduler.job_definition
    (job_type, schedule_kind, schedule, enabled, paused, max_run_seconds, late_threshold_seconds)
VALUES
    ('CALLBACK_SEND', 'FIXED_DELAY', '15', true, false, 120, 30)
ON CONFLICT (job_type) DO NOTHING;
