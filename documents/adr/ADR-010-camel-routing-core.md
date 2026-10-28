---
type: ADR
title: "ADR-010 - Apache Camel as the routing core, inside one module, with exactly two retry layers"
status: "Accepted (team, 2026-10-02) - Dipesh may reverse"
date: 2026-10-02
revised: "2026-10-02 - vikram-cto's challenge applied (memory m-817272): per-target bulkhead, idempotent stage 1, claim order and token, outcome table, nothing-stays-stuck section. 2026-10-02 - vikram-cto's re-check applied (memory m-6a410c): bulkhead-full backoff and re-drive cap, every state change takes the key lock first with 40001/40P01 retry, missing key uses no attempt, not-ready instance stops its consumers, broker-address check and egress policy. 2026-10-12 - amended with the approval of ADR-014 (sections 2 and 6: one delivery per message and route, unique on (message_id, route_id, created_at) with created_at = the message's accepted_at, message and payload day-partitioned) and ADR-015 (section 1: target adapters and the send-time destination check live in platform-routing; routing depends on platform-config's public API only), both approved by Dipesh 2026-10-12. 2026-10-17 - section 8: the dev/showcase-only exception to the destination check (T138; property platform.config.destinations.showcase-local-hosts, honoured only under the showcase profile, fail closed, never in production; AC approved by Dipesh 2026-10-17, memory m-b39c08). 2026-10-17 - section 3 amended for finding F16 (row 278): publish validates and never builds; routes are built lazily in stage 2, keyed by (target endpoint id, endpoint version = fingerprint of the row values a template is built from); the idle sweep is scheduled. 2026-10-18 - section 8 re-pinned to main a2c76ff and the B21 port pin added (host AND port, ports required with hosts, 80/443 default, IP literals refused, refused under prod/production/staging/uat); section 3 gains a status line of what main implements. 2026-10-27 - section 8 note: the runtime-editable global allow-list (operator_allowed_host, V2_4_1410) is removed (PRs 36, 37, 46; H-1), table dormant, application writes revoked by V2_4_1440; decision of 2026-10-19 unchanged"
author: "mahesh-architect (ledger row 278, phase 0)"
project: ipass/integration-platform
provenance: new-decision
supersedes: ADR-008-camel-adapter-only.md
related:
  - ADR-008-camel-adapter-only.md
  - ADR-005-messaging.md
  - ADR-011-targets-contracts-bulk-ordering.md
  - ADR-007-identity.md
  - ADR-001-modular-monolith.md
---

# ADR-010 - Apache Camel as the routing core, inside one module, with exactly two retry layers

Supersedes [ADR-008](ADR-008-camel-adapter-only.md). Source paths starting `ws/` are in the hub
repository, project workspace `_personal/_project_ipass/integration-platform/`. Memory lines
drift; the id is the stable key.

> **Revision 2026-10-02 (challenge m-817272).** vikram-cto's challenge found four blockers in
> this record: stage 2 sent on the Kafka partition thread (B1), stage 1 was not idempotent (B2),
> the ordering hold missed earlier unrouted messages (B3, fixed in ADR-011 Decision 6), and stuck
> work had no path back (B4). This revision fixes all four (sections 2, 6, 7, 8, 9) and applies
> the "should" items on outcome classes (S-a), the stale-claim limit (S-b), outbound destinations
> (S-c), the claim order (S-g) and the half-open re-drive cap (S-i). Section numbers are unchanged;
> sections 9 and 10 are new.

## Context

*Sources: `ws/_memory/shared.md:25` (m-703833), `:29` (m-6b1152), `:19` (m-273754);
`ws/_specs/project-plan.md:24`, `:29`, `:73`, `:78`, `:86`, `:114`, `:201`.*

Dipesh's binding technology direction (2026-10-02) makes Apache Camel the integration, routing,
transformation and EIP technology, and pulls CSV-over-queue-manager, DB and File targets into
v1. That is ADR-008's own reopen trigger ("v2 scope pulled into v1").

ADR-008 warned of two things that remain true: a framework's in-flight retry is not the
persisted, two-weeks-later, version-pinned retry the product needs; and a second retry
mechanism makes the "report equals the delivery rows" promise hard to keep. The internal
challenge of the plan found the earlier draft had **three** retry and dead-letter layers (the
Delivery state machine, Kafka retry and dead-letter topics, the scheduler) contradicting
report-equals-rows (m-273754, point 3).

**Premise decomposition - what does Camel actually replace?**

| Concern | Owner after this ADR | Why |
|---|---|---|
| Talking to targets (HTTP, JMS, SQL, file, Kafka) | **Camel components** | Mature, tested components; four target types in v1 |
| Fan-out to several targets, dispatch per target | **Camel routes and route templates**, driven by our configuration rows | Config-driven routes at runtime are a verified Camel feature (`ws/_specs/project-plan.md:78`) |
| In-attempt redelivery (seconds) | **Camel error handler** (retry layer 1) | Cheap, local, inside one attempt |
| Long-horizon retry, dead letter, replay, version pinning | **Our Delivery state machine** in PostgreSQL (retry layer 2) | ADR-008 reasons 1-2 still hold |
| Field mapping | **Not Camel** - the transform module calls the JSONata engine directly (`ws/_specs/project-plan.md:82`) | Keeps Camel inside routing; ADR-006 decides the engine |
| Validation and rules | **Not Camel** - JSON Schema validator and ZEN (ADR-003, ADR-011) | Runs at intake, before Kafka |
| Circuit breaking | Resilience4j 2.4.0 (managed by Camel 4.22.1), one breaker per target endpoint | `ws/_specs/project-plan.md:86` |
| Concurrency per target | **A bounded bulkhead per target endpoint** (section 2) | One slow target must not occupy the Kafka partition threads other targets need (challenge B1) |

## Options

| Option | Complexity | Risk | Reversibility | Verdict |
|---|---|---|---|---|
| **A. Camel as the routing core, contained in `platform-routing`; retry split into exactly two layers** | Medium | Team new to Camel in production | Medium: routes sit behind our ports | **Chosen** |
| B. Keep ADR-008: Camel only behind an HTTP outbound adapter port, our own dispatcher | Lower | Four target types and fan-out hand-written | High | Rejected: contradicts the binding direction and the v1 scope |
| C. Camel everywhere, retry via Kafka retry topics and Camel's dead-letter channel to a Kafka queue | Medium | Three retry layers; retry history lives in topics, not rows; replay cannot pin versions | Low | Rejected: breaks report-equals-rows and two-weeks-later replay |
| D. Spring Integration instead of Camel | Medium | Fewer ready target components | Medium | Rejected: contradicts the binding direction |

