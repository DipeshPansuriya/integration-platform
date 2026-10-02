---
type: Architecture
title: "Architecture - configuration-driven integration platform"
status: "Recreated 2026-10-02 (phase 0) from surviving sources and updated to ADR-005, 007, 010, 011"
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
> **New** comes from the ADRs of 2026-10-02.

Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`.

## 1. Quality goals, in priority order

*Sources: `ws/_specs/project-plan.md:44-52`, `:207`; `ws/_specs/f3-plan-brief.md:73`.*

1. **Tenant isolation** - 0 rows across tenants, even with a forged request; a release gate.
2. **No accepted message lost** (RPO zero for accepted messages - "non-negotiable").
3. **Configuration is data** - onboarding inserts rows; 0 deploys, 0 migrations.
4. **Every delivery accountable** - per-stage timestamps, append-only attempts, a report equal
   to the rows.
5. **Each target isolated** - one failing target never blocks or rolls back another.

## 2. Building blocks - one deployable, Maven modules

*Sources: `ws/_specs/work-breakdown.md:16-20` (module list of the deleted design);
`ws/_specs/f3-plan-brief.md:512` (config rule), `:611` (only deployable); ADR-001; **New**: the
placement of identity, registry, rules and Kafka follows ADR-007, 010, 011.*

| Module | Plane | Owns | Must not |
|---|---|---|---|
| `platform-domain` | shared | Ids, value types, the Delivery state register and outcome classes (ADR-010) | Depend on Spring or any other module |
| `platform-tenancy` | shared | Tenant context, per-transaction stamping, the bootstrap resolvers (login, client, work), OIDC resource server and console login, Keycloak admin port, role checks | Read a tenant from a request body, header or Kafka record |
| `platform-config` | control | Systems, target endpoints and encrypted credentials, contracts and versions, interfaces and versions, routes, mappings, rule sets (ZEN), lookup tables, retry policies, lifecycle and atomic publish, admin API; a read-only API over **published** versions for the data plane | Be written by a data-plane module |
| `platform-ingest` | data | The common API, size caps, contract and rule validation, bulk split, idempotency, the intake transaction (message, payload, outbox), the outbox relay and its publish port | Call a target; use Camel |
| `platform-routing` | data | **The only Camel module**: Kafka consumers for stages 1 and 2, route templates per target type, target adapters, circuit breakers, the Kafka implementation of the publish port | Hold retry timing; decide a tenant from a record |
| `platform-transform` | data | Mapping execution with the JSONata engine called directly (ADR-006 decides), sample tests | Use Camel |
| `platform-delivery` | data | Delivery and attempt persistence, outcome classifier, retry policy, the polling re-drive trigger, replay | Send anything itself |
| `platform-ledger` | data | Stage timestamps, message timeline, daily pre-aggregates, the daily report, dashboard counts | Read sampled traces for the report |
| `platform-audit` | shared | Append-only audit events, the tenant's activity view | Update or delete an event |
| `platform-app` | - | The deployable: wiring, security chain, scheduler jobs (db-scheduler), actuator | Contain business logic |
| `arch-test` | test | ArchUnit and Spring Modulith verification rules | - |
| `platform-e2e` | test | End-to-end suites on real containers plus a browser journey | - |
| `ui/` | - | The React console, served as static files, calling only the admin API | Hold an access token (ADR-007) |

The rule from the deleted design stands: configuration is written only by the control plane and
read only by the data plane, and the data plane reads only published, immutable versions.

## 3. Data model (shape, not DDL)

*Sources: deleted data-design.md §3-4 quoted at `ws/_specs/f3-plan-brief.md:36-48`; ADR-002;
**New**: contract, delivery hold, stage columns and the definer-function register come from
ADR-007, 010, 011.*

Every tenant-owned table has `tenant_id` and a row-level security policy; closed value sets are
`text` with a `CHECK`; high-volume tables are partitioned by day.

| Area | Tables | Notes |
|---|---|---|
| Tenancy and identity | `tenant`, `app_user` (issuer, subject), `role_grant`, `source_credential` (issuer, client id, status) | Directory under RLS with one bootstrap function (ADR-009) |
| Systems | `system` (code, name, direction, + `type`, `settings` from the registry phase), `target_endpoint` (timeout, circuit settings, error dictionary, idempotent-receive declaration, typed settings), `target_credential` (envelope-encrypted, ADR-007) | One systems table; settings validated per type |
| Contracts | `contract` (system, role), `contract_version` (label, status, JSON Schema, optional OpenAPI, derived-from, compatibility, author, approver) | The only home of every schema (ADR-011) |
| Flow configuration | `interface` (code immutable once published, mode `ASYNC`/`SYNC`, `ordering` `PER_KEY`/`NONE`, current and published version), `interface_version` (immutable snapshot `jsonb` of the whole bundle, business-key rule mandatory, change note, rejection comment, author, approver, `CHECK` approver is not author), `route` (target endpoint, condition, single primary for sync), `mapping` and its versions (one per route, bound to two contract versions), `retry_policy`, `rule_set`, `lookup_table`, `lookup_entry` | Lookup **values** are deliberately not versioned: immediate and audited (OQ-07 stays Dipesh's) |
| Intake | `message` (interface version, source contract version, business key, sequence number, stage timestamps), `payload`, `outbox` (`available_at`, `traceparent`), `idempotency_key` | Daily partitions for message and payload |
| Delivery | `delivery` (state, pinned target endpoint, contract and mapping versions, ordering scope, `next_attempt_at`, claim), `delivery_attempt` (append-only; layer, outcome class, timings), `delivery_event` (holds, releases, circuit deferrals, replays) | Daily partitions; attempts never updated or deleted |
| Ledger | Stage timestamps on message and delivery; `daily_aggregate` per tenant, interface, target, day | The report reads aggregates, which a seeded test proves equal to the rows |
| Audit | `audit_event` | Append-only; partition drop after 7 years |
| Scheduler | db-scheduler's task table; `job_definition`, `job_run` | Jobs are configuration rows |

**Stage timestamps** (*source: `ws/_specs/project-plan.md:118`*): received, validated, transform
start, transform end, sent, target responded, completed.

## 4. The message path

*Sources: ADR-005, ADR-010, ADR-011; `ws/_specs/project-plan.md:113-116`.*

### 4.1 Asynchronous flow (the default)

| Step | Where | What happens | Failure path - who finds out |
|---|---|---|---|
| 1 | ingest | Source calls `POST /api/v1/interfaces/{code}/messages[:bulk]` with its token | Invalid or revoked credential -> 401/403, never retried |
| 2 | tenancy | (issuer, client id) -> source system, customer, tenant via the client bootstrap function; body `customerId` cross-checked | Mismatch -> 403, audited |
| 3 | ingest | Size caps; per record contract validation then rules, collecting every violation; business key; idempotency | Over a cap -> 413, nothing stored; invalid records -> listed with all reasons |
| 4 | ingest | **One transaction:** message, payload and outbox rows for every valid record | Budget 2 s exceeded or database down -> 503, nothing stored; source repeats with the same idempotency key |
| 5 | ingest | Reply 202 with per-record results and tracking ids | - |
| 6 | ingest relay | Publishes each outbox row to `platform.message.accepted` (key = hash of tenant, interface, business key; `traceparent` restored) and marks it published | Kafka down -> rows stay, relay retries publishing; a crash after publish -> duplicate record, harmless (step 9) |
| 7 | routing stage 1 | Resolves the tenant from the message row (work function), loads the pinned interface version, evaluates route conditions | Unreadable record or missing row -> `platform.poison`, alert |
| 8 | routing stage 1 | **One transaction:** one Delivery per matched target (pinning endpoint, contract and mapping versions) plus one outbox row each | No route matches -> message completed with "no route", visible in the timeline |
| 9 | routing stage 2 | Consumes `platform.delivery.due`; claims the delivery by conditional update | 0 rows -> duplicate or held -> acknowledged, no effect |
| 10 | routing stage 2 | Ordering check under an advisory lock on the ordering scope | Earlier same-key delivery not final -> `HELD`, visible on the dashboard |
| 11 | transform | Mapping version applied to the payload (5 s budget) | Mapping error -> `PERMANENT`, dead-lettered with the reason |
| 12 | routing stage 2 | Target route sends with timeout, breaker and layer-1 redelivery of transient failures; each call writes one attempt row | Circuit open -> no call, `RETRY_WAIT`; transient after layer 1 -> `RETRY_WAIT`; ambiguous -> retry only if the target declares idempotent receive, else dead-lettered `AMBIGUOUS_TIMEOUT`; permanent -> dead-lettered |
| 13 | delivery | Polling trigger moves due `RETRY_WAIT` rows to `DUE` with an outbox row -> back to step 9 | Instance crash mid-call -> stale claim found after the target timeout + 60 s, classed ambiguous |
| 14 | delivery | `DELIVERED` or `DEAD_LETTERED`; the next held same-key delivery is released | Dead letter -> operator replay later, with the versions pinned at arrival, audited |

### 4.2 Synchronous single-target flow

Steps 1-4 as above, with the delivery's outbox row deferred (`available_at` = now + 10 s + 5 s).
The request thread then runs steps 7-12 inline for that one delivery, making exactly one target
call with no redelivery. Success -> 200 with the target's response mapped to the source's
response contract; permanent failure -> the mapped error; anything else or the 10 s budget ->
202 with the tracking id and status `PENDING`, and the delivery continues on the asynchronous
path. If the instance dies mid-call, the deferred outbox row re-drives it.

### 4.3 Configuration publish

| Step | What happens | Failure path |
|---|---|---|
| 1 | Configurator edits a draft (never live) | - |
| 2 | Submits with a change note -> `PENDING_APPROVAL`; snapshot copied | No change note -> refused |
| 3 | Approver approves - never the author (database `CHECK` and 403) | Author approves -> refused |
| 4 | Publish: interface, routes and mappings in one transaction | Any part fails -> nothing changes |
| 5 | Data plane picks the new published version on the next message; routes are created lazily on first use, on any instance | - |

*Source for 4.3: deleted workflows.md W8 and integration-platform-design.md P8, quoted at
`ws/_specs/f3-plan-brief.md:52-55`, `:62-65`.*

## 5. Tenant isolation - the controls (closed register)

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

## 6. Deployment view

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

- **Resilient integration:** every call that leaves the process carries timeout, transient-only
  retry with backoff, jitter and a cap, one circuit breaker per dependency, a fallback, and
  metrics plus a trace span (ADR-010 section 8 for targets; ADR-007 for Keycloak).
- **Observability:** one correlation id and the W3C trace context follow a message through
  intake, Kafka, Camel, transform, delivery and response; the outbox row carries `traceparent`.
  Logs are structured JSON with tenant and message ids; masked fields never appear.
- **Outbound destination controls:** allow-list; private and loopback ranges refused after DNS
  resolution and after every redirect; test-connection audited and rate-limited.

## 8. Decisions

See the [ADR index](adr/README.md).

Diagrams (architecture, end-to-end flow, configuration workflow, delivery states): [diagrams/README.md](diagrams/README.md).
