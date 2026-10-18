---
type: ADR
title: "ADR-016 - Retention enforcement: a fifth job type for audit partition maintenance run as the maintenance role; metrics and trace retention in the adopted stores' configuration; log retention enforced by the deployment's log collector"
status: "Decisions 1-2 Accepted (team, 2026-10-17) - Dipesh may reverse; Decision 3 Decided: A - decided by Dipesh 2026-10-17 (logs to stdout only, 30 days enforced by the deployment's log collector, a deployment-checklist item); B (Loki, 720h) rejected for now"
date: 2026-10-17
author: "mahesh-architect (ledger row 327, E12-F2-S3, questions raised by lane T118)"
project: ipass/integration-platform
provenance: new-decision
related:
  - ADR-011-targets-contracts-bulk-ordering.md
  - ADR-012-job-scheduler.md
  - ADR-014-message-store-and-delivery-uniqueness.md
  - ../architecture.md
---

# ADR-016 - Retention enforcement

Conventions and grades as in [ADR-012](ADR-012-job-scheduler.md): paths starting `ws/` are in the hub
project workspace `_personal/_project_ipass/integration-platform/`; paths starting `t118/` are in lane
worktree `D:\_Working_tree\integration-platform-t118` (branch `worktree-sanjay-developer-integration-platform-t118`,
commit `7c6f7de`, not yet on main); other paths are in this repository on this ADR's branch (main `ae20804` plus this change - line numbers in ADR-012 and architecture.md include the amendments made with it).
**[Verified]** = read in the cited file this pass; **[Inferred]** = reasoned, not measured; **[TBD]** =
needs a measurement or a fact only Dipesh holds.

This ADR **amends ADR-012 Decision 2** (the closed job-type register). As the owner of a register
must, it lists every dependent list that changes (section "Register re-publication").

## Context

- E12-F2-S3 asks that logs be kept 30 days, metrics 13 months, traces 7 days and audit 7 years,
  audit purged by partition drop; scenario 1 reads "the logging, metrics, tracing and audit
  configuration" in a configuration test; scenario 2 drops a 7-year-1-month partition and keeps a
  6-year-11-month one (`ws/_specs/backlog.md:3320-3341`). The plan names these as figures the
  logging phase owns (`ws/_specs/project-plan.md:119`, `:183`); a figure moves between phases only
  by Dipesh (`ws/_specs/project-plan.md:155`). [Verified]
- Lane T118 added `public.audit_purge_expired()`: no argument, fixed 7-year cutoff, drops every
  month partition of `audit_event` whose upper bound is at or before `now() - 7 years`; it is
  `SECURITY DEFINER` and `EXECUTE` is granted to **`ipaas_app`**
  (`t118/platform-app/src/main/resources/db/migration/V2_4_1250__audit_retention.sql:15-39`).
  The lane also added `RetentionPolicy.AGREED` with the four figures and a `ponytail:` note that
  the log, metric and trace figures are declared, not enforced
  (`t118/platform-app/src/main/java/io/integrationplatform/app/retention/RetentionPolicy.java:13-20`). [Verified]
- **A second, unstated retention gap - partition creation.** `audit_event` has no DEFAULT partition and
  its migration pre-creates months from last month to 12 months ahead only; "monthly maintenance
  beyond it moves onto the E14 scheduler" (`t118/platform-app/src/main/resources/db/migration/V2_2_1220__audit_event.sql:7-10`,
  `:28-44`). Nothing creates later months. About 12 months after the migration runs, every audit
  insert fails with "no partition of relation found" - and with it every action that must land an
  audit event (E12-C4). Purge and creation are one maintenance concern. [Verified] window; [Inferred] failure.
- Nothing calls the purge. The register is closed at four types (`documents/adr/ADR-012-job-scheduler.md:123-126`)
  and the code mirrors it: four constants and four task beans
  (`platform-app/src/main/java/io/integrationplatform/app/scheduler/SchedulerJobTasks.java:24-54`), four
  seed rows (`platform-config/src/main/resources/db/migration/V2_2_1420__job_definition_seed.sql:15-22`), and
  a wiring test that expects exactly those four names
  (`platform-app/src/test/java/io/integrationplatform/app/scheduler/SchedulerSpringWiringContainerTest.java:70-71`).
  The wrapper resolves a handler by its `type()` and skips a type without one
  (`platform-app/src/main/java/io/integrationplatform/app/scheduler/JobRunWrapper.java:25-34`). [Verified]
