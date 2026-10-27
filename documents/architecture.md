---
type: Architecture
title: "Architecture - configuration-driven integration platform"
status: "Recreated 2026-10-02 (phase 0) from surviving sources and updated to ADR-005, 007, 010, 011; revised 2026-10-02 for vikram-cto's challenge (m-817272); revised 2026-10-02 for vikram-cto's re-check (m-6a410c): ordering_key table and per-key sequence; amended 2026-10-12 for ADR-012, ADR-013, ADR-014 and ADR-015 (approved by Dipesh 2026-10-12): adapters and the send-time destination check in platform-routing, routing -> config public API, message and payload day-partitioned on accepted_at with deliveries on their message's day, mapping_version, scheduler schema and the OPERATOR_AGENT caller kind; as-built status added 2026-10-16 at main 90c1ddd in section 9 (ledger row 325) - the target design is unchanged and every earlier line keeps its number"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0)"
project: ipass/integration-platform
provenance: recreation
related:
  - PRD.md
  - rule.md
  - adr/README.md
---

# Architecture - configuration-driven integration platform

> **Recreation, already updated to the final approach.** The earlier data-design,
> architecture-and-patterns and system-design documents were deleted with the old repository on
> 2026-10-02. This file is rebuilt from their quotes in the hub (`ws/_specs/project-plan.md:159`,
> provenance table) and written straight to the 2026-10-02 approach (Camel core, Kafka, contract
> registry), so there is no v1/v2 drift to undo. Each section names its source; text marked
> **New** comes from the ADRs of 2026-10-02. **What of this design the code runs today (main `90c1ddd`): section 9.**

Source paths starting `ws/` are in the planning workspace kept outside this repository;
see [README.md](README.md) for how to read them.

## 1. Quality goals, in priority order

*Sources: `ws/_specs/project-plan.md:44-52`, `:207`; `ws/_specs/f3-plan-brief.md:73`.*

1. **Tenant isolation** - 0 rows across tenants, even with a forged request; a release gate.
2. **No accepted message lost** (RPO zero for accepted messages - "non-negotiable").
3. **Configuration is data** - onboarding inserts rows; 0 deploys, 0 migrations.
4. **Every delivery accountable** - per-stage timestamps, append-only attempts, a report equal
   to the rows.
5. **Each target isolated** - one failing target never blocks or rolls back another.

## 2. Building blocks - one deployable, Maven modules (status today: section 9)

*Sources: `ws/_specs/work-breakdown.md:16-20` (module list of the deleted design);
`ws/_specs/f3-plan-brief.md:512` (config rule), `:611` (only deployable); ADR-001; **New**: the
placement of identity, registry, rules and Kafka follows ADR-007, 010, 011; **amended 2026-10-12**:
adapters and the routing -> config edge follow ADR-015, job definitions ADR-012.*

