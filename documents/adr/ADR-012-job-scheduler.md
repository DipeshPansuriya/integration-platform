---
type: ADR
title: "ADR-012 - Job scheduler: db-scheduler with job definitions as our rows, platform-scoped jobs, a no-tenant operator-agent credential, alerts as durable rows plus Prometheus"
status: "Accepted (approved by Dipesh 2026-10-12, as recommended) - tenant admins hold no job operation; the agent polls alerts every 5 minutes, no push channel in Release 1; the no-tenant machine credential proceeds with its attack suite added to T-147. Amendment 2026-10-27 (conservative PAYLOAD_PURGE guard, guard and drop in the database as the table owner, V2_4_1450 / V2_4_1460) Ratified by mahesh-architect 2026-10-27 - Dipesh may reverse"
approved: "Dipesh, 2026-10-12 (hub memory m-f1fcfc)"
date: 2026-10-11
author: "mahesh-architect (ledger row 307 under row 278, task T-054)"
project: ipass/integration-platform
provenance: new-decision
related:
  - ADR-007-identity.md
  - ADR-009-tenant-directory-rls.md
  - ADR-010-camel-routing-core.md
  - ADR-011-targets-contracts-bulk-ordering.md
  - ADR-005-messaging.md
  - ../architecture.md
---

