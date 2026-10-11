---
type: ADR
title: "ADR-012 - Job scheduler: db-scheduler with job definitions as our rows, platform-scoped jobs, a no-tenant operator-agent credential, alerts as durable rows plus Prometheus"
status: "Proposed (mahesh-architect, 2026-10-11) - Dipesh approves. Three points wait for him (Open items 1-3)"
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

## Decision (Proposed)

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
  [Inferred] the race argument.

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
  admins reach the admin API" (`ws/_specs/backlog.md:3730`) - **Open item 1**. [Verified] wording;
  [TBD] Dipesh.
- **Audit:** agent actions are written to an append-only **platform audit stream** (same writer,
  same append-only grants and 7-year retention as the tenant audit log of E12), not to a tenant's
  activity view, because they belong to no tenant and a tenant-scoped row with no tenant would fail
  the row-level-security check. This is how E14-C12 "every action in the activity log" is met.
  [Inferred - new team decision]
- This is the platform's **first no-tenant principal**. It is a machine credential, not the deferred
  operator **login**, but it crosses the same line, so it carries its own attack suite (below) and is
  routed to the security review - **Open item 3**. [Verified] deferral `documents/adr/ADR-009-tenant-directory-rls.md:84-86`;
  [TBD] Dipesh.

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
- **Which agent reads the alerts, and how often,** is a hub-side fact - **Open item 2**. [TBD]

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
| T-060 agent API, T-061 agent actions audited, T-062 alerts | ADR-012 approved, Open items 1 and 3 answered, E12's audit writer | [Verified] `ws/_specs/backlog.md:3715`, `:3743-3745` |
| ADR-007 roles register and architecture.md section 5 | Amended in the change that approves this ADR (the owner of a register re-publishes it, `documents/adr/README.md:42-45`) | [Verified] rule; [Inferred] timing |

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

1. **Needs decision (Dipesh):** do tenant admins get any job operation in Release 1? Recommend
   **no** (agent only; a tenant-admin pause would stop every tenant) and the backlog negative
   scenario at `ws/_specs/backlog.md:3730` reworded to "only the agent credential". Alternative:
   tenant admins read job health only, no actions.
2. **Needs decision (Dipesh):** which AI agent reads the alerts, how often it polls, and whether he
   wants a push channel later. Recommend: the agent polls the alert API every 5 minutes; no push in
   Release 1.
3. **Needs decision (Dipesh):** the agent credential is the first no-tenant principal, while operator
   login was deferred to an operations phase. Recommend proceeding with the machine-only scope above
   and adding its attack suite to the Release 1 security review (T-147); the human operator login
   stays deferred.