| Module | Plane | Owns | Must not |
|---|---|---|---|
| `platform-domain` | shared | Ids, value types, the Delivery state register and outcome classes (ADR-010) | Depend on Spring or any other module |
| `platform-tenancy` | shared | Tenant context, per-transaction stamping, the bootstrap resolvers (login, client, work), OIDC resource server and console login, Keycloak admin port, role checks | Read a tenant from a request body, header or Kafka record |
| `platform-config` | control | Systems, target endpoints and encrypted credentials, contracts and versions, interfaces and versions, routes, mappings and immutable mapping versions (ADR-013), rule sets (ZEN), lookup tables, retry policies, lifecycle and atomic publish, job definitions and the job admin API (ADR-012), admin API; a public API for the data plane: read-only over **published** versions, sealed target credentials, and the one outbound `DestinationGuard` | Be written by a data-plane module |
| `platform-ingest` | data | The common API, size caps, contract and rule validation, bulk split, idempotency, the intake transaction (message, payload, outbox), the outbox relay and its publish port | Call a target; use Camel |
| `platform-routing` | data | **The only Camel module**: Kafka consumers for stages 1 and 2 (claim on the partition thread, consumer pause on a transient database error), route templates per target type, the target adapters (`routing/adapter/**`, ADR-015), the per-target bulkheads and circuit breakers, the send-time outbound destination check (config's `DestinationGuard`, reused - ADR-015), the Kafka implementation of the publish port. Depends on `platform-config`'s public API only, never its internals (ADR-015 Decision 2) | Hold retry timing; decide a tenant from a record; call a target on a Kafka partition thread |
| `platform-transform` | data | Mapping execution with the JSONata engine (Dashjoin port) called directly (ADR-006, confirmed by the spike), sample tests | Use Camel |
| `platform-delivery` | data | Delivery and attempt persistence, outcome classifier, retry policy (no adapter - ADR-015), the ordering hold and its same-transaction release, the polling re-drive trigger and the reconciler inside it (the delivery-retry job), replay | Send anything itself |
| `platform-ledger` | data | Stage timestamps, message timeline, daily pre-aggregates, the daily report, dashboard counts | Read sampled traces for the report |
| `platform-audit` | shared | Append-only audit events, the tenant's activity view | Update or delete an event |
| `platform-app` | - | The deployable: wiring, security chain, scheduler jobs (db-scheduler), actuator | Contain business logic |
| `arch-test` | test | ArchUnit and Spring Modulith verification rules | - |
| `platform-e2e` | test | End-to-end suites on real containers plus a browser journey | - |
| `ui/` | - | The React console, served as static files, calling only the admin API | Hold an access token (ADR-007) |
| `e2e-ui/` | - | Test-only: the console's Playwright suites and runner scripts; no product code | Ship in the console bundle |

The rule from the deleted design stands: configuration is written only by the control plane and
read only by the data plane, and the data plane reads only published, immutable versions.
**Amended 2026-10-12 (ADR-015):** `platform-routing` depends on `platform-config`'s public API
(published versions, sealed target credentials, the destination guard) - already declared in
`platform-routing/pom.xml:38-41` - and never on config internals; an arch-test rule allowing the
API package only is written in E10's wave-0 seam.

## 3. Data model (shape, not DDL)

*Sources: deleted data-design.md §3-4 quoted at `ws/_specs/f3-plan-brief.md:36-48`; ADR-002;
**New**: contract, delivery hold, stage columns and the definer-function register come from
ADR-007, 010, 011; **amended 2026-10-12**: message store, delivery uniqueness and retention
(ADR-014), mapping versions (ADR-013), scheduler tables (ADR-012).*

Every tenant-owned table has `tenant_id` and a row-level security policy; closed value sets are
`text` with a `CHECK`; high-volume tables are partitioned by day.

| Area | Tables | Notes |
|---|---|---|
| Tenancy and identity | `tenant`, `app_user` (issuer, subject), `role_grant`, `source_credential` (issuer, client id, status) | Directory under RLS with one bootstrap function (ADR-009) |
| Systems | `system` (code, name, direction, + `type`, `settings` from the registry phase), `target_endpoint` (timeout, circuit settings, error dictionary, idempotent-receive declaration, typed settings), `target_credential` (envelope-encrypted, ADR-007) | One systems table; settings validated per type |
| Contracts | `contract` (system, role), `contract_version` (label, status, JSON Schema, optional OpenAPI, derived-from, compatibility, author, approver) | The only home of every schema (ADR-011) |
| Flow configuration | `interface` (code immutable once published, mode `ASYNC`/`SYNC`, `ordering` `PER_KEY`/`NONE`, current and published version), `interface_version` (immutable snapshot `jsonb` of the whole bundle, business-key rule mandatory, change note, rejection comment, author, approver, `CHECK` approver is not author), `route` (target endpoint, condition, single primary for sync), `mapping` (the editable copy, one per route) and `mapping_version` (immutable, content-addressed, created at draft save, bound to two contract versions, referenced by id from the snapshot and pinned on every delivery - ADR-013), `retry_policy`, `rule_set`, `lookup_table`, `lookup_entry` | Lookup **values** are deliberately not versioned: immediate and audited (OQ-07 stays Dipesh's) |
| Intake | `message` (interface version, source contract version, business key, **per-key sequence**, state `ACCEPTED`/`ROUTED`/`ROUTE_FAILED`, stage timestamps), `ordering_key` (tenant, interface, business key - unique together; `last_seq`; one row per key, taken by the intake transaction with an upsert that returns the next per-key sequence and holds the row lock to commit, so per key the sequence is commit order; not used when ordering is `NONE` - ADR-011 Decision 6), `payload`, `outbox` (`available_at`, `traceparent`, key bucket 0-63), `idempotency_key` (source credential, key, request-body hash, results; kept 24 hours) | Daily partitions for message and payload on the message's `accepted_at` (payload carries its message's `accepted_at`; ADR-014); payload retention 30 days (Dipesh, 2026-10-12); one day of message, payload and delivery is dropped together, and only when 0 dead-lettered or non-final deliveries reference it (ADR-011 Decision 8) |
| Delivery | `delivery` (state, message and route - unique per message day as `(message_id, route_id, created_at)` with `created_at` = the message's `accepted_at`, so a delivery sits in its message's day partition (ADR-014); pinned target endpoint, contract and mapping versions, ordering key, `next_attempt_at`, `claim_token`, `claimed_at`, `bulkhead_deferrals`), `delivery_attempt` (append-only; layer, outcome class, timings), `delivery_event` (holds, releases, circuit, bulkhead and missing-key deferrals, late outcomes, reconciler re-drives, replays) | Daily partitions on the message's day; attempts keep their own insert-day partitions and are never updated or deleted |
| Ledger | Stage timestamps on message and delivery; `daily_aggregate` per tenant, interface, target, day | The report reads aggregates, which a seeded test proves equal to the rows |
| Audit | `audit_event` | Append-only; partition drop after 7 years |
| Scheduler | Own schema `scheduler`: db-scheduler's task table, `job_definition`, `job_run`, `job_alert`, `operator_credential` (ADR-012) | Jobs are configuration rows; **platform-scoped, no `tenant_id` column**, access by grant only (control T3 exemption register, section 5) |

**Stage timestamps** (*source: `ws/_specs/project-plan.md:118`*): received, validated, transform
start, transform end, sent, target responded, completed.

## 4. The message path

*Sources: ADR-005, ADR-010, ADR-011; `ws/_specs/project-plan.md:113-116`.*

### 4.1 Asynchronous flow (the default) - status today: section 9

| Step | Where | What happens | Failure path - who finds out |
|---|---|---|---|
| 1 | ingest | Source calls `POST /api/v1/interfaces/{code}/messages[:bulk]` with its token | Invalid or revoked credential -> 401/403, never retried |
| 2 | tenancy | (issuer, client id) -> source system, customer, tenant via the client bootstrap function; body `customerId` cross-checked | Mismatch -> 403, audited |
| 3 | ingest | Size caps; per record contract validation then rules, collecting every violation; business key; idempotency | Over a cap -> 413, nothing stored; invalid records -> listed with all reasons |
| 4 | ingest | **One transaction:** each distinct key's `ordering_key` row first, in key-hash order (the per-key sequence, held to commit), then message, payload and outbox rows for every valid record | Budget 2 s exceeded or database down -> 503, nothing stored; source repeats with the same idempotency key |
| 5 | ingest | Reply 202 with per-record results and tracking ids | - |
| 6 | ingest relay | Publishes each outbox row to `platform.message.accepted` (key = hash of tenant, interface, business key; `traceparent` restored) and marks it published; **one publisher per key bucket** (advisory lock), rows in order, stops a bucket at the first failure (ADR-005 Decision 11) | Kafka down -> breaker opens, rows stay, outbox-age alert after 60 s; a crash after publish -> duplicate record, harmless (step 8) |
| 7 | routing stage 1 | Resolves the tenant from the message row (work function), loads the pinned interface version, evaluates route conditions | Unreadable record or missing row -> `platform.poison`, alert; transient database error -> consumer pauses and backs off, never poison |
| 8 | routing stage 1 | **One transaction:** key lock; message `ACCEPTED -> ROUTED` by conditional update; one Delivery per matched target, insert-on-conflict `(message_id, route_id, created_at)` do nothing, `created_at` = the message's `accepted_at` (ADR-014; pinning endpoint, contract and mapping versions), plus one outbox row each; release deliveries held only behind this message | 0 rows on the update -> duplicate record, no effect; no route -> `ROUTED` with 0 deliveries, "no route" in the timeline; pinned configuration unreadable -> `ROUTE_FAILED`, alert |
| 9 | routing stage 2 | Consumes `platform.delivery.due` on the partition thread; takes a permit from the target's **bulkhead** | Bulkhead full -> no call, `BULKHEAD_FULL` event, `RETRY_WAIT` for 1-5 s |
| 10 | routing stage 2 | **Claim transaction, one order:** key lock, read the delivery, check the hold (an earlier same-key message still `ACCEPTED`, or an earlier delivery to this target not final), then `HELD` or `SENDING` with a claim token; commit; then commit the Kafka offset | 0 rows -> duplicate, acknowledged; hold applies -> `HELD`, visible on the dashboard |
| 11 | transform (bulkhead thread) | Mapping version applied to the payload (5 s budget) | Mapping error -> `PERMANENT`, dead-lettered with the reason |
| 12 | routing stage 2 (bulkhead thread) | Destination check (pinned address); target route sends with timeout, breaker and layer-1 redelivery of transient failures (`Retry-After` honoured); each call writes one attempt row | Circuit open -> no call, `RETRY_WAIT`; transient after layer 1 -> `RETRY_WAIT`; ambiguous (timeout after send, or 408/500/502/504 without idempotent receive) -> retry only if the target declares idempotent receive, else dead-lettered `AMBIGUOUS_TIMEOUT`; permanent -> dead-lettered |
| 13 | delivery (delivery-retry job) | Moves due `RETRY_WAIT` rows to `DUE` with an outbox row -> back to step 9 (at half-open, at most the breaker's trial count per target); the **reconciler** re-publishes messages `ACCEPTED` over 5 minutes, deliveries `PENDING`/`DUE` idle over 5 minutes, and `HELD` with no blocker left | Instance crash mid-call -> stale claim after 3 x target timeout + 5 s + 60 s, classed ambiguous; a late outcome with an old claim token changes no state |
| 14 | delivery | `DELIVERED` or `DEAD_LETTERED`, conditional on the claim token; **in the same transaction** the next held same-key delivery per target is released | Dead letter -> operator replay later, with the versions pinned at arrival (retired versions stay readable), audited |

### 4.2 Synchronous single-target flow - planned, see section 9

Steps 1-4 as above, with **the message's own outbox row** deferred (`available_at` = now + 10 s +
5 s). The request thread then runs stage 1 inline (`ROUTED` and the delivery in one transaction,
the delivery's outbox row deferred the same way) and steps 9-12 for that one delivery - bulkhead
permit, the same claim transaction - making exactly one target call with no redelivery. A full
bulkhead or a hold makes no call and answers 202 `PENDING`. Success -> 200 with the target's response mapped to the source's
response contract; permanent failure -> the mapped error; anything else or the 10 s budget ->
202 with the tracking id and status `PENDING`, and the delivery continues on the asynchronous
path. If the instance dies before routing, the deferred message row re-drives stage 1; if it
dies mid-call, the stale-claim rule recovers the delivery (ADR-011 Decision 5).

### 4.3 Configuration publish

| Step | What happens | Failure path |
|---|---|---|
| 1 | Configurator edits a draft (never live) | - |
| 2 | Submits with a change note -> `PENDING_APPROVAL`; snapshot copied | No change note -> refused |
| 3 | Approver approves - never the author (database `CHECK` and 403) | Author approves -> refused |
| 4 | Publish: interface, routes and mappings in one transaction, after the pin check - every mapping's source contract version equals the interface version's pin, and its target contract version equals the route's pin (ADR-011 Decision 3 rule 6) | Any part fails, or a pin differs -> nothing changes, the mismatch is named |
| 5 | Data plane picks the new published version on the next message; routes are created lazily on first use, on any instance | - |

*Source for 4.3: deleted workflows.md W8 and integration-platform-design.md P8, quoted at
`ws/_specs/f3-plan-brief.md:52-55`, `:62-65`.*

## 5. Tenant isolation - the controls (closed register; T5 status: section 9)

*Sources: `ws/_specs/project-plan.md:103`; ADR-007, ADR-009, ADR-011; measured lessons in
[rule.md](rule.md).*

| # | Control | Proven by |
|---|---|---|
| T1 | Tenant from the authenticated principal through our database only; never from a body, header, path or Kafka record | Forged-tenant denial suite (release gate) |
| T2 | Every query carries the tenant filter; ArchUnit fails the build on a repository or raw JDBC access without it, in every module's package | Planted-violation test |
| T3 | Row-level security on every tenant-owned table; error-deny on an empty setting | RLS-bypass negative suite with raw SQL |
| T4 | The setting is stamped per transaction only | Pooled-connection test (ADR-007 Decision 6) |
| T5 | The definer functions that cross the tenant boundary are exactly four: `ipaas_login_bootstrap`, `ipaas_client_bootstrap`, `ipaas_work_tenant`, `ipaas_active_tenants` - each narrow, ids or status only, `search_path` pinned, `EXECUTE` to the application role only | One negative test each; adding a fifth needs an ADR |
| T6 | The application role has no `BYPASSRLS` and no DDL; partition maintenance runs as a separate role | Grant inspection test |

**Amended 2026-10-12 (ADR-012, approved by Dipesh):**

- **Platform-scoped exemption register (T2, T3).** Exactly these tables hold no tenant data and
  sit outside row-level security, in their own schemas, access by grant only (the application role
  reads and writes, PUBLIC holds nothing): the `console_session` schema (ADR-007 Decision 1) and the
  `scheduler` schema (db-scheduler's task table, `job_definition`, `job_run`, `job_alert`,
  `operator_credential`). Each listed table has a test that it has no `tenant_id` column; adding a
  table needs an ADR.
- **The first no-tenant principal.** The `OPERATOR_AGENT` caller kind (ADR-007 Decision 5) binds
  no tenant, so every tenant-owned policy errors for it (T3 error-deny); it reaches only the job and
  alert operations. Tenant admins hold no job operation in Release 1. Its attack suite is part of the
  Release 1 security review (T-147). T5 stays at four definer functions: jobs loop
  `ipaas_active_tenants` and resolve known rows with `ipaas_work_tenant`.

**Amended 2026-10-17 (ADR-016, team - Dipesh may reverse):**

- **Partition maintenance through two narrow functions (T6).** `audit_ensure_partitions()` and
  `audit_purge_expired()` are no-argument definer functions with a pinned `search_path` and a
  5-second lock timeout; `EXECUTE` goes to the maintenance role `ipaas_maintenance` **only** - the
  application role holds none (grant-inspection test; a call as the application role fails with
  42501). They are not T5 functions: they neither resolve nor return a tenant. They run in the job
  `AUDIT_PARTITION_MAINTENANCE`, the fifth member of ADR-012's job-type register.
- **Amended 2026-10-27 (ADR-012 amendment 2026-10-27, ratified):** a third T6 function,
  `payload_purge_days(cutoff, dry_run)` (`V2_4_1450`, guard made conservative by `V2_4_1460`), same shape -
  definer owned by the table owner, pinned `search_path`, 5-second lock timeout, `EXECUTE` to
  `ipaas_maintenance` only, no tenant in or out. It runs in the job `PAYLOAD_PURGE`, ships `OFF`, and keeps any
  day that holds a delivery not `DELIVERED` or a message not `ROUTED`, for every tenant, suspended ones included.

## 6. Deployment view (development compose today: section 9)

*Sources: `ws/_specs/project-plan.md:71`, `:79`, `:85-92`, `:192`; ADR-005, ADR-007.*

| Component | Role | Notes |
|---|---|---|
| `platform-app` x N | The platform | Stateless; sessions in PostgreSQL |
| PostgreSQL 17 | Platform data; Keycloak's own database | Separate databases and users |
| Apache Kafka (KRaft) | Work topics, poison topic | 1 broker in dev/test, 3 in production |
| Keycloak | Login and machine credentials | Highly available in production (on the ingest path) |
| JMS broker | MQ-CSV targets | The client's product in production; ActiveMQ Artemis 2.55.0 in tests |
| Prometheus, Grafana, Tempo | Metrics, dashboards, traces | Internal only (Grafana is AGPL) |
| Kafbat UI | Engineers' Kafka view | Internal only |

Every container image tag is pinned; every password comes from an environment variable name.

## 7. Cross-cutting

*Sources: `ws/_specs/project-plan.md:86`, `:115` (five controls on a target call, two retry layers);
`:119`, `:202` (delivery-retry job; hold and release); `ws/_memory/shared.md:44` (m-8bcac7, the
reconciler stays inside the delivery-retry job); `ws/_specs/project-plan.md:87`, `:117`
(correlation id, `traceparent` on the outbox row, structured logs, masked fields); `:110`,
`:115` (destination controls, test-connection audited and rate-limited). The alert thresholds
and the full refused-range list come from ADR-005 and ADR-010 (team decisions Dipesh may
reverse).*

- **Resilient integration:** every call that leaves the process carries timeout, transient-only
  retry with backoff, jitter and a cap, one circuit breaker per dependency, a fallback, and
  metrics plus a trace span (ADR-010 section 8 for targets, which adds a per-target bulkhead;
  ADR-005 Decision 12 for the relay and the consumers; ADR-007 for Keycloak).
- **Nothing stays stuck:** release in the same transaction, the reconciler inside the
  delivery-retry job, and the consumer pause on a transient database error (ADR-010 section 9).
  Alerts: oldest unpublished outbox row over 60 s, consumer lag over 1,000 records or 2 minutes,
  every poison arrival, a hold older than 1 hour.
- **Observability:** one correlation id and the W3C trace context follow a message through
  intake, Kafka, Camel, transform, delivery and response; the outbox row carries `traceparent`.
  Logs are structured JSON with tenant and message ids; masked fields never appear.
- **Outbound destination controls:** one check for every target type's host (ADR-010 section
  8): refused ranges include loopback, private, link-local and cloud metadata, carrier-grade NAT,
  IPv6 unique-local, IPv4-mapped IPv6 and the platform's own hosts; the resolved address is
  pinned; the allow-list is per tenant but maintained by the operator; every redirect re-checked;
  File paths confined under a root; test-connection audited and rate-limited.

## 8. Decisions

*Source: `ws/_specs/project-plan.md:156-169` (the provenance table that lists the ADRs).*

See the [ADR index](adr/README.md).

Diagrams (architecture, end-to-end flow in two parts, configuration workflow, delivery states, console UI shell), drawn against the code at main `90c1ddd` with status words: [diagrams/README.md](diagrams/README.md).

## 9. As built at main `90c1ddd` (2026-10-16)

Sections 1-8 describe the **target** architecture. This section says how much of it the code holds today, so a
reader does not take a planned piece for a running one. The [diagrams](diagrams/README.md) use the same status
words: **runs** (started by `platform-app`), **not wired** (classes and tests exist, nothing creates or calls them
yet), **planned** (no running code and no caller).

**In one sentence:** the running application accepts a message, stores it with its payload and outbox row, and stops
there (`platform-ingest/src/main/java/io/integrationplatform/ingest/internal/intake/IntakeService.java:38-66`). The
relay, the stage-1 and stage-2 consumers, the per-target bulkhead, claim, send and the retry job body are built and tested as separate pieces
but are not connected. Connecting them is the E11 composition step; no `app/composition` package exists under
`platform-app/src/main` yet.

| Module (section 2) | Maven module today | Status | Evidence |
|---|---|---|---|
| `platform-domain` | yes | partly: the job-handler and audit-event types are used by running code; the Delivery state register and the outcome classes are reached only from **not wired** code; no id type yet (only `audit/`, `delivery/`, `scheduler/`) | `pom.xml:24`; `platform-domain/src/main/java/io/integrationplatform/domain/scheduler/JobHandler.java:19` (used by `platform-app/src/main/java/io/integrationplatform/app/scheduler/JobRunWrapper.java:3`); `platform-config/src/main/java/io/integrationplatform/config/rule/application/LookupEntryService.java:6`; `platform-domain/src/main/java/io/integrationplatform/domain/delivery/Delivery.java:34`; `platform-domain/src/main/java/io/integrationplatform/domain/delivery/DeliveryTransitions.java:39-47` |
| `platform-tenancy` | yes | runs: console login (BFF), bootstrap resolvers, roles | `pom.xml:34`; `platform-tenancy/src/main/java/io/integrationplatform/tenancy/internal/web/ConsoleSecurityConfiguration.java:84-88` |
| `platform-config` | yes | runs: the admin API of [interface.md](interface.md) sections 4-7 | `pom.xml:30` |
| `platform-ingest` | yes | intake runs (one endpoint, no bulk, no schema or rule validation, `Idempotency-Key` required but not stored); the outbox relay is **not wired** | `pom.xml:27`; `platform-ingest/src/main/java/io/integrationplatform/ingest/adapter/in/IntakeController.java:53`, `:68-69`; `platform-ingest/src/main/java/io/integrationplatform/ingest/internal/relay/PostgresOutboxRelay.java:45` |
| `platform-routing` | yes | **not wired**: stage consumers, the layer-1 send route, the per-target bulkhead (1-32 concurrent sends, non-blocking permit taken before the claim), the API adapter and the send-time credential beans exist with no caller; the Kafka publish port is a bean only when `IPASS_KAFKA_BOOTSTRAP_SERVERS` is set; only the API adapter sends; MQ-CSV, DB, File and Kafka adapters **planned** - and Kafka is not yet a registrable target type (see the Kafka note below) | `pom.xml:26`; `platform-routing/src/main/java/io/integrationplatform/routing/stage/StageConsumer.java:45`; `platform-routing/src/main/java/io/integrationplatform/routing/publish/KafkaPublishPortConfiguration.java:29`; `platform-routing/src/main/java/io/integrationplatform/routing/adapter/api/ApiAdapterConfiguration.java:43-45`; `platform-routing/src/main/java/io/integrationplatform/routing/credential/CredentialRoutingConfiguration.java:23-33`; `platform-routing/src/main/java/io/integrationplatform/routing/stage2/bulkhead/DeliveryDueDispatch.java:41`; `platform-routing/src/main/java/io/integrationplatform/routing/stage2/bulkhead/TargetBulkhead.java:23-36`; `platform-routing/src/main/java/io/integrationplatform/routing/stage2/bulkhead/BulkheadSettings.java:14` |
| `platform-transform` | yes | runs only behind the mapping test; transform in the send path **planned** (`platform-routing/pom.xml` has no `platform-transform` dependency; the API body port is a stub) | `pom.xml:33`; `platform-routing/src/main/java/io/integrationplatform/routing/adapter/api/ApiAdapterConfiguration.java:38-39` |
| `platform-delivery` | yes | **not wired**: claim, outcome recording, retry trigger, and the bulkhead-full deferral (`PENDING`/`DUE` to `RETRY_WAIT`, 1 s doubling to 30 s with full jitter, a `BULKHEAD_FULL` event); the reconciler **planned** | `pom.xml:25`; `platform-delivery/src/main/java/io/integrationplatform/delivery/internal/deferral/JdbcBulkheadDeferrals.java:31`, `:68-75`; `platform-delivery/src/main/java/io/integrationplatform/delivery/internal/deferral/BulkheadBackoff.java:5-9` |
| `platform-ledger` | **no** | planned; stage timings are kept in `message_stage_timing` today | `pom.xml:23-38`; `platform-routing/src/main/resources/db/migration/V2_1_1110__message_stage_timing.sql:26` |
| `platform-audit` | **no** | planned; no `audit_event` table in any migration | `pom.xml:23-38` |
| `platform-app` | yes | runs: wiring, db-scheduler with 4 recurring jobs whose bodies are no-ops or skipped today, the queue summary API `GET /api/v1/admin/queue/summary` | `pom.xml:35`; `platform-app/src/main/java/io/integrationplatform/app/scheduler/SchedulerJobTasks.java:37-53`; `platform-app/src/main/java/io/integrationplatform/app/scheduler/JobRunWrapper.java:32-34`; `platform-app/src/main/java/io/integrationplatform/app/queue/QueueSummaryController.java:35`, `:51` |
| `arch-test` | yes | ArchUnit only (6 rule classes); no Spring Modulith dependency in any `pom.xml` | `pom.xml:37`; `arch-test/src/test/java/io/integrationplatform/archtest/` |
| `platform-e2e` | **no** | planned | `pom.xml:23-38` |
| `ui/` | not a Maven module | runs: the console of [design.md](design.md) | `ui/src/App.tsx:65-89` |
| `e2e-ui/` | not a Maven module | test-only: the console's Playwright suites and runners (L29, moved out of `ui/`); drives a booted backend and the BUILT `ui/` | `e2e-ui/package.json` |

Data model (section 3) differences today, measured by searching every `*.sql` migration on main: there is **no**
`ordering_key`, `retry_policy`, `daily_aggregate` or `audit_event` table yet [TBD - the owning phases];
`lookup_entry` is named only in a comment
(`platform-config/src/main/resources/db/migration/V2_2_51__lookup_table.sql:2`). The `message` state set
`ACCEPTED`/`ROUTED`/`ROUTE_FAILED` exists
(`platform-routing/src/main/resources/db/migration/V2_1_810__message_partitioned.sql:23`).

### Notes per section

**Section 2 (modules).** `platform-ledger`, `platform-audit` and `platform-e2e` are not Maven modules
yet, and `arch-test` holds ArchUnit rules only (table above).

**Section 4.1 (asynchronous flow).** Steps 1, 2, 4 and 5 run for a single record. Step 3 runs only the 1 MB cap,
the source-contract pin and the business key (`IntakeService.java:41-56`): no bulk, no schema or rule validation,
no idempotency store. Step 4 writes message, payload and outbox but no `ordering_key` row. Steps 6-14 are built but
**not wired** (table above). Answers today: 202 with a tracking id, 400, 404, 413, 415, 422, 503
(`platform-ingest/src/main/java/io/integrationplatform/ingest/adapter/in/IntakeController.java:30-33`).

**Section 4.2 (synchronous flow).** **planned** - no `SYNC` code path in any module.

**Kafka - two roles, both not running yet** (diagrams: the architecture `Apache Kafka` and `Target systems` boxes).
As the platform's own bus, the development compose file starts the broker (`compose/compose.yaml:72`), but
`platform-app` publishes and consumes nothing: the outbox relay and the stage consumers are **not wired**, and the
Kafka publish port is a bean only when `IPASS_KAFKA_BOOTSTRAP_SERVERS` is set
(`platform-routing/src/main/java/io/integrationplatform/routing/publish/KafkaPublishPortConfiguration.java:29`) - so the
three topics and the ids-only record are **not wired**. As a target type, Kafka is **planned**: the system-type register
allows only `API`, `DB`, `MQ` and `FILE`
(`platform-config/src/main/resources/db/migration/V2_2_7__config_system_type_settings.sql:13`), so a Kafka target cannot
be registered today; [ADR-015](adr/ADR-015-target-type-register-and-adapter-placement.md) decision 3 adds `KAFKA_TOPIC`
(and renames `MQ` to `MQ_CSV`) in E10, with its settings schema in E16. The architecture diagram's targets box reads
"API; MQ, DB, File, Kafka planned".

**Section 5 (control T5).** T5 - three of the four definer functions exist
(`platform-tenancy/src/main/resources/db/migration/V1_3_3__ipaas_login_bootstrap.sql:7`,
`platform-tenancy/src/main/resources/db/migration/V2_3_5__ipaas_client_bootstrap.sql:8`,
`platform-routing/src/main/resources/db/migration/V2_3_811__ipaas_work_tenant.sql:13`). `ipaas_active_tenants` is
referenced in Java (`platform-delivery/src/main/java/io/integrationplatform/delivery/internal/trigger/ActiveTenants.java:8`)
but no migration creates it yet [TBD - the phase that wires the scanning jobs].

**Section 6 (deployment).** The development compose file starts PostgreSQL 17, Keycloak, Kafka and Kafbat
only (`compose/compose.yaml:13`, `:41`, `:72`, `:104`). Prometheus, Grafana and Tempo are **planned**; actuator
exposes health only (`platform-app/src/main/resources/application.yml:57-58`). No JMS broker is in compose or in any
test yet [TBD - the MQ-CSV adapter phase].