**Where stage 2 sends from (challenge B1):**

| Option | For | Against | Verdict |
|---|---|---|---|
| Send on the Kafka partition thread (the first draft) | Simplest; offset commit after send | A target with a 10 s timeout blocks every other key on that partition, whatever its target; a slow target lowers every target's throughput; long sends push the consumer past its poll interval and trigger rebalances | Rejected |
| **Claim on the partition thread, send on a bounded per-target bulkhead** | One slow target uses only its own pool; partition threads stay fast; the offset is committed after the claim | The offset no longer proves the send finished - a crash mid-send is recovered from the database (stale claim, section 6), not from Kafka | **Chosen** |
| One Kafka topic or consumer group per target | Isolation by topic | Topics multiply with configuration rows; breaks "0 deploys to add a target" | Rejected |

## Decision

### 1. Versions and containment

- **Apache Camel 4.22.1 (LTS)** with `camel-spring-boot` 4.22.1 on Spring Boot 4.1.1 and
  Java 25 (`ws/_specs/project-plan.md:73`). Starters used: kafka, jms, csv, bindy, sql, file,
  http, resilience4j, micrometer, opentelemetry2 (`:78`). No others without an ADR update.
- **`org.apache.camel` is used only in `platform-routing`.** An ArchUnit rule fails the build
  otherwise, proven by a planted violation. Other modules talk to routing through ports
  (interfaces) owned by the calling module.
- **Target adapters live in `platform-routing`** (`routing/adapter/**`, ADR-015 Decision 1,
  amended 2026-10-12): the route templates per target type, the adapters that send, the per-target
  bulkheads and circuit breakers and the send-time destination check (section 8). Outcome
  classification and retry timing stay in `platform-domain` and `platform-delivery`, which never
  send.
- **`platform-routing` depends on `platform-config`'s public API only** (ADR-015 Decision 2): the
  read-only API over published versions, the sealed target credentials, and config's one
  `DestinationGuard`, reused for the send-time check. Never config internals; the arch-test rule is
  written in E10's wave-0 seam.

### 2. Two stages, both consumers of Kafka work topics (ADR-005)

| Stage | Consumes | Does | Never does | Budget |
|---|---|---|---|---|
| **1 - route and fan out** | `platform.message.accepted` | Resolves the tenant from the message row (ADR-011), loads the routes of the interface version pinned on the message, evaluates routing conditions, and in **one transaction**: takes the ordering-key lock (ADR-011 Decision 6), moves the message `ACCEPTED -> ROUTED` by conditional update (**0 rows = a duplicate record: acknowledged, no effect**), inserts one Delivery per matched target with `ON CONFLICT (message_id, route_id, created_at) DO NOTHING`, where `created_at` is the message's `accepted_at` (ADR-014) - each pinning its target contract version and mapping version - plus one outbox row per inserted delivery, and releases any delivery held only behind this message | Calls a target; creates a second delivery for the same (message, route) | 1 s (`ws/_specs/project-plan.md:114`) |
| **2 - deliver** | `platform.delivery.due` | **On the partition thread:** takes a permit from the target's bulkhead (non-blocking), then the claim transaction of section 7 (lock, hold check, `HELD` or `SENDING`), then commits the offset. **On the target's bulkhead thread:** transforms through the transform port, sends through the target's route, records each attempt, classifies, moves the state | Sends on the Kafka partition thread; decides retry timing outside the database | Claim under 1 s; transform 5 s; target call within its configured timeout |

**Message states (closed register):** `ACCEPTED` (intake committed) -> `ROUTED` (stage 1
committed, 0 or more deliveries; 0 shows "no route" in the timeline) or `ROUTE_FAILED` (the
pinned configuration cannot be read - permanent, alerted). `ROUTED` and `ROUTE_FAILED` are final.
The unique key `delivery (message_id, route_id, created_at)` - unique per message day, because
`created_at` is the message's `accepted_at` and so fixed by `message_id` (ADR-014, amended
2026-10-12) - makes a second stage-1 run harmless even if the conditional update were bypassed.
Message and payload are partitioned by day on `accepted_at`, and each delivery sits in its
message's day partition, so one day is created, guarded and dropped as one unit (ADR-014
section 3). **Gate:** the same `message.accepted` record published twice
creates N deliveries for N matched routes, not 2N (test).

**The per-target bulkhead (challenge B1):**

- One bounded executor per (target endpoint, endpoint version) on each instance; pool size is a
  typed target setting `max_concurrent_sends` (team default 4, validated 1-32), queue length 0.
  The implementation (a Resilience4j thread-pool bulkhead or a Camel thread-pool profile) is a
  build choice; the contract is: bounded, non-blocking acquire, no hidden in-memory queue.
- The permit is taken **before** the claim. No permit -> no claim: in one transaction (the
  ordering-key lock first, section 7) the delivery gets a `BULKHEAD_FULL` delivery event and
  moves `PENDING`/`DUE` -> `RETRY_WAIT` by an update **conditional on `status IN
  ('PENDING','DUE')`** - 0 rows means another consumer already claimed or moved it, and the
  record is acknowledged with no effect - then the offset is committed. `next_attempt_at` = now
  + a backoff that **starts at 1 s and doubles with each consecutive `BULKHEAD_FULL` of that
  delivery, capped at 30 s**, with full jitter (configurable; the count `bulkhead_deferrals`
  is reset to 0 by a successful claim). **No call is made, so no attempt row is written** and
  the attempt count is not consumed.
- **Per-target re-drive cap:** each run of the delivery-retry job moves at most
  `max_concurrent_sends` x the configured instance count bulkhead-deferred deliveries per target back to
  `DUE`; the rest stay in `RETRY_WAIT` for the next run, so a full target is not flooded with
  records it would only defer again.