# ADR-012 - Job scheduler: db-scheduler with job definitions as our rows, platform-scoped jobs, a no-tenant operator-agent credential, alerts as durable rows plus Prometheus

Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`. Paths without a prefix are in this repository.
Every statement carries a grade: **[Verified]** (read in the cited file this pass), **[Inferred]**
(a reasoned conclusion, not yet measured) or **[TBD]** (needs a measurement or a fact only Dipesh
holds).

> **Approved by Dipesh 2026-10-12** (hub memory m-f1fcfc), as recommended. His three decision
> points (former Open items 1-3): (1) **tenant admins cannot act on platform jobs** in Release 1 -
> the agent credential only; (2) **the alert-reading agent polls the alert API every 5 minutes;
> no push channel in Release 1**; (3) **the no-tenant machine credential for agents is approved**,
> and its attack suite is added to the Release 1 security review (T-147). The amendments this ADR
> requires are made in the same change: ADR-007 Decision 5 (caller kinds) and architecture.md
> sections 3 and 5.

## Context

- The phase is done when ADR-012 is approved before code; delivery retry, payload purge (partition
  drop) and the daily report run as configuration rows on db-scheduler; jobs fire on time; two
  instances claim different due jobs with no local state; each job takes its tenant from the
  RLS-protected row it processes; every run's outcome is recorded and searchable; and an AI agent
  with its own machine credential lists, runs, pauses and inspects jobs through the admin API, is
  alerted when a job misses its schedule or fails, with every action in the activity log
  (`ws/_specs/project-plan.md:119`). [Verified]
- The backlog says this ADR "fixes db-scheduler (over JobRunr and Quartz), job definitions as our
  rows, no local state, the agent's machine credential and scope, and the alert channel", and no
  E14-F2 or E14-F3 task starts before Dipesh approves it (`ws/_specs/backlog.md:3544-3554`).
  [Verified]
- The plan adopts `db-scheduler-spring-boot-starter` 16.12.0, "job definitions are OUR
  configuration rows; PostgreSQL-only, no local state" (`ws/_specs/project-plan.md:90`). The R&D
  scan: db-scheduler Apache-2.0 with a Spring Boot 4 starter and one task table; Quartz 2.5.2 the
  fallback; JobRunr's LGPL core and paid dashboard rejected (`ws/_reports/2026-09-30-oss-rnd.md:77-79`,
  `:188-191`). [Verified]
- Jobs already fixed by other records: the delivery-retry job, which also runs the reconciler (no
  job of its own); credential clean-up as its own job (ADR-010 section 4,
  `documents/adr/ADR-010-camel-routing-core.md:165-170`; ADR-007 Decision 3,
  `documents/adr/ADR-007-identity.md:131-137`); payload purge by partition drop with the purge guard,
  run as a separate maintenance role (ADR-011 Decision 8,
  `documents/adr/ADR-011-targets-contracts-bulk-ordering.md:288-296`). [Verified]
- Scanning jobs loop tenants returned by `ipaas_active_tenants()` and bind one tenant per transaction;
  no application role bypasses row-level security (`documents/adr/ADR-011-targets-contracts-bulk-ordering.md:288-290`).
  [Verified]
- A not-ready instance runs no delivery-retry pass ("its scheduler picks up no job")
  (`documents/adr/ADR-010-camel-routing-core.md:333-340`). [Verified]
- Today the delivery-retry trigger is a plain class with `runOnce()` that loops active tenants,
  one transaction per tenant, `FOR UPDATE SKIP LOCKED` through its store - "no scheduler, no queue"
  (`platform-delivery/src/main/java/io/integrationplatform/delivery/internal/trigger/DeliveryRetryTrigger.java:15-27`,
  `:56-68`). No `@Scheduled` or `@EnableScheduling` exists anywhere in the repository (search this
  pass). [Verified]
- Callers the API knows today are three kinds: a human with a role, any human, a source system's
  machine credential (`platform-tenancy/src/main/java/io/integrationplatform/tenancy/RolePolicy.java:40-47`).
  There is no operator or agent caller. [Verified]
- Platform-operator (no-tenant) login was deferred by Dipesh to an operations phase "and gets its
  own security design and attack suite then"; ADR-009 is revisited when it is designed
  (`documents/adr/ADR-009-tenant-directory-rls.md:84-86`, `:100`). [Verified]
- Precedent for platform data without a tenant: the console session tables live in their own schema,
  not `public`, with no tenant column and no row-level security, access by grant only
  (`platform-tenancy/src/main/resources/db/migration/V2_4_1__console_session.sql:5-9`). [Verified]

## Options - the scheduler engine

| Criterion | Weight | db-scheduler 16.12.0 + our rows | Quartz 2.5.2 JDBC cluster | JobRunr 8 (open-source core) | Spring `@Scheduled` + ShedLock | Extend our own polling trigger |
|---|---|---|---|---|---|---|
| Cluster-safe claiming, no local state (E14-C7) | 25 | 5 | 5 | 4 | 3 | 4 |
| Licence fit for a hosted proprietary product | 15 | 5 | 5 | 2 | 5 | 5 |
| Job definitions as our rows (schedules read from the database) | 15 | 4 | 3 | 3 | 2 | 5 |
| Run history, restart catch-up, missed-run handling (E14-C6, C9) | 15 | 4 | 4 | 5 | 2 | 2 |
| PostgreSQL only, no new infrastructure | 10 | 5 | 5 | 5 | 5 | 5 |
| Code we must build and own | 10 | 4 | 3 | 4 | 3 | 1 |
| Maturity and Spring Boot 4 support | 10 | 4 | 4 | 4 | 5 | 3 |
| **Weighted total (of 500)** | 100 | **450** | 425 | 380 | 340 | 370 |

Grades: licence and Spring Boot 4 rows [Verified] against `ws/_reports/2026-09-30-oss-rnd.md:77-79`;
"our own trigger" effort [Verified] against the scan's estimate of 2-3 weeks saved (`:77`); the
weights and every other score [Inferred] (team judgement). Quartz scores lower on "our rows" because
its own trigger tables would be a second source of truth beside `job_definition` [Inferred].

## Options - how the agent reaches the scheduler

| Option | What it means | Verdict | Grade |
|---|---|---|---|
| **A. No-tenant operator-agent principal** | A dedicated machine credential per agent, bound to platform scope only; it binds **no tenant**, so every tenant-owned query errors under row-level security | **Chosen** | [Inferred] |
| B. Tenant-bound agent credential | Reuse the source-credential path; the agent acts inside one tenant | Rejected: the jobs are platform-wide (purge drops a day's partition for every tenant; retry loops every tenant), so a tenant-bound pause stops every tenant - cross-tenant blast radius | [Inferred] from `documents/adr/ADR-011-targets-contracts-bulk-ordering.md:288-296` |
| C. The agent drives a human's console session | Shared credential, no attribution | Rejected | [Inferred] |

## Options - the alert channel

| Option | Verdict | Grade |
|---|---|---|
| **Durable alert rows read through the admin API, plus Prometheus alert rules** | **Chosen**: no new outbound call, no egress rule, no secret; works when the agent is offline (it reads on its next poll); Prometheus covers the case no instance can report (all down) | [Inferred]; Prometheus is already in the deployment (`documents/architecture.md:160`) [Verified] |
| Push to the agent (webhook, mail, chat) | Deferred: a new external call with all five controls and a destination check, and a channel only Dipesh can name | [Inferred] |
| Logs only | Rejected: not "alerted ... once per incident" (`ws/_specs/backlog.md:3727`) | [Verified] |

## Decision (Accepted 2026-10-12)

### 1. Engine

**db-scheduler** via `db-scheduler-spring-boot-starter` 16.12.0 (Apache-2.0)
(`ws/_specs/project-plan.md:90`). The version is re-checked for currency when T-056 starts.
[Verified] choice; [TBD] currency check.

### 2. Job types are code; job definitions are our rows

- **Job types (closed register, owned by this ADR):** `DELIVERY_RETRY` (with the reconciler sweep
  inside it), `PAYLOAD_PURGE`, `DAILY_REPORT`, `CREDENTIAL_CLEANUP`. Adding a type is code plus an
  amendment of this register. [Verified] for the four jobs (sources in Context); [Inferred] for the
  register rule.
- **`job_definition`** (one row per job type): schedule (fixed delay or cron), enabled, paused (with
  who and when), maximum run time, late threshold, and type-specific parameters validated per type.
  A row **never** holds a tenant id. A change to a row is audited and takes effect at the next
  schedule sync; db-scheduler's recurring tasks with a schedule stored in the database carry it
  (T-056 confirms the exact API). [Inferred]
- **Placement:** the rows and the admin API live in `platform-config` (it owns configuration,
  `documents/architecture.md:48`); the runtime wiring lives in `platform-app` (it owns scheduler
  jobs, `documents/architecture.md:55`); each job's body stays in the module that owns its data
  (delivery: retry and purge; ledger: daily report; config: credential clean-up) behind one plain
  job-handler interface with no Spring type, so no module depends on `platform-app`. [Verified]
  module ownership; [Inferred] interface placement.
- **Update 2026-10-17 ([ADR-016](ADR-016-retention-enforcement.md), team - Dipesh may reverse):** the
  register has **five** types - `AUDIT_PARTITION_MAINTENANCE` added (creates `audit_event` month
  partitions 12 months ahead and drops those older than 7 years, as the maintenance role, daily at
  03:30 UTC). ADR-016 lists every dependent list re-published with it. The four-type text above is
  kept as the record of the original decision.

### 3. No local state

- db-scheduler's task table is created by a Flyway migration (Flyway owns the schema), claimed by
  row locks so two instances never run one execution; heartbeats let a live instance pick up an
  execution whose instance died. [Inferred - T-058 proves it]
- **No `@Scheduled`, `@EnableScheduling` or in-memory timer anywhere**; an architecture test fails
  the build on one (none exists today - Context). [Verified] today; [Inferred] test.
- **`job_run`**: one row per execution, written by our wrapper: job type, scheduled for, started,
  finished, instance, outcome (`SUCCEEDED`, `FAILED`, `MISSED`) with a late flag, aggregate counts,
  error class. It holds **no payload, no tenant id, no per-tenant count** - so reading it reveals
  nothing about any tenant. Per-tenant detail stays in the tenant-scoped delivery events and audit
  events. [Inferred]

### 4. Tenancy - jobs are platform-scoped, the work inside them is tenant-scoped

- The scheduler tables (`scheduled_tasks`, `job_definition`, `job_run`, `job_alert`) and the agent
  credential table hold no tenant data. Following the console-session precedent they live in their
  **own schema** (`scheduler`), not `public`, with no row-level security and access by grant only:
  the application role reads and writes them, PUBLIC holds nothing. [Verified] precedent
  (`platform-tenancy/src/main/resources/db/migration/V2_4_1__console_session.sql:5-9`); [Inferred]
  application.
- **Exemption register:** the tenant-filter rule (control T2) and the RLS suite (T3) treat exactly
  the tables of this section and the console-session schema as platform-scoped; adding a table to
  that list needs an ADR. Each listed table has a test that it has no `tenant_id` column.
  [Verified] controls `documents/architecture.md:143-144`; [Inferred] register.
- **Inside a job, tenants come only from the database:** scanning jobs loop
  `ipaas_active_tenants()` and bind one tenant per transaction; work on a known row resolves its
  tenant with `ipaas_work_tenant` (ADR-011 Decision 8). **No fifth definer function** is added, so
  control T5 stays at four (`documents/architecture.md:146`). The task data db-scheduler stores
  never carries a tenant id (test: a planted tenant id in job data is ignored -
  `ws/_specs/backlog.md:3650-3653`). [Verified] sources; [Inferred] task-data rule.
- **Purge:** the guard count runs as the application role, looping tenants under RLS and summing
  `DEAD_LETTERED` or non-final deliveries that reference the partition; the drop runs as the
  maintenance role over a second data source configured only by environment variable names. The
  guard-then-drop gap is safe: a delivery that counted as final cannot become non-final, and the
  only path back (replay) starts from `DEAD_LETTERED`, which the guard already counts.
  [Verified] guard and roles `documents/adr/ADR-011-targets-contracts-bulk-ordering.md:291-296`;
  [Inferred] the race argument. *(Superseded as built by Amendment 2026-10-27 below: the guard
  and the drop run together inside one definer function as the table owner, so every tenant is
  counted - suspended ones included - and the guard also keeps a day holding any message that is
  not `ROUTED`. Kept here as the record of the original sketch.)*

### 5. The operator-agent credential and its scope

- **One confidential Keycloak client per agent** (client-credentials grant, 5-minute access
  tokens, default audience scope - the same mechanics as ADR-007 Decision 3,
  `documents/adr/ADR-007-identity.md:109-120`), bound through a row in `scheduler.operator_credential`
  (issuer, client id, status, scopes). Issue and revoke are platform-operator actions, revoke
  platform first, as in ADR-007 Decision 3. [Verified] mechanics; [Inferred] table.
- **A new caller kind `OPERATOR_AGENT`** in the role policy, with five operations: list jobs,
  inspect a job and its runs, run now, pause, resume - plus read and acknowledge alerts. It holds no
  human role and no source-system binding, so every existing operation refuses it, and every
  operation below refuses everyone else (source credentials, read-only users: 403,
  `ws/_specs/backlog.md:3730-3734`). [Verified] current caller kinds
  `platform-tenancy/src/main/java/io/integrationplatform/tenancy/RolePolicy.java:40-66`; [Inferred]
  new kind.
- **The agent never reads tenant data by construction:** it binds no tenant, and every tenant-owned
  policy compares to the tenant setting, which errors when empty
  (`platform-config/src/main/resources/db/migration/V2_2_3__config_flows.sql:73-75`;
  `documents/architecture.md:144`). Its API returns job, run and alert rows only - no payload, no
  configuration (`ws/_specs/backlog.md:3736-3740`). [Verified] policy shape; [Inferred] guarantee.
- **Tenant admins:** no job operation in Release 1 - a tenant admin pausing a platform-wide job would
  stop every tenant. This differs from the backlog's wording "only the agent credential and tenant
  admins reach the admin API" (`ws/_specs/backlog.md:3730`), which is reworded to "only the agent
  credential". [Verified] wording; **decided by Dipesh 2026-10-12** (m-f1fcfc).
- **Audit:** agent actions are written to an append-only **platform audit stream** (same writer,
  same append-only grants and 7-year retention as the tenant audit log of E12), not to a tenant's
  activity view, because they belong to no tenant and a tenant-scoped row with no tenant would fail
  the row-level-security check. This is how E14-C12 "every action in the activity log" is met.
  [Inferred - new team decision]
- This is the platform's **first no-tenant principal**. It is a machine credential, not the deferred
  operator **login**, but it crosses the same line, so it carries its own attack suite (below) and is
  routed to the security review: its attack suite is part of T-147. [Verified] deferral
  `documents/adr/ADR-009-tenant-directory-rls.md:84-86`; **approved by Dipesh 2026-10-12**
  (m-f1fcfc); the human operator login stays deferred.

### 6. Missed runs, failures and the alert channel

- **`job_alert`**: one row per incident, unique on (job type, scheduled-for, kind `MISSED` or
  `FAILED`), so the same incident never alerts twice (`ws/_specs/backlog.md:3727`, "once per
  incident"). The agent reads open alerts and acknowledges them through the admin API.
  [Verified] requirement; [Inferred] design.
- **Failure:** a run that throws or exceeds its maximum run time writes `job_run` `FAILED` and one
  `FAILED` alert. [Inferred]
- **Missed:** a due execution not started within the job's late threshold is `MISSED`. Detection
  does not depend on the scheduler's own polling: the alert read in the admin API also computes
  overdue executions live from the task table, and a metric of the oldest overdue execution is
  computed at scrape time. A run that fires after a restart is recorded once and flagged late
  (`ws/_specs/backlog.md:3587-3590`). [Verified] scenario; [Inferred] detection.
- **Prometheus:** counters for runs by outcome and the oldest-overdue gauge, with alert rules for
  humans, including the case where no instance is alive (an absent-metric rule) - the one case an
  in-application detector cannot report. [Inferred]
- **The alert reader:** an AI agent polls the alert API **every 5 minutes**; **no push channel in
  Release 1** (webhook, mail or chat stay deferred - see Revisit if). The agent's identity is a
  hub-side setting, not a platform rule. **Decided by Dipesh 2026-10-12** (m-f1fcfc). With a
  5-minute poll, the worst-case time from incident to an agent seeing it is 5 minutes plus the
  late threshold. [Verified] decision; [Inferred] worst case.

### 7. Readiness and the delivery-retry move

- On readiness DOWN an instance stops polling for executions (it claims none) and resumes on UP, as
  ADR-010 section 9 item 4 requires; skipping inside a claimed execution is not enough, because it
  would starve the other instance's retries for one interval. T-141 carries T-201's readiness port
  onto the scheduler (`ws/_specs/backlog.md:2779`). [Verified] requirement; [Inferred] mechanism.
- `DeliveryRetryTrigger.runOnce()` becomes the body of `DELIVERY_RETRY` unchanged; the trigger keeps
  no timing. The interval is a configuration value on the job row (team default 5 s), and the
  restart-survival test T-013 is re-run (`ws/_specs/project-plan.md:119`). [Verified] re-run;
  [Inferred] default value.

### 8. External calls

The scheduler itself calls only PostgreSQL. The one job that leaves the process,
`CREDENTIAL_CLEANUP`, calls Keycloak with the five controls already required by ADR-010 section 9 and
ADR-007 (`ws/_specs/backlog.md:3706`). This ADR adds no other outbound call. [Verified] T-203;
[Inferred] "no other call".

## What this ADR gates

| Work | Gate | Grade |
|---|---|---|
| T-056 core and job rows, T-057 on-time proof, T-058 two-instance test, T-059 run rows | ADR-012 approved | [Verified] `ws/_specs/backlog.md:3594-3597`, `:3550-3554` |
| T-141 delivery-retry job | ADR-012 approved **and** E9's reconciler (T-180), re-drive cap (T-199) and E10's readiness port (T-201) integrated | [Verified] `ws/_specs/backlog.md:3604`, `:2477`, `:2369`, `:2779` |
| T-142 purge, T-189 purge guard, T-203 credential clean-up | ADR-012 approved and T-056 | [Verified] `ws/_specs/backlog.md:261-263` |
| T-143 daily-report job | ADR-012 approved and E13's report | [Verified] `ws/_specs/backlog.md:3604` |
| T-060 agent API, T-061 agent actions audited, T-062 alerts | ADR-012 approved and its decision points answered (both 2026-10-12), E12's audit writer | [Verified] `ws/_specs/backlog.md:3715`, `:3743-3745` |
| ADR-007 roles register and architecture.md section 5 | Amended in the change that approves this ADR (the owner of a register re-publishes it, `documents/adr/README.md:47-48`) - done 2026-10-12 | [Verified] rule and amendment |

## Process - one run, with its failure paths

| Step | What happens | Failure path - who finds out | Grade |
|---|---|---|---|
| 1 | A ready instance polls the task table and claims one due execution by row lock | Database down: the poll fails, is logged and retried next poll; the overdue gauge rises - Prometheus | [Inferred] |
| 2 | The wrapper writes `job_run` started | Write fails: no run starts; retried next poll | [Inferred] |
| 3 | The handler loops tenants (one transaction each) or works on rows whose tenant comes from the row | One tenant's transaction fails: that tenant is retried next run, the others continue; the error class is counted | [Inferred] |
| 4 | The wrapper writes `job_run` finished with the outcome and counts | Throws or exceeds maximum run time: `FAILED` plus one `FAILED` alert - agent (API) and Prometheus | [Inferred] |
| 5 | The instance dies mid-run | Its heartbeat stops; another instance picks the execution up; the run is recorded late | [Inferred - T-058] |
| 6 | No instance is alive | Nothing runs; Prometheus' absent-metric rule alerts humans; on restart the missed run fires once, flagged late | [Inferred] |

## Conformance tests this ADR adds

Each written failing first: a job row fires within the poll interval (T-057); 20 due jobs, two
instances, each runs once (T-058); a job due while all instances were down fires once on start and is
flagged late (T-057); an `@Scheduled` annotation anywhere fails the build; each platform-scoped table
has no `tenant_id` column; a tenant id planted in job data is ignored (T-144); the agent token on
every tenant endpoint returns 403 and a tenant-table read under the agent principal errors; a
source credential and a read-only user on the job API return 403 (T-060); one incident writes one
alert (T-062); a not-ready instance claims 0 executions while the other runs every one (T-141); a
partition holding one dead letter survives the purge (T-189). [Inferred - owners per
`ws/_specs/backlog.md:3594-3745`]

## Amendment 2026-10-19 - per-task acceptance criteria (row 327, E14 / F8)

No decision above changes. This section makes the gate table implementable: each tracker item maps
to its backlog tasks, and each task has pass criteria written as counts. [Verified] mapping from the
hub tracker `.claude/tracker/projects/ipass--integration-platform.json` (items T73-T76, T127).

**Tracker to backlog mapping.** T73 (done) = T-057, T-058. T74 = T-056, T-141, T-062 (its T-060
part was split out). T75 = T-142, T-144, T-189, T-203. T76 = T-059, T-143, T-061. **T127 = T-060**,
the agent admin REST API, split out of T74 after review bounce c3; it needs a karan-security
re-gate before review. Backlog task T-127 (E10 sync p95 measurement, `_specs/backlog.md:2659`) is
a different item that shares the number only. [Verified] tracker titles.

**Tenant isolation rule for every job (RLS).** A handler never takes a tenant id from job data, the
`job_definition` row or a caller. It either loops the active tenants, setting `app.tenant_id` per
transaction, or works on rows whose `tenant_id` it reads from the row under row-level security.
Platform-scoped scheduler tables carry no `tenant_id`, and the agent principal reading a tenant
table gets an error, not rows. [Decided - restates Decision 1 and T-144 as one testable rule]

| Task | Acceptance criteria (each a test written failing first) |
|---|---|
| T-056 core, job rows | 1 `job_run` row per execution, with start, end, outcome and counts; a throwing handler gives 1 `FAILED` row and 1 alert; 0 `@Scheduled` or `@EnableScheduling` in the build (arch test, with a planted violation that must fail it); 0 `tenant_id` columns on platform-scoped scheduler tables |
| T-057 on time | a due job fires within 1 poll interval; a job due while every instance was down fires 1 time on start and is flagged late |
| T-058 two instances | 20 due jobs on 2 instances give 20 runs and 0 duplicates; killing the instance mid-run still ends in 1 recorded run |
| T-059 run rows | every finished run shows 1 row in the run list with its counts; 0 payload bytes in any `job_run` column |
| T-141 delivery retry | a not-ready instance claims 0 executions while the other claims every one; a delivery at the re-drive cap is re-driven 0 times |
| T-142 purge | only partitions past retention are dropped; the job runs as the maintenance role (env var names only, 0 secrets in files) |
| T-189 purge guard | a partition holding 1 `DEAD_LETTERED` delivery, or 1 non-final delivery, survives (0 drops); it drops only when both counts are 0. *Stricter since Amendment 2026-10-27: 1 message not `ROUTED` (`ACCEPTED`, `ROUTE_FAILED`) also keeps the day, and a suspended tenant's dead letter keeps it too* |
| T-143 daily report | 1 report per tenant per day; a tenant's report holds 0 rows of another tenant |
| T-144 tenant from row | a tenant id planted in job data is ignored: 0 rows written or read for the planted tenant |
| T-203 credential clean-up | the Keycloak call carries the five controls, each with its own test; Keycloak down gives 1 `FAILED` run, 0 credentials marked removed, and the next run retries |
| T-060 / T127 agent API | list, run, pause and inspect work under the agent credential; 403 for 3 of 3 non-agent callers (source credential, read-only user, tenant admin); the agent token on every tenant endpoint gives 403; 0 payload fields and 0 other-tenant configuration fields in any response; karan-security re-gate PASS before review |
| T-061 activity log | 1 audit event per agent operation (run, pause, resume) through E12's writer; a refused call writes 1 refusal event and nothing else |
| T-062 alerts | 1 alert row per incident, never 1 per retry; the alert API returns it on the next 5-minute poll; an alert closes 1 time, when the job next succeeds |

**What this gives up:** the criteria are fixed per task, so a lane that finds a better test changes
this section, not only the test.

## Amendment 2026-10-22 - job state-change stamp for the polling surface (E14)

No decision above changes; this adds one column and one query parameter to the polling surface (section 5).
`pollSince` could not see a job that was only disabled, or only resumed: `paused_at` is set by pausing alone and
cleared by resume, and nothing in the application flips `enabled` (the seed, a migration or manual SQL does).
Migration `V2_4_1320` adds `scheduler.job_definition.state_changed_at` (NOT NULL, default `now()`, backfilled from
`paused_at`, else the migration time) and a `BEFORE UPDATE OF enabled, paused` trigger that stamps it only when the
`(enabled, paused)` pair changes, so every writer is covered. `JobSummary` gains `stateChangedAt`;
"changed since" is now ran, paused or state-changed. `GET /api/v1/admin/jobs/summaries` takes an optional
`?since=<ISO-8601 instant>` (absent = the full list, an invalid value = 400); the role is unchanged
(`OPERATOR_AGENT` only). **Gives up:** state logic lives in a trigger, and the stamp is the database clock, not the
application `Clock`; the first poll after deploy lists every non-paused job once (a false positive, never a false
negative). Revisit if an enable / disable endpoint is added: it needs no extra code, but its tests must assert the
trigger fired. [Decided - design note 2026-10-22, option B]

## Consequences

- One mechanism runs all background work, visible in one table and one API. [Inferred]
- A second set of platform-scoped tables outside row-level security exists, kept in its own schema
  and named in an exemption register. [Inferred]
- The role policy gains a fourth caller kind; ADR-007's roles register and architecture.md section 5
  change with approval. [Inferred]
- The purge job needs the maintenance role's credentials at runtime (environment variable names
  only), so production carries one more secret. [Inferred]

**What we give up:** per-tenant schedules and any tenant self-service over jobs; push alerts in
Release 1; and the simplicity of "every table is under row-level security" - four more tables are
policed by grant and schema instead.

## Revisit if

- db-scheduler changes licence or has no release for 12 months (then Quartz, the scored runner-up).
  [Inferred]
- A job must run on a per-tenant schedule. [Inferred]
- Dipesh names a push channel for alerts. [TBD]
- Platform-operator login is designed (ADR-009): the human operator joins `OPERATOR_AGENT`'s
  operations under its own credential. [Verified] trigger `documents/adr/ADR-009-tenant-directory-rls.md:100`
- More than about 50 job types, or a schedule tighter than one second. [Inferred]

## Open

None. The three decision points were answered by Dipesh on 2026-10-12 (m-f1fcfc), each as
recommended:

1. **Decided:** tenant admins get no job operation in Release 1 (agent only); the backlog negative
   scenario at `ws/_specs/backlog.md:3730` reads "only the agent credential".
2. **Decided:** the agent polls the alert API every 5 minutes; no push channel in Release 1.
3. **Decided:** the machine-only agent credential proceeds; its attack suite is added to the
   Release 1 security review (T-147); the human operator login stays deferred.


## Amendment 2026-10-27 - PAYLOAD_PURGE guard is conservative (row 495, T-142; PR 52 `e12f2949`)

**Status: Ratified - mahesh-architect, 2026-10-27 (ledger row 524). Dipesh may reverse.** Decided by Mira on
row 495; built by arjun-fullstack in PR 52 (`e12f2949`) on top of `V2_4_1450`. No decision above changes except
the purge sketch of section 4, which is marked superseded where it stands.

- **Context.** The T-189 row of the criteria table keeps a day only for a `DEAD_LETTERED` or non-final delivery.
  The payload partition is the only copy of a message's body, and a message can be unfinished while its day holds
  no delivery at all: `ACCEPTED` (never routed; the reconciler re-publishes it, ADR-010 section 9) and
  `ROUTE_FAILED` (may need a fix and a replay). Message states are exactly `ACCEPTED`, `ROUTED`, `ROUTE_FAILED`
  (`platform-routing/src/main/resources/db/migration/V2_1_810__message_partitioned.sql:23`). The section 4 sketch
  also counted under row-level security with the active-tenant loop, which never sees a suspended tenant, so a
  suspended tenant's dead letters were unprotected. [Verified]
- **Options.** (A) The T-189 row as written: delivery guard only, counted per active tenant as the application
  role - purges the most, but drops a day holding an unrouted message or a suspended tenant's dead letter.
  (B) **Conservative guard, inside the database as the table owner** - keeps every day it cannot prove final.
  (C) B plus a per-message escape hatch (purge a day except its stuck rows) - impossible with partition drop
  without a row `DELETE`, which ADR-014 Decision 3 rules out. [Decided] **B.**
- **Decision (as built).**
  1. One definer function, `public.payload_purge_days(cutoff date, dry_run boolean)`, owned by the migration
     owner (the table owner), `SECURITY DEFINER`, `search_path = pg_catalog, pg_temp`, `lock_timeout = 5s`
     (`platform-app/src/main/resources/db/migration/V2_4_1460__payload_purge_conservative_guard.sql:9-13`).
     `EXECUTE` goes to `ipaas_maintenance` only; `PUBLIC` and the application role hold none (`:63-64`).
  2. Retention floor inside the function: a cutoff newer than today (UTC) minus 30 days raises `22023` (`:20-22`).
  3. Per day older than the cutoff: `ACCESS EXCLUSIVE` lock on `delivery_D`, count deliveries not `DELIVERED`
     (`:36-40` - this covers `DEAD_LETTERED` and every non-final state, `V2_3_1__delivery_partitioned.sql:28-29`);
     if 0, lock `message_D` and count messages not `ROUTED` (`:44-48`); any count above 0 is `KEPT` (`:50-51`).
     Otherwise `WOULD_DROP` (dry run) or a `DROP` of `message_D`, `payload_D`, `delivery_D` together (`:52-58`),
     in the same transaction as the count, so no state can change between guard and drop.
  4. The caller is `JdbcPartitionPurge` over a private pool on the maintenance connection
     (`platform-tenancy/src/main/java/io/integrationplatform/tenancy/internal/persistence/JdbcPartitionPurge.java:23-26`),
     created only when `IPASS_MAINTENANCE_DB_URL` is set (`MaintenancePurgeConfiguration.java:16-21`). The login
     named by `IPASS_MAINTENANCE_DB_USERNAME` is created outside Flyway and granted membership of the `NOLOGIN`
     role `ipaas_maintenance`, so no password enters a migration (`V2_4_1450__payload_purge.sql:6-8`, `:18-30`).
  5. The job ships `OFF` (`ipaas.retention.payload-purge.mode`, `PayloadPurgeHandler.java:42`, `:64-66`).
     `DRY_RUN` locks and counts but drops nothing; `ENFORCE` drops. Only a deployment sets either, and either
     without the maintenance connection fails the run by name, which alerts (`PayloadPurgeHandler.java:68-72`).
- **Architect's verdict - Ratified.** Checked this pass:
  - *Every tenant is counted, fail-closed.* The function reads the day partitions as their owner. No `FORCE ROW
    LEVEL SECURITY` is set on `message`, `payload` or `delivery` (search of all migrations this pass), so the owner
    sees every row, suspended tenants included. If row-level security ever bound the owner, the policies compare
    to `current_setting('app.tenant_id')` with no `missing_ok` (`V2_1_810__message_partitioned.sql:56`,
    `V2_3_1__delivery_partitioned.sql:70`): the count errors, the run fails and nothing is dropped.
  - *Conformance tests exist* (`platform-app/src/test/java/io/integrationplatform/app/retention/PayloadPurgeContainerTest.java`):
    a suspended tenant's dead letter, a `ROUTE_FAILED` message and an `ACCEPTED` message each keep their day
    (`:197-199`); dry run drops nothing (`:208-213`); a cutoff inside 30 days is refused (`:219`); the application
    role cannot run the purge (`:230`); the maintenance login can do nothing but call the function (`:241-257`);
    the function is a definer owned by the table owner with the fixed search path and lock timeout (`:260-275`).
  - *Tenant isolation is not weakened:* the function takes no tenant, returns only a day and an outcome, and
    reads no payload. It is a T6 maintenance function of the same shape as the two audit
    functions (`documents/architecture.md:172-177`), not a T5 tenant-crossing function, so the T5 count stays at
    four; architecture.md section 5 lists it as the third T6 function in this same change.
- **Trade-off accepted (what this gives up).**
  1. **One stuck message blocks its whole day, for every tenant.** A single `ACCEPTED` or `ROUTE_FAILED` message,
     or a dead letter, keeps that day's partitions - every tenant's payloads of that day - past the 30-day
     retention until it is resolved (routed, replayed or dead letter discarded). Retention becomes "at least
     30 days" for such a day, not "exactly 30".
  2. Payload storage grows with the number of unresolved days; `kept` days are counted on the run row and named
     in the run log (`PayloadPurgeHandler.java:82-87`), but there is no age alert on a kept day yet (Open 1).
  3. The function takes `ACCESS EXCLUSIVE` on `delivery_D` then `message_D`. A replay writing to a day older than
     30 days at the same moment waits up to 5 s or is chosen as a deadlock victim; the purge run then fails,
     alerts once and retries on its next run. Accepted: such writes are rare and the next run completes.
- **Gates (counts).** A day holding 1 delivery not `DELIVERED`: 0 drops. A day holding 1 message not `ROUTED`:
  0 drops. A suspended tenant's 1 dead letter: 0 drops. A clean day older than the cutoff: 3 of 3 partitions
  dropped under `ENFORCE`, 0 under `DRY_RUN`. A cutoff inside 30 days: 1 of 1 refused. The application role
  calling the function: 1 of 1 refused. All are in `PayloadPurgeContainerTest` (above).
- **Open (non-blocking, owners to be assigned by Mira).**
  1. A kept-day age signal: the oldest `KEPT` day as a gauge or a run count, with an alert when a kept day is
     more than 30 days past the cutoff - so "blocked by one stuck message" is seen, not silent.
  2. A conformance test that day partitions created by the production horizon
     (`V2_3_1__delivery_partitioned.sql:59` and its message / payload equivalents) carry no row-level security of
     their own (`relrowsecurity` false). Enabling row-level security on a partition with no policy hides every
     row from a query on that partition, the count would read 0, and the guard would fail open. The current test
     builds its own partitions (`PayloadPurgeContainerTest.java:129-131`), so it would not catch that change.
- **Revisit if** a tenant or regulation requires a maximum retention (then stuck-message days need a per-tenant
  escape, which partition drop cannot give without a row `DELETE` - a new ADR); more than 3 days are `KEPT` past the
  cutoff on 30 consecutive daily runs; or row-level security is enabled on any day partition.

[Ratified - mahesh-architect, 2026-10-27; reversible by a new migration restoring the `V2_4_1450` function body,
`V2_4_1460__payload_purge_conservative_guard.sql:66`]
