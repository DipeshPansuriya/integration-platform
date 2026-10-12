-- E14 seam (arjun-fullstack, F8; ADR-012 section 4, Accepted by Dipesh 2026-10-12): the platform-scoped scheduler
-- tables, in their own schema `scheduler` - NOT public. Jobs are platform-wide (the purge drops a day's partition for
-- every tenant, the retry loops every tenant), so NO row here is tenant data:
--   - `scheduled_tasks`        db-scheduler 16.12.0's task table (Flyway owns the schema; the auto-create is off in
--                              T-056's settings), exactly the columns db-scheduler expects, claimed by row lock;
--   - `job_definition`         one row per job type (the ADR-012 closed register: DELIVERY_RETRY, PAYLOAD_PURGE,
--                              DAILY_REPORT, CREDENTIAL_CLEANUP): schedule (fixed delay or cron), enabled, paused
--                              with who/when, maximum run time, late threshold, type-specific parameters;
--   - `job_run`                one row per execution, written by the wrapper: outcome SUCCEEDED/FAILED/MISSED with a
--                              late flag - NO payload, NO tenant id, NO per-tenant count (ADR-012 section 3);
--   - `job_alert`              one row per incident, unique on (job_type, scheduled_for, kind MISSED/FAILED), so the
--                              same incident never alerts twice (ADR-012 section 6);
--   - `operator_credential`    the agent's machine credential binding (issuer, client id, status, scopes - ADR-012
--                              Decision 5); the platform's first no-tenant principal.
-- Like console_session (V2_4_1 precedent): there is no tenant column to filter by, so no row-level security - access
-- by grant only: the application role reads and writes, PUBLIC holds nothing, and the tenant-table grant gates on
-- schema public stay as they are. These five tables ARE the exemption register's entries for ADR-012 section 4
-- (adding a table to that list needs an ADR); each has the no-tenant_id test (SchedulerSchemaContainerTest).
--
-- Rollback (Flyway Community has no undo; run as the migration owner, then delete this version from
-- flyway_schema_history): DROP SCHEMA scheduler CASCADE;

CREATE SCHEMA scheduler;
REVOKE ALL ON SCHEMA scheduler FROM PUBLIC;
GRANT USAGE ON SCHEMA scheduler TO ipaas_app;

-- db-scheduler 16.12.0's own table shape (upstream postgresql DDL of that release; task_data is the job data, never
-- a tenant id - ADR-012 section 4)
CREATE TABLE scheduler.scheduled_tasks (
    task_name             text                         NOT NULL,
    task_instance         text                         NOT NULL,
    task_data             bytea,
    execution_time        timestamp with time zone     NOT NULL,
    picked                boolean                      NOT NULL,
    picked_by             text,
    last_success          timestamp with time zone,
    last_failure          timestamp with time zone,
    consecutive_failures  integer,
    last_heartbeat        timestamp with time zone,
    version               bigint                       NOT NULL,
    priority              smallint,
    CONSTRAINT scheduled_tasks_pk PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX execution_time_idx ON scheduler.scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduler.scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduler.scheduled_tasks (priority DESC, execution_time ASC);

CREATE TABLE scheduler.job_definition (
    job_type          varchar(50)                    NOT NULL,
    schedule_kind     varchar(20)                    NOT NULL          CHECK (schedule_kind IN ('FIXED_DELAY', 'CRON')),
    schedule          varchar(100)                   NOT NULL,
    enabled           boolean                        NOT NULL          DEFAULT true,
    paused            boolean                        NOT NULL          DEFAULT false,
    paused_by         varchar(100),
    paused_at         timestamp with time zone,
    max_run_seconds   integer                        NOT NULL          DEFAULT 3600,
    late_threshold_seconds integer                   NOT NULL          DEFAULT 300,
    parameters        jsonb,
    CONSTRAINT job_definition_pk PRIMARY KEY (job_type)
);

CREATE TABLE scheduler.job_run (
    id                uuid                           NOT NULL          DEFAULT gen_random_uuid(),
    job_type          varchar(50)                    NOT NULL,
    scheduled_for     timestamp with time zone,
    started           timestamp with time zone,
    finished          timestamp with time zone,
    instance          varchar(100),
    outcome           varchar(10)                    NOT NULL          CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'MISSED')),
    is_late           boolean                        NOT NULL          DEFAULT false,
    error_class       varchar(200),
    counts            jsonb,
    CONSTRAINT job_run_pk PRIMARY KEY (id),
    CONSTRAINT job_run_type_fk FOREIGN KEY (job_type) REFERENCES scheduler.job_definition (job_type)
);

CREATE INDEX job_run_search_idx ON scheduler.job_run (job_type, outcome, started);

CREATE TABLE scheduler.job_alert (
    id                uuid                           NOT NULL          DEFAULT gen_random_uuid(),
    job_type          varchar(50)                    NOT NULL,
    scheduled_for     timestamp with time zone       NOT NULL,
    kind              varchar(10)                    NOT NULL          CHECK (kind IN ('MISSED', 'FAILED')),
    status            varchar(12)                    NOT NULL          DEFAULT 'OPEN'
                      CHECK (status IN ('OPEN', 'ACKNOWLEDGED')),
    acknowledged_by   varchar(100),
    acknowledged_at   timestamp with time zone,
    error_class       varchar(200),
    message           varchar(500),
    raised_at         timestamp with time zone       NOT NULL          DEFAULT now(),
    CONSTRAINT job_alert_pk PRIMARY KEY (id),
    -- one alert per incident, never twice (ADR-012 section 6)
    CONSTRAINT job_alert_incident_uq UNIQUE (job_type, scheduled_for, kind)
);

CREATE TABLE scheduler.operator_credential (
    issuer            varchar(200)                   NOT NULL,
    client_id         varchar(200)                   NOT NULL,
    status            varchar(20)                    NOT NULL          DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE', 'REVOKED')),
    scopes            varchar(500)                   NOT NULL          DEFAULT 'platform-api',
    created_at        timestamp with time zone       NOT NULL          DEFAULT now(),
    revoked_at        timestamp with time zone,
    CONSTRAINT operator_credential_pk PRIMARY KEY (issuer, client_id)
);

REVOKE ALL ON scheduler.scheduled_tasks, scheduler.job_definition, scheduler.job_run, scheduler.job_alert,
            scheduler.operator_credential FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE
    ON scheduler.scheduled_tasks, scheduler.job_definition, scheduler.job_run, scheduler.job_alert,
       scheduler.operator_credential TO ipaas_app;