- A claim that ends in `HELD` or finds a duplicate (0 rows) returns the permit at once.
- **Addendum 2026-10-22 - slot-free re-drive (E18 drain defect, T-153).** The backoff above never
  applies on its own: only the `DELIVERY_RETRY` job re-drives a deferred delivery (60 s cadence,
  the cap above per run), so a deferral cost at least one job period - drain times of 11 s, 54 s
  and 260 s on the same code, all of it tail. Now, **when a send ends and its permit is about to
  return, the same bulkhead thread, still under the delivery's tenant binding, re-drives ONE
  bulkhead-deferred `RETRY_WAIT` delivery of the same target** (`bulkhead_deferrals > 0`, oldest
  `next_attempt_at` first, `FOR UPDATE SKIP LOCKED`, the backoff time ignored: the wait was for this
  slot): `RETRY_WAIT -> DUE` through the same conditional update as the job, and its `DELIVERY_DUE`
  outbox row in one transaction. One freed permit moves at most one row, so the per-target cap
  holds and there is no loop: a re-driven record runs again only by taking a permit like any
  other. A failing re-drive is logged and swallowed and never fails the finished send. Ordering
  (HELD at the claim), idempotency (claim token, duplicate detection) and tenant isolation (the
  re-drive runs under the bound tenant; RLS scopes the select) are unchanged. **The retry job
  remains the safety net** (a missed re-drive, a crash between the send and the re-drive, an
  instance restart); the 1 s to 30 s backoff is now the backstop wait, not the normal path.
  Test: `DeliveryDueDispatchTest` (full bulkhead, deferral, slot freed, re-driven at once) and
  `JdbcBulkheadRedriveContainerTest` (the real tables).
  **Consequences of the addendum (T-177 follow-ups, Varun F2/F3/F5/F6):**
  - **The backoff no longer sheds load against a slow partner.** The 1 s to 30 s `BULKHEAD_FULL`
    backoff used to space the retries of a deferred delivery; the slot-free re-drive ignores it, so a
    slow or hanging target is no longer relieved by waiting. Only the **per-target bulkhead** (the
    permit cap, a re-driven record must still win a permit) and the **circuit breaker** protect it.
  - **No re-drive chain on an open breaker.** A send that ended as `CIRCUIT_DEFER` (nothing was
    sent) does not re-drive; the retry job stays the only path until the breaker lets a call through.
  - **HELD and duplicate claims hand the turn on**, at most 8 in a row per target (reset by a real
    send); beyond that the retry job takes the tail.
  - **Operability:** the re-drive writes `REDRIVE` with the detail `slot-free` (the job writes none);
    a failed re-drive logs one WARN line without a stack trace (stack at DEBUG).
- The pool is per instance: the effective limit for a target is pool size x instances.
- **Gate:** with target A answering only after its 10 s timeout, target B delivers the same
  number of messages in the same wall-clock time as with target A idle (500 deliveries to B,
  completion time differs by less than 1 s; test).

Deliveries are independent from creation: one target failing never blocks or rolls back
another (best-effort, OQ-09), except the same-key hold of ADR-011, which is per target.

### 3. Routes are built at runtime from configuration, lazily, on every instance

- One **route template per target type** (API, MQ-CSV, DB, File, Kafka topic). A route instance
  is created from the template for a (target endpoint id, endpoint version) pair **the first
  time a delivery needs it**, on whichever instance consumes it, and removed after 30 minutes
  idle. Publishing a new interface or target therefore needs 0 deploys and 0 restarts, and no
  broadcast between instances. The route instance, its bulkhead and its breaker are deliberate
  local state: rebuilt on demand, never authoritative (ADR-001).
- **Endpoint URIs are never free text.** Template parameters come from typed target settings
  validated against the per-type schema (ADR-011). Stage 2 dispatches with `toD` whose
  `allowedSchemes` is `direct` only, so configuration cannot reach an unapproved component.

**Amendment 2026-10-17 (finding F16, ledger row 278): where the build happens and what the key
is.** The code at `4b3516b` builds every route at publish, inside the publish transaction, and
caches it by target endpoint id alone (`RouteInstanceFactory.java:49`, `:77-83`, `:98-101`,
`:120`; called by the `BEFORE_COMMIT` listener `InterfacePublishedRouteListener.java:29-34`).
A Camel registration is not part of the transaction, so a refused later row or a failed commit
leaves live, cached routes behind (finding F14). Decision:

- **Publish validates; it never builds.** For every active route row of the interface, the
  publish path runs the scheme check (`allowedSchemes`) and the template-exists check. A refusal
  fails the publish and rolls it back. Publish adds 0 Camel routes and writes 0 cache entries,
  so a failed publish leaves nothing to undo.
- **The build is lazy, in stage 2.** The consuming instance builds the instance in the send
  path the first time a delivery needs the pair, on any instance, with no broadcast (as above).
- **The key is (target endpoint id, endpoint version).** The schema has no endpoint version
  column, and `target_endpoint` is not updated in place (`V2_2_1__config_systems.sql:23-45`).
  So the *endpoint version* is a deterministic fingerprint of every row value a template is built
  from: target type, endpoint URI, contract version id and mapping version id. A changed value
  is a new key, so the next delivery builds a new instance and nothing is rebuilt over a live one.
  The old instance drains and the idle sweep removes it. The Camel route id and the `direct:`
  consumer both carry the key, so two versions of one endpoint never share a consumer.
- **Tenants.** Endpoint ids are globally unique primary keys, and the row is read under the
  delivery's tenant (row-level security), so a key cannot alias across tenants. The instance
  holds no tenant state; the tenant travels with the delivery.
- **The 30-minute idle removal runs on a schedule.** It is a claimed control, so it has a caller
  and a test.
- **Gives up:** a fault that only appears when Camel registers the route is found at the first
  send, not at publish (that delivery dead-letters `PERMANENT`), instead of failing the publish.
  Accepted: scheme and target type, the faults configuration can cause, are still refused at
  publish.
- **Gates (counts):** publishing 2 valid rows adds 0 routes. A publish of [valid, refused] fails
  and adds 0 routes. 2 deliveries for one key build 1 instance. A changed mapping version builds
  a 2nd instance and leaves the 1st untouched. An instance idle 31 minutes is removed (0 left);
  one touched at 29 minutes stays (1 left).
- **Status on main `a2c76ff` (code facts, `platform-routing/.../templates/RouteInstanceFactory.java`).**
  Landed (T106, `08fe511`): publish validates and builds nothing (`:78-82`), and the build is lazy
  in `ensure` (`:89-112`). Not yet in code: the cache and the Camel route id are still keyed by
  target endpoint id alone (`:50`, `:91`, `:100`, `:109`), so the (endpoint id, endpoint version)
  key is open; `evictIdle` exists (`:151`) but nothing in `src/main` calls it on a schedule, so
  the scheduled idle sweep is open.