- **The lane's grant conflicts with control T6:** "The application role has no `BYPASSRLS` and no DDL;
  partition maintenance runs as a separate role" (`documents/architecture.md:153`), restated by
  ADR-011 Decision 8 ("never as the application role", `documents/adr/ADR-011-targets-contracts-bulk-ordering.md:291-292`)
  and ADR-012 ("the drop runs as the maintenance role over a second data source configured only by
  environment variable names", `documents/adr/ADR-012-job-scheduler.md:175-177`). A definer function
  executable by `ipaas_app` passes a grant-inspection test while giving the application role a DROP
  path - a control whose evidence the design discards. [Verified] texts; [Inferred] conflict.
- The maintenance role does not exist in code yet: the only role migration creates `ipaas_app`
  (`platform-tenancy/src/main/resources/db/migration/V1_3_1__restricted_application_role.sql:16`); a search
  of `*.sql`, `*.java` and configuration for a maintenance role or data source finds comments only
  (e.g. `platform-routing/src/main/resources/db/migration/V2_1_810__message_partitioned.sql:6`). The payload
  purge (T-142) needs the same role (`ws/_specs/backlog.md:3644`, `:3683`). [Verified]
- **Observability stores.** The plan adopts OpenTelemetry + Micrometer to Tempo/Grafana and
  Prometheus + Grafana, internal only (`ws/_specs/project-plan.md:89`, `:94`); the deployment view lists
  Prometheus, Grafana, Tempo (`documents/architecture.md:189`). The development compose file starts
  PostgreSQL, Keycloak, Kafka and Kafbat only (`compose/compose.yaml:13`, `:41`, `:72`, `:104`); the stores are
  "planned" (`documents/architecture.md:297-298`). **No log store is adopted** anywhere in the plan;
  Elasticsearch/OpenSearch are out of scope (`ws/_specs/project-plan.md:231`); infrastructure is
  self-hosted (`:194`). Logs are structured JSON lines (E12-C2, `ws/_specs/backlog.md:3348`). [Verified]

### Premise decomposition - "nothing enforces the figures"

| Figure | Store today | Store adopted | What would enforce it | Whose call |
|---|---|---|---|---|
| Audit 7 years | `audit_event` (lane) | PostgreSQL | a scheduled partition drop **and** creation | team (this ADR, Decision 1) |
| Metrics 13 months | none | Prometheus | its TSDB retention flag | team (Decision 2) - store already adopted |
| Traces 7 days | none | Tempo | its compactor block retention | team (Decision 2) - store already adopted |
| Logs 30 days | stdout only | **none** | a log store's retention, or the host's | Dipesh (Decision 3) - decided 2026-10-17: the deployment's log collector |

## Options - question 1: how the audit purge is scheduled

| Option | Complexity | Risk | Cost | Reversibility | Verdict |
|---|---|---|---|---|---|
| **A. Amend the register: a fifth type `AUDIT_PARTITION_MAINTENANCE`** (creates ahead and drops expired), run as the maintenance role | Low: one handler, one constant, one seed row, one wiring-test line | Low: one mechanism, own run history and alerts | ~1 S task + the shared maintenance-role seam | One revert; job row can be paused | **Chosen** |
| B. Fold into `PAYLOAD_PURGE` | Low | Medium: couples two data owners' failures into one alert; that handler belongs in `platform-delivery` (`documents/adr/ADR-012-job-scheduler.md:134-136`), which rows 278/323 own | Low | Easy | Rejected |
| C. `pg_cron`, an OS cron or a container cron calling `psql` | Medium: a new extension or runtime | High: outside `job_run`/`job_alert`, invisible to the agent API and to missed-run detection; breaks "one mechanism runs all background work" (`documents/adr/ADR-012-job-scheduler.md:293`) | New infrastructure | Medium | Rejected |
| D. Purge only, monthly cadence (the lane's literal ask) | Lowest | High: leaves the partition-creation cliff; a failed run waits a month | Lowest | Easy | Rejected - solves half the concern |

Who runs the DDL:

| Option | Verdict |
|---|---|
| P1. Keep `EXECUTE` to `ipaas_app` and amend T6 | Rejected: the internet-facing role gains a DROP path; T-142 needs the maintenance role anyway, so P1 saves no secret, only defers it |
| **P2. Narrow, no-argument definer functions owned by the migration owner, `EXECUTE` to `ipaas_maintenance` only; the handler calls them over the maintenance data source** | **Chosen**: T6 holds as written; the maintenance role holds no raw DDL, only two fixed functions |
| P3. The maintenance role owns `audit_event` and issues raw DDL | Rejected: broad power over the ledger (could also DELETE rows) |

## Options - question 2: log, metric and trace retention in Release 1

| Option | Metrics/traces | Logs | Cost | Verdict |
|---|---|---|---|---|
| D1. Declare only (today) | unenforced | unenforced | 0 | Rejected for metrics/traces: the stores are already adopted, so declaring without them leaves 2 owned figures unproven |
| **D2. Ship the adopted stores' configuration with the figures, in an opt-in compose profile, read by the configuration test** | enforced in config, tested | n/a | 2 containers, off by default | **Chosen for metrics and traces** |
| D3. Add a log store (Grafana Loki + a collector) in the same profile, retention 30 days | - | enforced in config, tested | 2 more containers; a component the plan never adopted; AGPL, internal only (Grafana precedent) | **Option B** - rejected for now (Dipesh, 2026-10-17; Decision 3) |
| D4. The deployment environment's log platform keeps logs 30 days; the platform writes no log file | - | verified at deployment | 0 build cost | **Option A - chosen for logs** (Dipesh, 2026-10-17; Decision 3) |

## Decision

### 1. `AUDIT_PARTITION_MAINTENANCE` - the fifth job type (Accepted, team, 2026-10-17)

- **Register (ADR-012 Decision 2) becomes five:** `DELIVERY_RETRY`, `PAYLOAD_PURGE`, `DAILY_REPORT`,
  `CREDENTIAL_CLEANUP`, **`AUDIT_PARTITION_MAINTENANCE`**. The name covers creation and drop, and the
  platform audit stream of ADR-012 Decision 5 (`documents/adr/ADR-012-job-scheduler.md:206-210`) joins this
  same job when its table lands - no sixth type. [Inferred]
- **Body, in this order, each step its own transaction on the maintenance data source:**
  1. `select public.audit_ensure_partitions()` - creates any missing month partition of `audit_event`
     from the current month through 12 months ahead (the migration's window,
     `t118/.../V2_2_1220__audit_event.sql:33-36`), `CREATE TABLE IF NOT EXISTS ... PARTITION OF`, returns
     how many it created. No argument.
  2. `select public.audit_purge_expired()` - the lane's function, unchanged in logic.
  Creation runs first and commits on its own, so a failed purge never blocks next year's partitions.
- **Privilege (P2):** both functions are `SECURITY DEFINER`, owned by the migration owner, fixed
  `search_path`, no argument, and carry `SET lock_timeout = '5s'` (dropping a partition takes an
  `ACCESS EXCLUSIVE` lock on the parent; a busy ledger makes the run fail and alert, never stall
  writers). `REVOKE ALL ... FROM PUBLIC`; `GRANT EXECUTE` **to `ipaas_maintenance` only** -
  `ipaas_app` holds none. They are not T5 functions: they neither resolve nor return a tenant and
  the application role cannot run them; T5 stays at four. [Inferred]
- **Tenancy:** platform-wide, binds no tenant, reads only the catalog; nothing in job data is read
  (ADR-012 Decision 4). [Inferred]
- **Cadence (seed row):** `CRON '0 30 3 * * *'` UTC, enabled, not paused, `max_run_seconds` 900,
  `late_threshold_seconds` 600. Daily, not monthly: both steps are idempotent and are no-ops on most
  days, a failure raises one `FAILED` alert within 24 hours instead of a month, and 03:30 starts after
  `PAYLOAD_PURGE` (02:00, maximum 3600 s, `V2_2_1420__job_definition_seed.sql:19`) can have finished.
  [Inferred]
- **The maintenance role seam (shared with T-142, built once):** a migration creating
  `ipaas_maintenance` `NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION` (the shape of
  `V1_3_1__restricted_application_role.sql:16`), placed beside it in `platform-tenancy`; a named, non-primary
  maintenance `DataSource` bean in `platform-app` whose URL, user and password come from environment
  variable names only (`documents/adr/ADR-012-job-scheduler.md:175-177`, `:298`); the existing data source
  stays primary. If the maintenance data source is not configured, the handler throws a named error, so
  the run is `FAILED` and alerts - retention silently not running is an incident. [Inferred]

### 2. Metrics and trace retention live in the adopted stores' configuration (Accepted, team, 2026-10-17)

- A compose profile `observability` (services not started by a plain `docker compose up`, so the
  build PC's container budget is unchanged) adds **Prometheus** with `--storage.tsdb.retention.time=397d`
  and **Tempo** with `compactor.compaction.block_retention: 168h`. 397 days is the longest span of 13
  consecutive months (eight 31-day months, a 29-day February), so "13 months" is never cut short.
  Image tags pinned, as every image (`documents/architecture.md:192`). Grafana is not needed for
  retention and is left to whichever task wires dashboards. [Inferred]
- The E12-F2-S3 configuration test reads `RetentionPolicy.AGREED`, the Prometheus flag in
  `compose/compose.yaml` and the Tempo file, and fails on any mismatch. Wiring the scrape endpoint and
  the trace exporter stays with the tasks that own them (E14 T-062 metrics, E12 T-064 tracing); this
  decision fixes only how long the stores keep data. [Inferred]
- Production applies the same two values; the deployment checklist carries them (the reference
  configuration is the source). [Inferred]

### 3. Log retention - Decided: A (decided by Dipesh, 2026-10-17)

**Decided: A** - decided by Dipesh on 2026-10-17 (his reply "A", ledger row 327). Option A is D4 in the
options table above; option B is D3.

- In Release 1 the platform writes application logs to **stdout only**; no log store is added to the
  platform and no log file is written. Conformance: no file appender and no `logging.file.*` in any
  profile (test 9).
- The **30-day figure is enforced by the log collector of the deployment** the platform runs in and is
  **verified at deployment time**: it becomes a deployment-checklist item and joins the "verified at
  deployment" list (`ws/_specs/project-plan.md:186`). Moving the figure out of its phase is Dipesh's
  call (`ws/_specs/project-plan.md:155`); this decision makes it.
- `RetentionPolicy.AGREED` keeps 30 days for logs as the declared figure that the deployment check
  compares against; nothing in the platform enforces it. [Inferred]

**Rejected alternative - B (D3), rejected for now:** Grafana Loki + a collector in the `observability`
profile with `retention_period: 720h`, read by the same configuration test. Reason: no extra store to
run before go-live, and no log store in the app stack.

## Register re-publication (every dependent list, in the change that lands Decision 1)

| List | Where | Change |
|---|---|---|
| ADR-012 register | `documents/adr/ADR-012-job-scheduler.md:123-126` | Update block pointing here (added with this ADR) |
| Architecture controls | `documents/architecture.md:155-176` | partition-maintenance functions named outside T5 (added with this ADR) |
| ADR index and registers | `documents/adr/README.md` | row ADR-016; job-types register row cites ADR-016 (added with this ADR) |
| Handler interface javadoc | `platform-domain/src/main/java/io/integrationplatform/domain/scheduler/JobHandler.java:6-7` | add the fifth name - developer |
| Task beans | `platform-app/src/main/java/io/integrationplatform/app/scheduler/SchedulerJobTasks.java:24-54` | constant + bean - developer |
| Seed rows | **new** migration in `platform-config` (never edit `V2_2_1420`: Flyway checksums applied files) | one row, `on conflict do nothing` - developer |
| Wiring test | `platform-app/src/test/java/io/integrationplatform/app/scheduler/SchedulerSpringWiringContainerTest.java:70-71` | expected names include the fifth - developer |
| Proof-task javadoc | `platform-app/src/test/java/io/integrationplatform/scheduler/SchedulerProofTasks.java:17-18` | list five - developer |
| Schema comment | `platform-config/src/main/resources/db/migration/V2_2_1400__scheduler_schema.sql:6-7` | **not edited** (applied file); the new seed migration's header records the amendment |

## Conformance tests (each written failing first)

1. `audit_purge_expired()` with partitions aged 6 years 11 months and 7 years 1 month drops exactly 1
   and keeps the other (backlog scenario 2).
2. `audit_ensure_partitions()` on a table whose window ends next month creates exactly the missing
   months to +12; a second run creates 0.
3. Grant inspection: `ipaas_app` has 0 `EXECUTE` on either function; `ipaas_maintenance` has `EXECUTE`
   on both and 0 table privileges on `audit_event`.
4. Calling either function as `ipaas_app` fails with SQLSTATE 42501.
5. The scheduler wiring registers exactly 5 task names; one `AUDIT_PARTITION_MAINTENANCE` run writes 1
   `job_run` row.
6. With the parent locked by another session past 5 s, the run is `FAILED` and writes exactly 1
   `FAILED` alert.
7. Maintenance data source not configured: the run is `FAILED` (not skipped) with the named error class.
8. Configuration test: Prometheus retention = 397d, Tempo block retention = 168h, audit = 7 years,
   each equal to `RetentionPolicy.AGREED`.
9. Log output (Decision 3): 0 file appenders in the logging configuration and 0 `logging.file.*`
   properties in any profile; logs go to stdout only.

## Process - one maintenance run, with failure paths

| Step | What happens | Failure - who finds out |
|---|---|---|
| 1 | db-scheduler claims the daily execution (ADR-012 section 3) | not claimed within 600 s: `MISSED` alert - agent API, Prometheus |
| 2 | Handler opens the maintenance data source | not configured or login refused: `FAILED` + 1 alert |
| 3 | `audit_ensure_partitions()` commits | lock timeout or error: `FAILED` + 1 alert; retried next day; 12 months of headroom remain |
| 4 | `audit_purge_expired()` commits | lock timeout or error: `FAILED` + 1 alert; partitions created in step 3 stay |
| 5 | Wrapper writes `job_run` `SUCCEEDED` with created and dropped counts in the log line | - |

External calls: none; PostgreSQL only (ADR-012 section 8 unchanged).

## Consequences

- One mechanism still runs all background work; the audit ledger can no longer run out of partitions.
- The maintenance role and its data source become a Release 1 prerequisite for retention, built once
  for both purges; production carries the maintenance secret ADR-012 already accepted
  (`documents/adr/ADR-012-job-scheduler.md:298-299`).
- Lane T118's migration changes before it lands: `EXECUTE` moves from `ipaas_app` to `ipaas_maintenance`,
  `lock_timeout` is added, and `audit_ensure_partitions()` joins it; its migration test changes with it.
- Metrics and trace retention are proven in configuration, not by waiting 13 months.

**What we give up:** the audit purge cannot run until the maintenance-role seam exists (a sequencing
dependency the lane's `ipaas_app` grant avoided); a fifth register member and its re-publication;
production retention for metrics and traces still depends on the deployed stores using the
reference values; and in Release 1 under Decision 3 (A) the platform itself proves nothing about log
retention - the 30 days rest on the deployment's log collector and its checklist item.

## Revisit if

- A legal hold or regulator asks to keep specific audit partitions past 7 years (the purge then needs
  a hold list, and the cutoff stops being fixed).
- The platform audit stream (ADR-012 Decision 5) lands with a partition grain other than month.
- The deployment has no log collector that can keep 30 days, or a log store joins the app stack -
  then option B (D3, Loki at 720h) is reopened with Dipesh.
- A Prometheus at 397 days exceeds its disk budget once real series exist [TBD - measure at the
  Release 1 load test]; then remote storage or downsampling is designed.