### 4. Exactly two retry layers

**Layer 1 - Camel in-attempt redelivery (seconds).** Camel's error handler redelivers only
**transient** failures (table in section 5): at most 2 redeliveries, 500 ms initial delay,
doubling, with jitter. Of the HTTP 408/500/502/504 family, only codes from a target that
**declares idempotent receive** (an `Idempotency-Key` rides every try, ADR-011 Decision 1) are
layer-1 eligible; without the declaration they are `AMBIGUOUS` and never redelivered. **The 5 s cap is delay time only** - the sum of the waits between tries
never passes 5 s; each try's own duration is bounded by the target timeout. A redelivery is
also skipped when it would pass the remaining synchronous budget, or when the target sent a
`Retry-After` longer than the remaining layer-1 delay. Each try - the first and each redelivery -
is a call that left the process and **writes one `delivery_attempt` row** (`layer = 1`). When
layer 1 ends without success, Camel hands the exchange to our outcome classifier through a
`direct:` endpoint; Camel's dead-letter channel is never pointed at a queue or topic.

**Layer 2 - the Delivery state machine (minutes to days), persisted.** The classifier moves the
delivery, by an update conditional on the claim token (section 7):

- retryable and attempts remain -> `RETRY_WAIT` with `next_attempt_at` = now + exponential
  backoff with full jitter, capped (per-route `retry_policy`; team defaults 8 attempts,
  30 s base, 1 h ceiling - configuration, not figures); when the target sent `Retry-After`,
  `next_attempt_at` is the later of the backoff and `Retry-After`, still capped by the ceiling;
- retryable and attempts exhausted, or permanent -> `DEAD_LETTERED` with the reason class.

A polling trigger (phase 2; moved onto db-scheduler as the **delivery-retry job** in the
scheduler phase) selects due `RETRY_WAIT` rows with `FOR UPDATE SKIP LOCKED`, moves them to
`DUE` and writes an outbox row, which the relay publishes to `platform.delivery.due`. Retry
timing lives only in the database, so it survives restarts. The same job runs the reconciler
of section 9, so **the reconciler adds no job of its own**. Credential clean-up (pending and
`idp_disable_pending` credential rows, ADR-007 Decision 3) is its own scheduled job (Mira's
decision, memory m-c14c49).

**Kafka retries nothing.** A record that cannot be read, or whose row does not exist, goes once
to `platform.poison` and is never retried from there (ADR-005). A transient database error is
not a poison case: the consumer pauses and backs off (section 9).

**Scope of "exactly two":** the two layers are the only places a **delivery to a target** is
retried. The outbox relay re-publishing a row Kafka did not acknowledge, the reconciler
re-publishing stuck work (section 9), and the bounded retry of an idempotent read from Keycloak
(ADR-007), are infrastructure calls with their own five controls; they never call a target and
never write an attempt row.

**The report equals the rows:** every call that left the process is exactly one attempt row;
"retries made" for a delivery = its attempt rows minus 1; a circuit-open deferral, a
bulkhead-full deferral and a missing-key deferral are not calls and are recorded as delivery
events, not attempts.

### 5. Outcome classes (closed register)

This table is the **default**. A target's error dictionary may override the class of a status
code or error code for that target (for example, a target documenting that its 500 means "not
processed" may mark it `TRANSIENT`); an override can never make 401 or 403 retryable. (Request
validation failure is a `PERMANENT` row of this table, not a retry-class override.)

| Class | Examples | Layer 1 | Layer 2 |
|---|---|---|---|
| `SUCCESS` | HTTP 2xx; JMS send acknowledged; DB commit; file renamed into place | - | `DELIVERED` |
| `TRANSIENT` | connection refused or reset **before the request was sent**; HTTP 429 and 503 (honouring `Retry-After`); HTTP **408, 500, 502, 504 from a target that declares idempotent receive**; broker unavailable; DB serialization failure or deadlock (40001, 40P01); target folder temporarily unavailable | redeliver | `RETRY_WAIT` |
| `AMBIGUOUS` | a timeout after the request was sent; a connection lost while awaiting the reply; HTTP **408, 500, 502, 504 from a target that does not declare idempotent receive** (the request may have been processed) — **no other 408/500/502/504 case is AMBIGUOUS: with the declaration the codes are `TRANSIENT` (row above), so layer 2 is `RETRY_WAIT` and never this row's `AMBIGUOUS_TIMEOUT` dead-letter**; a DB commit outcome unknown | **never redeliver** | `DEAD_LETTERED` with reason `AMBIGUOUS_TIMEOUT` for an operator decision |
| `PERMANENT` | HTTP 400, 404, 409, 422; **401, 403**; mapping or contract failure; destination refused by the destination check (section 8); **target credential authentication-tag mismatch** (ADR-007) | never | `DEAD_LETTERED` |

A timeout is treated as ambiguous, never as failed (`ws/_specs/project-plan.md:115`).

**A target credential key id unknown on this instance (S-d)** is found when the credential is
decrypted, before any call leaves the process (ADR-007 Decision 7). It is not an outcome class:
**no attempt row is written and no attempt is used up**; the delivery gets a `KEY_UNAVAILABLE`
delivery event and moves `SENDING` -> `RETRY_WAIT` (conditional on the claim token) with the
bulkhead-full backoff of section 2 — its wait is that same series (1 s doubling to a 30 s cap,
full jitter; the numbers, not the mechanics: section 2's deferral updates rows `WHERE status IN
('PENDING','DUE')` and this delivery is `SENDING`, so it moves conditional on the claim token
and counts its own `key_unavailable_deferrals`, reset by a send that completes). A missing key
is a deployment fault fixed by the alert and the readiness gate below, never an unbounded
silent spin: an alert fires and the instance fails its readiness check (section 9 item 4), so
an instance that holds the key sends it.

**Addendum 2026-10-20 - E10 scenario-authoring settles A1-A3** (backlog.md:2685, :2774, :2790;
no second mechanism anywhere):

- **A1, "policy ceiling"** is the same route `retry_policy` ceiling as the section-4 layer-2
  bullet (team default 1 h, configuration): `next_attempt_at = min(now + backoff, ceiling)`;
  a `Retry-After` past the remaining ceiling is **not** a dead-letter - the row simply becomes
  due at the ceiling.
- **A2, the `KEY_UNAVAILABLE` wait** is exactly section 2's series - 1 s doubling to the 30 s
  cap, full jitter - reused as the *one* number; only the counter is distinct
  (`key_unavailable_deferrals`, per T-200). There is no 10-minute backoff and no second
  backoff in this ADR.
- **A3, "an alert fires"** is asserted as the Micrometer counter
  `platform.delivery.key_unavailable` (+1 per event, the named metric of section 8's set) plus
  the readiness-DOWN check; the paging rule itself lives in the deployment's observability
  stack, not in the acceptance criterion.

### 6. Delivery states (closed register; phase 2 implements it, illegal transitions are rejected)

| From | To | Trigger |
|---|---|---|
| (new) | `PENDING` | stage 1 inserts the delivery (`ON CONFLICT (message_id, route_id, created_at) DO NOTHING`, `created_at` = the message's `accepted_at`, ADR-014) |
| `PENDING`, `DUE` | `HELD` | the claim transaction finds the ordering hold applies (ADR-011 Decision 6): an earlier message with the same key is still `ACCEPTED`, or an earlier delivery to the same target is not final |
| `HELD` | `DUE` | in the **same transaction** that makes the blocking predecessor final (`DELIVERED` or `DEAD_LETTERED`) or moves the earlier message to `ROUTED` / `ROUTE_FAILED`; or the reconciler (section 9) finds no blocker left |
| `PENDING`, `DUE` | `SENDING` | stage 2 claims it (section 7), with a new claim token |
| `PENDING`, `DUE` | `RETRY_WAIT` | the target's bulkhead is full (section 2) - no call, a `BULKHEAD_FULL` event |
| `SENDING` | `DELIVERED` / `RETRY_WAIT` / `DEAD_LETTERED` | the classifier, conditional on the claim token |
| `SENDING` | `RETRY_WAIT` | the target credential key is not on this instance (section 5) - no call, no attempt, a `KEY_UNAVAILABLE` event |
| `SENDING` | `RETRY_WAIT` or `DEAD_LETTERED` (as `AMBIGUOUS`) | a stale claim: claimed longer than **3 x the target timeout + the layer-1 delay cap (5 s) + 60 s** (instance crash mid-call), found by the delivery-retry job |
| `RETRY_WAIT` | `DUE` | `next_attempt_at` reached (delivery-retry job); after a bulkhead-full deferral, at most `max_concurrent_sends` x the configured instance count per target per run (section 2) |
| `RETRY_WAIT` | `DUE` | circuit half-open time reached after a circuit-open deferral — **at most the breaker's trial-call count per target** (`half_open_trial_calls`, the target's settings field, ADR-011 Decision 1; Resilience4j `permittedNumberOfCallsInHalfOpenState`) per half-open window; the rest wait until the breaker closes — **and if the breaker re-opens before it closes, the next delivery-retry pass re-drives at most the trial-call count again** (A4: **every `RETRY_WAIT` -> `DUE` move in this table is the delivery-retry job's**, whether
the trigger is the half-open timer or the pass itself - so the backlog scenario's event
assertion is the same rows-whatever-moved set on both paths; otherwise the remainder waits
forever on a target that never flips to CLOSED) |
| `DEAD_LETTERED` | `DUE` | operator replay - audited; uses the versions pinned when the message arrived |

Terminal: `DELIVERED`. `DEAD_LETTERED` is terminal until an operator replays it.

The stale-claim limit covers the longest legal attempt: three tries (the first and two
redeliveries) each up to the target timeout, the layer-1 delay cap, and 60 s for clock skew and
commit time. A late result from a call whose claim was already declared stale finds a different
claim token (or none): it writes its attempt row (it was a call) and a `LATE_OUTCOME` delivery
event, and changes no state.

### 7. One claim order; duplicates are detected, not sent (challenge S-g)

The claim is **one transaction, always in this order**:

1. take the transaction-level advisory lock on the delivery's ordering key (ADR-011 Decision 6;
   skipped when the interface's ordering is `NONE`);
2. read the delivery `FOR UPDATE` where `status IN ('PENDING','DUE')` - **0 rows means a
   duplicate Kafka record or a delivery already held, claimed or final: the record is
   acknowledged with no effect** and the bulkhead permit returned;
3. evaluate the hold rule; if it applies -> `HELD` with a `HOLD` delivery event, permit returned;
4. otherwise -> `SENDING` with a fresh `claim_token` (UUID) and `claimed_at`;
5. commit; then the Kafka offset is committed and the work is handed to the bulkhead.

Every later update of that delivery's state by the send path is conditional on
`status = 'SENDING' AND claim_token = ?`. A crash between commit and publish re-publishes the
outbox row; the claim makes the duplicate harmless. The synchronous inline path (ADR-011
Decision 5) uses exactly this transaction.

**Every state-changing transaction takes the ordering-key lock first (S-g),** not only the
claim: stage 1 (`ROUTED` / `ROUTE_FAILED`), the bulkhead-full deferral, the classifier's outcome
update, the release (section 9), the delivery-retry job's moves (`RETRY_WAIT -> DUE`, stale
claim), the reconciler and an operator replay. Inside the lock, rows are locked in one order:
the message, then its deliveries in id order. The lock is skipped only when the interface's
ordering is `NONE`. A **serialization failure (40001) or deadlock (40P01)** rolls the
transaction back; it is retried in place up to 3 times with jittered backoff (it is not a target
call, so no attempt row), then the consumer pause of section 9 item 3 applies. Test: two
transactions on one key in opposite row order both finish, with 0 lost updates.

### 8. The six controls on every target call

The resilient-integration rule's five controls, plus a bulkhead (challenge B1):

| Control | How |
|---|---|
| Timeout | Per target endpoint, configurable (figure: per-target timeout configurable); connect and read timeouts on HTTP, send timeout on JMS, query timeout on SQL |
| Retry of transient failures only | Layer 1 as above, transient class only, backoff with jitter and a 5 s delay cap; `Retry-After` honoured; never 401/403, validation, ambiguous or non-idempotent |
| Circuit breaker | One Resilience4j breaker per target endpoint; open -> no call, the delivery waits in `RETRY_WAIT` until half-open time; at half-open at most the trial-call count is re-driven per target |
| Fallback | The Delivery state machine: `RETRY_WAIT` or `DEAD_LETTERED`; nothing is ever dropped silently |
| Observability | Micrometer metrics per target (attempts by outcome, latency histogram, breaker state, bulkhead in use and `BULKHEAD_FULL` count); an OpenTelemetry span per call (`camel-opentelemetry2`); structured log line with tenant, message and delivery ids |

**Metric note (2026-10-20, E10):** the per-target metric set above **is** the bulkhead-in-use
gauge and the `BULKHEAD_FULL` counter — required of every adapter, and counted for E10's
target scope as six controls, the bulkhead being the sixth of section 8 (built in E9,
T-177/T-198).
| **Bulkhead** | One bounded pool per target endpoint per instance (`max_concurrent_sends`, default 4, queue 0); full -> a `BULKHEAD_FULL` event and `RETRY_WAIT`, no call made (section 2) |

**Outbound destinations - one check for every target type (challenge S-c).** The same check runs
on the host field of every target type (API URL, MQ connection host, DB host, Kafka bootstrap
servers) at save, at test-connection and at send, and on **every broker address the client
connects to** - for Kafka, each broker the cluster metadata returns, checked before the client
connects to it:

- the host is resolved once; **every** resolved address is checked; the connection is made to
  the checked address (the IP is pinned - no second resolution, so DNS rebinding cannot swap
  it), with the original name kept for TLS SNI and certificate checks;
- refused ranges: loopback (127.0.0.0/8, ::1), "this network" (0.0.0.0/8), private
  (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16), link-local including cloud metadata
  (169.254.0.0/16, fe80::/10), carrier-grade NAT (100.64.0.0/10), IPv6 unique-local
  (fc00::/7), IPv4-mapped IPv6 (::ffff:0:0/96 - checked as the embedded IPv4 address),
  multicast and broadcast, and **the platform's own hosts** (its instances, PostgreSQL, Kafka,
  Keycloak, the observability stack - a configured list);
- the allow-list is **per tenant but maintained by the platform operator**, never by the tenant;
  a refused private range can be reached only through an operator allow-list entry naming that
  exact host and address range (audited) - needed for a client's on-premises queue manager or
  database. Loopback, link-local and the platform's own hosts can never be allow-listed;
- redirects are off unless the target enables them, and every hop is re-checked;
- **where a driver resolves and connects on its own** (the Kafka client after bootstrap, JDBC
  drivers, JMS connection factories) and the checked address therefore cannot be pinned, the
  deployment's **egress network policy** enforces the same refused ranges and the platform's own
  hosts for the outbound traffic of the instances, so a re-resolution cannot reach them. Open
  research question for neha-research: which of the chosen drivers (Kafka client, the JDBC
  drivers of the DB target, the allow-listed JMS factories) accept a custom resolver or socket
  factory, so the checked address can be pinned in-process as well;
- **File targets:** the path is normalised and must stay under the target's configured root
  after resolving `..` and symbolic links (real-path prefix check); anything else is refused.

A refused destination is `PERMANENT` (`DESTINATION_REFUSED`). Test: one refused case per range,
per target type, plus a rebinding case (first lookup public, second private) and a `../` and a
symlink escape for File.

**Dev/showcase-only exception to the destination check (T138; acceptance criteria approved by
Dipesh 2026-10-17, memory m-b39c08).** This is a named **exception** to the check above - to the
sentence "Loopback ... can never be allow-listed" and to the audited exact-range rule for private
ranges - and it exists only so the showcase can reach a local test destination. It is **never
enabled in production**, nor in any environment other than a dev or showcase run. Code paths below
are on main `a2c76ff` (T138 + B21 landed through seam `b7a23f3`). Paths: `DestinationGuard.java`
is `platform-config/src/main/java/io/integrationplatform/config/destination/`,
`SystemPersistenceConfiguration.java` is
`platform-config/src/main/java/io/integrationplatform/config/internal/persistence/`,
`config-systems.yml` is `platform-config/src/main/resources/config/`.

- **Properties.** `platform.config.destinations.showcase-local-hosts` (a list of exact host
  names) and `platform.config.destinations.showcase-local-ports` (a list of exact ports) are
  honoured only while the Spring profile **`showcase`** is active
  (`SystemPersistenceConfiguration.java:55-57`, `:81-84`, `:88-91`). The shipped
  `config-systems.yml` sets neither (`config-systems.yml:10-16`: `allowed-hosts: []` and comments
  only); they are set only for a showcase run.
- **What it lifts - and nothing else.** For a name on the hosts list, **on a port on the ports
  list**, a resolved address in the `loopback` or `private` range is not refused
  (`DestinationGuard.java:46-47`, `:113`, `:120-125`). It never widens
  `platform.config.destinations.allowed-hosts`: the name must still pass the allow-list first
  (`DestinationGuard.java:106-108`). It never lifts an IP literal: a literal is range-checked with
  the exception off (`:98-100`). It never lifts link-local / cloud metadata, "this network",
  carrier-grade NAT, IPv6 unique-local, multicast or broadcast.
- **Port pin (B21; T137 F1, CWE-918).** The range is lifted only when the host **AND** the port
  are listed (`DestinationGuard.java:113`), so a name that resolves to loopback does not reach
  every port on that host (database, identity, broker). The port is the URI's explicit port, or
  the scheme default **443** for `https` and **80** otherwise (`:94-95`). The ports are global to
  the exception, not per host (`:34-36`). Each port must be an exact 1-65535 value; a null or
  out-of-range entry is rejected when the guard is built (`:144-154`).
- **Hosts need ports.** `showcase-local-ports` is required together with `showcase-local-hosts`:
  hosts without ports -> `IllegalStateException`, the application refuses to start
  (`SystemPersistenceConfiguration.java:97-100`); the guard refuses the same pairing on its own
  (`DestinationGuard.java:72-74`).
- **Exact names only.** An empty entry, a wildcard, an IP literal (including an all-digit or
  `0x` hex last label, an IPv4 encoding; T137 F3) or a port is rejected when the guard is built,
  so the application does not start (`DestinationGuard.java:128-142`).
- **Fail closed.** The hosts property set without the `showcase` profile ->
  `IllegalStateException`, the application refuses to start
  (`SystemPersistenceConfiguration.java:88-91`). Set next to a production-like profile -
  **`prod`, `production`, `staging` or `uat`** - -> refused at startup even when `showcase` is
  also active (`:58`, `:92-96`). The hosts property absent or empty -> the guard is built exactly
  as before (`:85-86`), so removing the property restores today's refusal. When the exception is
  active, a startup **WARN** names both properties, the hosts and the ports (`:101-104`).
- **Gates (counts).** Without the property: 1 loopback and 1 private resolution of an
  allow-listed name are refused (2 of 2). With hosts and ports under `showcase`: those 2 are
  allowed on a listed port, and the same host on an unlisted port, an IP literal, a link-local
  resolution and a name not on the allow-list are still refused (4 of 4). Startup refused, 1
  each: hosts without the profile; hosts next to `prod`, `production`, `staging` or `uat`; hosts
  without ports; a wildcard or IP host entry; a null or out-of-range port. Conformance tests on
  main: `DestinationGuardShowcaseLocalHostsTest` (port cases `:115`, `:130`, `:138`, `:145`; IP
  literal `:174`), `SystemPersistenceShowcaseWiringTest` (hosts without ports `:117`,
  production-like profile `:134`), `ShowcaseLocalHostsHostContainerTest`. Lead gate on `b7a23f3`:
  unit exit 0, `-Pcontainer` exit 0 (merge `a2c76ff`).
- **What it gives up.** Under `showcase`, a listed name that resolves to loopback can still reach
  any service on that host that listens on a **listed** port. The port pin narrows this from every
  port to the listed ones, and the "platform's own hosts" refusal list of this section is in code
  since `8215ebc` (`DestinationGuard.java:76-106`, `:173-177`), so a listed port shared with a
  platform service is refused. A showcase-local port
  must therefore be the test destination's own port, a showcase-local host a name that points
  only at the test destination, and a showcase run never holds real tenant data.
- **Review.** karan-security, through kavita-release-manager (T137, ledger row 323), reviewed the
  change before it landed: T137 PASS (merge `a2c76ff`).

#### Amendment 2026-10-19 - global outbound allow-list (T-202, row 327)

- **Context.** The allow-list is per tenant only, so a tenant administrator decides alone where
  the platform may connect. An operator needs an outer bound per deployment.
- **Options.** (A) Keep the per-tenant list only: no new setting, but no operator bound.
  (B) Global list only: one bound, but it removes the tenant's own narrowing. (C) Both, the
  effective list being the intersection. [Decided] **C.**
- **Decision.** A global allow-list from an environment variable is the outer bound; the
  effective list for a target is global ∩ tenant. An empty or missing global list **fails closed**
  (every destination refused), never open. The platform's own hosts (the instance's PostgreSQL,
  Kafka and Keycloak) are refused as targets **even when allow-listed**: the egress manifest
  allows them for the platform's own connections only. A FILE target path is confined with
  `ConfinedPath.under` (`ConfinedPath.java:25-45`); `..` and symlink escapes are refused.
- **Gates (counts), each a conformance test.** Empty global list: N of N destinations refused. A
  host on the tenant list but not on the global list: refused, 1 of 1. A platform host on both
  lists: refused, 1 of 1. FILE escapes: 2 of 2 refused (`..`, symlink). A host on both lists and
  not a platform host: allowed, 1 of 1.
- **What it gives up.** One more setting per deployment, and a deployment that forgets it sends
  nothing until it is set.
- **Revisit if** an operator needs per-tenant overrides of the global bound.

#### Note 2026-10-27 - the editable global allow-list is removed (security finding H-1; PRs 36, 37, 46)

- **What happened.** `V2_4_1410__operator_allowed_host.sql` added a table `public.operator_allowed_host` edited at
  runtime from a console settings page, whose entries were **unioned** with the startup list
  (`V2_4_1410__operator_allowed_host.sql:1-4`), writable by the application role (`:15`) and gated only on
  TENANT_ADMIN (`:4`) - so one tenant's admin could widen every tenant's outer bound, which this amendment fixes as
  an environment setting. No ADR decided it. karan-security raised it as
  H-1; the endpoint and screen were removed in PR 36 (`8f5cd0d7`), the dormant repository in PR 37 (`b1a9cdb6`);
  PR 40 re-added them by accident and PR 46 (`79c76ce5`, row 510) removed them again.
- **State on main `e12f2949` (superseded - the table is no longer dormant; see the note of 2026-10-28 below).** The global allow-list is the startup setting
  `platform.config.destinations.allowed-hosts` only
  (`platform-config/src/main/java/io/integrationplatform/config/internal/persistence/SystemPersistenceConfiguration.java:60`, `:97`),
  as decided above. No application code reads or writes `operator_allowed_host` (search of `platform-*/src/main`
  this pass). The table stays, dormant, because `V2_4_1410` is applied and checksummed; `V2_4_1440` revokes
  `INSERT` and `DELETE` on it from `ipaas_app`
  (`platform-config/src/main/resources/db/migration/V2_4_1440__operator_allowed_host_revoke_write.sql:7`), so a code
  regression fails with "permission denied" instead of widening every tenant's outbound policy. `SELECT` remains,
  harmless on a dormant table.
- **Decision unchanged:** no runtime-editable allow-list. Re-introducing one needs a new amendment of this
  section and a karan-security review.
- **Open.** (1) No test asserts the revoke yet: a grant-inspection row (`has_table_privilege('ipaas_app',
  'public.operator_allowed_host', 'INSERT')` and `'DELETE'` both false, 0 of 2) would make it a claimed control
  with a conformance test. (2) Dropping the dormant table is a later migration and is the project owner's call
  (dropping a table is never automatic). (3) The comment at
  `platform-app/src/test/java/io/integrationplatform/app/retention/PayloadPurgeContainerTest.java:52` still says the
  application reads `operator_allowed_host` at start; it is stale.

#### Note 2026-10-28 - operator_allowed_host is written again, by an operator-agent-only endpoint (PRs #88, #90)

- **State on main `ba1767c6`.** The table is not dormant. PR #88 (`2b561b92`) added
  `platform-config/src/main/resources/db/migration/V2_4_1510__regrant_operator_allowed_host_write.sql:6`
  (`GRANT INSERT, DELETE ON public.operator_allowed_host TO ipaas_app`, undoing `V2_4_1440`), the repository
  `platform-config/src/main/java/io/integrationplatform/config/destination/DbBackedAllowList.java`, which
  **unions** the startup list with the table (`:12-13`, reads `:68`, writes `:92`, `:102`), and the endpoint
  `platform-config/src/main/java/io/integrationplatform/config/destination/adapter/in/OperatorAllowedHostController.java`
  (`/api/v1/admin/operator/allowed-hosts`). PR #90 (`ba1767c6`, row 578, CWE-862/918) made every route an
  OPERATOR_AGENT operation (`OperatorAllowedHostController.java:30-32`,
  `OperatorAllowedHostEndpointRoles.java:10-13`): every tenant role gets 403, so the H-1 path (a tenant admin widening
  every tenant's outer bound) is closed, but the list is runtime-editable again.
- **Conflict with the decision above.** "No runtime-editable allow-list" and "re-introducing one needs a new amendment
  of this section and a karan-security review" no longer describe main. #90 is the security fix; no amendment of
  section 8 accepted the runtime list. **Needs decision** (recommend: amend section 8 to accept one global,
  operator-agent-only runtime list unioned with the startup setting, gated by #90's role tests, rather than revert #88).
- **Open items of the 2026-10-27 note.** (1) The revoke test is moot: the grant is restored by design. (2) The table
  is no longer a drop candidate. (3) `PayloadPurgeContainerTest.java:52` is accurate again (the app reads the table).

### 9. Nothing stays stuck (challenge B4)

Three mechanisms, so that a lost Kafka record, a crashed consumer or a crashed instance never
leaves accepted work stopped:

1. **Release in the same transaction.** Whenever a delivery reaches `DELIVERED` or
   `DEAD_LETTERED`, or a message reaches `ROUTED` or `ROUTE_FAILED`, that transaction takes the
   ordering-key lock, re-evaluates the hold for the oldest `HELD` delivery per target in the
   key's scope, and moves each one with no blocker left to `DUE` with its outbox row.
2. **The reconciler, inside the delivery-retry job** (it adds no job of its own). On each
   run, per tenant (ADR-011 Decision 8), it re-publishes by writing a fresh outbox row:
   - messages `ACCEPTED` for more than 5 minutes (stage 1 never completed);
   - deliveries `PENDING` or `DUE` with no state change for more than 5 minutes and no
     unpublished outbox row (the record was lost or never consumed);
   - deliveries `HELD` whose blockers are all gone (moved to `DUE` first);
   - and it applies the stale-claim rule of section 6.
   Re-publishing is safe because both stages detect duplicates (sections 2 and 7). Every action
   is a delivery or message event with its ids. Limits are configuration (team defaults above).
3. **Consumers pause on a transient database error.** A connection failure, pool timeout,
   serialization failure (40001), deadlock (40P01) or statement timeout while handling a record,
   once the in-place retry of section 7 is spent, is not a poison case: the
   consumer does not commit the offset, pauses its partitions, backs off (1 s doubling to a 30 s
   cap, with jitter) and resumes; the record is processed again. Only an unreadable record or a
   referenced row that does not exist goes to `platform.poison`.
4. **A not-ready instance takes no work (S-d).** While its readiness check fails (for example a
   missing credential key id, ADR-007 Decision 7), the instance stops its Kafka consumers - they
   leave the consumer group without committing offsets, so their partitions are reassigned to
   ready instances (a Kafka pause alone would keep them assigned) - and does not run the
   delivery-retry job (its scheduler picks up no job). It resumes when it is ready again. Test: an
   instance started without one key claims 0 deliveries and runs 0 retry-job passes, and the
   other instance delivers every message.

**Gate:** in an end-to-end test, (a) a `delivery.due` record is deleted from the topic before it
is consumed, and (b) a consumer instance is killed between the claim commit and the send; in both
cases every accepted message reaches a final state within reconciler limit + one job interval,
with 0 messages lost and 0 duplicate sends to a target without idempotent receive (test).

### 10. Conformance tests this ADR adds

Each claimed control carries a test written first: bulkhead isolation (section 2 gate);
duplicate `message.accepted` gives N deliveries (section 2); stuck-work recovery (section 9
gate); claim order and late outcome with a stale token (sections 6-7); `Retry-After` honoured on
429 and 503; 500 classed `AMBIGUOUS` without idempotent receive and `TRANSIENT` with it; an
error-dictionary override that tries to make 401 retryable is refused at save; half-open
re-drives at most the trial-call count (with 50 deliveries waiting on one target, exactly 3 are
re-driven when the breaker half-opens); every refused destination case of section 8,
including a Kafka broker address returned by metadata in a refused range; bulkhead-full backoff
(consecutive deferrals wait 1, 2, 4 ... s up to 30 s, and a run re-drives at most the per-target
cap); a missing key writes 0 attempt rows and uses 0 attempts; a not-ready instance claims 0
deliveries; a 40P01 in a state-changing transaction is retried and the state change lands once.

## Consequences

- One code path executes every delivery, first attempt or replay, so the report and the rows
  agree by construction.
- Camel, its components and its upgrade cadence become a core dependency; the next LTS upgrade
  is planned into Release 2 (`ws/_specs/project-plan.md:200`).
- Debugging crosses Camel exchanges and now a thread hand-off; the trace span per call, the
  claim token and the attempt rows are the primary tools.
- Some Camel components pull Jackson 2 while Spring Boot 4 uses Jackson 3; the transform module
  avoids `camel-jsonata` for this reason, and the dependency check of phase 0 records what
  remains (`ws/_specs/project-plan.md:199`).
- The Kafka offset proves the claim, not the send: a crash mid-send is recovered by the
  stale-claim rule after up to 3 x timeout + 65 s, not at once.
- The reconciler adds a periodic scan per tenant; its queries use partial indexes on the
  non-final states so the cost follows stuck work, not table size.

**What we give up:** a smaller framework footprint and a debugging story the team already
knows; Camel's own dead-letter-to-broker and Kafka's retry-topic patterns (deliberately unused);
redelivery of ambiguous outcomes to targets that do not declare idempotent receive (they go to
an operator instead); unbounded per-target concurrency (a target never gets more than pool size
x instances parallel sends, and a burst above that waits seconds in the database).

## Revisit if

- More than 1 delivery in 1,000 ends as `AMBIGUOUS_TIMEOUT` over a week - targets need
  idempotent receive, or timeouts are mis-set.
- A target type needs a component outside the starter list above.
- Camel 4.22's LTS support ends before the next LTS upgrade is done.
- A target records more than 1,000 `BULKHEAD_FULL` events a day for a week - raise its pool
  size or its instances.
- The reconciler re-drives more than 10 items a day for a week - a systematic defect upstream,
  not an edge case.
