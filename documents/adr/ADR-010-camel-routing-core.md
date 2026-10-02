---
type: ADR
title: "ADR-010 - Apache Camel as the routing core, inside one module, with exactly two retry layers"
status: "Accepted (team, 2026-10-02) - Dipesh may reverse"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0)"
project: ipass/integration-platform
provenance: new-decision
supersedes: ADR-008-camel-adapter-only.md
related:
  - ADR-008-camel-adapter-only.md
  - ADR-005-messaging.md
  - ADR-011-targets-contracts-bulk-ordering.md
  - ADR-007-identity.md
---

# ADR-010 - Apache Camel as the routing core, inside one module, with exactly two retry layers

Supersedes [ADR-008](ADR-008-camel-adapter-only.md). Source paths starting `ws/` are in the hub
repository, project workspace `_personal/_project_ipass/integration-platform/`. Memory lines
drift; the id is the stable key.

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

## Options

| Option | Complexity | Risk | Reversibility | Verdict |
|---|---|---|---|---|
| **A. Camel as the routing core, contained in `platform-routing`; retry split into exactly two layers** | Medium | Team new to Camel in production | Medium: routes sit behind our ports | **Chosen** |
| B. Keep ADR-008: Camel only behind an HTTP outbound adapter port, our own dispatcher | Lower | Four target types and fan-out hand-written | High | Rejected: contradicts the binding direction and the v1 scope |
| C. Camel everywhere, retry via Kafka retry topics and Camel's dead-letter channel to a Kafka queue | Medium | Three retry layers; retry history lives in topics, not rows; replay cannot pin versions | Low | Rejected: breaks report-equals-rows and two-weeks-later replay |
| D. Spring Integration instead of Camel | Medium | Fewer ready target components | Medium | Rejected: contradicts the binding direction |

## Decision

### 1. Versions and containment

- **Apache Camel 4.22.1 (LTS)** with `camel-spring-boot` 4.22.1 on Spring Boot 4.1.1 and
  Java 25 (`ws/_specs/project-plan.md:73`). Starters used: kafka, jms, csv, bindy, sql, file,
  http, resilience4j, micrometer, opentelemetry2 (`:78`). No others without an ADR update.
- **`org.apache.camel` is used only in `platform-routing`.** An ArchUnit rule fails the build
  otherwise, proven by a planted violation. Other modules talk to routing through ports
  (interfaces) owned by the calling module.

### 2. Two stages, both consumers of Kafka work topics (ADR-005)

| Stage | Consumes | Does | Never does | Budget |
|---|---|---|---|---|
| **1 - route and fan out** | `platform.message.accepted` | Resolves the tenant from the message row (ADR-011), loads the routes of the interface version pinned on the message, evaluates routing conditions, and in **one transaction** creates one Delivery per matched target - each pinning its target contract version and mapping version - plus one outbox row per delivery | Calls a target | 1 s (`ws/_specs/project-plan.md:114`) |
| **2 - deliver** | `platform.delivery.due` | Claims the delivery, applies the ordering hold (ADR-011), transforms through the transform port, sends through the target's route, records the attempt, moves the state | Decides retry timing outside the database | Transform 5 s; target call within its configured timeout |

Deliveries are independent from creation: one target failing never blocks or rolls back
another (best-effort, OQ-09), except the same-key hold of ADR-011, which is per target.

### 3. Routes are built at runtime from configuration, lazily, on every instance

- One **route template per target type** (API, MQ-CSV, DB, File, Kafka topic). A route instance
  is created from the template for a (target endpoint id, endpoint version) pair **the first
  time a delivery needs it**, on whichever instance consumes it, and removed after 30 minutes
  idle. Publishing a new interface or target therefore needs 0 deploys and 0 restarts, and no
  broadcast between instances.
- **Endpoint URIs are never free text.** Template parameters come from typed target settings
  validated against the per-type schema (ADR-011). Stage 2 dispatches with `toD` whose
  `allowedSchemes` is `direct` only, so configuration cannot reach an unapproved component.

### 4. Exactly two retry layers

**Layer 1 - Camel in-attempt redelivery (seconds).** Camel's error handler redelivers only
**transient** failures (table in section 5): at most 2 redeliveries, 500 ms initial delay,
doubling, with jitter, and never beyond 5 s in total or beyond the remaining synchronous budget.
Each try - the first and each redelivery - is a call that left the process and **writes one
`delivery_attempt` row** (`layer = 1`). When layer 1 ends without success, Camel hands the
exchange to our outcome classifier through a `direct:` endpoint; Camel's dead-letter channel is
never pointed at a queue or topic.

**Layer 2 - the Delivery state machine (minutes to days), persisted.** The classifier moves the
delivery:

- retryable and attempts remain -> `RETRY_WAIT` with `next_attempt_at` = now + exponential
  backoff with full jitter, capped (per-route `retry_policy`; team defaults 8 attempts,
  30 s base, 1 h ceiling - configuration, not figures);
- retryable and attempts exhausted, or permanent -> `DEAD_LETTERED` with the reason class.

A polling trigger (phase 2; moved onto db-scheduler in the scheduler phase) selects due
`RETRY_WAIT` rows with `FOR UPDATE SKIP LOCKED`, moves them to `DUE` and writes an outbox row,
which the relay publishes to `platform.delivery.due`. Retry timing lives only in the database,
so it survives restarts.

**Kafka retries nothing.** A record that cannot be read, or whose row does not exist, goes once
to `platform.poison` and is never retried from there (ADR-005).

**Scope of "exactly two":** the two layers are the only places a **delivery to a target** is
retried. The outbox relay re-publishing a row Kafka did not acknowledge, and the bounded retry
of an idempotent read from Keycloak (ADR-007), are infrastructure calls with their own five
controls; they never call a target and never write an attempt row.

**The report equals the rows:** every call that left the process is exactly one attempt row;
"retries made" for a delivery = its attempt rows minus 1; a circuit-open deferral is not a call
and is recorded as a delivery event, not an attempt.

### 5. Outcome classes (closed register)

| Class | Examples | Layer 1 | Layer 2 |
|---|---|---|---|
| `SUCCESS` | HTTP 2xx; JMS send acknowledged; DB commit; file renamed into place | - | `DELIVERED` |
| `TRANSIENT` | connection refused or reset before the request was sent; HTTP 429, 502, 503, 504; broker unavailable; DB serialization failure or deadlock (40001, 40P01); target folder temporarily unavailable | redeliver | `RETRY_WAIT` |
| `AMBIGUOUS` | a timeout after the request was sent; a connection lost while awaiting the reply; a DB commit outcome unknown | **never redeliver** | `RETRY_WAIT` only if the target declares idempotent receive (ADR-011); otherwise `DEAD_LETTERED` with reason `AMBIGUOUS_TIMEOUT` for an operator decision |
| `PERMANENT` | HTTP 400, 404, 409, 422; **401, 403**; mapping or contract failure; destination refused by the allow-list; target credential unreadable (ADR-007) | never | `DEAD_LETTERED` |

A timeout is treated as ambiguous, never as failed (`ws/_specs/project-plan.md:115`).

### 6. Delivery states (closed register; phase 2 implements it, illegal transitions are rejected)

| From | To | Trigger |
|---|---|---|
| (new) | `PENDING` | stage 1 creates the delivery |
| `PENDING`, `DUE` | `HELD` | an earlier same-key delivery for this target is not final (ADR-011) |
| `HELD` | `DUE` | the earlier delivery became `DELIVERED` or `DEAD_LETTERED` |
| `PENDING`, `DUE` | `SENDING` | stage 2 claims it (conditional update - see 7) |
| `SENDING` | `DELIVERED` / `RETRY_WAIT` / `DEAD_LETTERED` | the classifier |
| `SENDING` | `RETRY_WAIT` or `DEAD_LETTERED` (as `AMBIGUOUS`) | a stale claim: claimed longer than the target timeout plus 60 s (instance crash mid-call), found by the polling trigger |
| `RETRY_WAIT` | `DUE` | `next_attempt_at` reached (polling trigger) |
| `RETRY_WAIT` | `DUE` | circuit half-open time reached after a circuit-open deferral |
| `DEAD_LETTERED` | `DUE` | operator replay - audited; uses the versions pinned when the message arrived |

Terminal: `DELIVERED`. `DEAD_LETTERED` is terminal until an operator replays it.

### 7. Duplicates are detected, not sent

Stage 2 claims a delivery with a conditional update (`... SET status = 'SENDING' WHERE id = ? AND
status IN ('PENDING','DUE')`). Zero rows updated means a duplicate Kafka record or a held
delivery: the record is acknowledged with no effect. A crash between commit and publish
re-publishes the outbox row; the claim makes the duplicate harmless.

### 8. The five controls on every target call

| Control | How |
|---|---|
| Timeout | Per target endpoint, configurable (figure: per-target timeout configurable); connect and read timeouts on HTTP, send timeout on JMS, query timeout on SQL |
| Retry of transient failures only | Layer 1 as above, transient class only, backoff with jitter and a cap; never 401/403, validation, ambiguous or non-idempotent |
| Circuit breaker | One Resilience4j breaker per target endpoint; open -> no call, the delivery waits in `RETRY_WAIT` until half-open time |
| Fallback | The Delivery state machine: `RETRY_WAIT` or `DEAD_LETTERED`; nothing is ever dropped silently |
| Observability | Micrometer metrics per target (attempts by outcome, latency histogram, breaker state); an OpenTelemetry span per call (`camel-opentelemetry2`); structured log line with tenant, message and delivery ids |

Outbound destinations: API targets reach only allow-listed destinations and refuse private and
loopback ranges after DNS resolution and after every redirect (redirects are off unless the
target enables them, and every hop is re-checked) - `ws/_specs/project-plan.md:115`.

## Consequences

- One code path executes every delivery, first attempt or replay, so the report and the rows
  agree by construction.
- Camel, its components and its upgrade cadence become a core dependency; the next LTS upgrade
  is planned into Release 2 (`ws/_specs/project-plan.md:200`).
- Debugging crosses Camel exchanges; the trace span per call and the attempt rows are the
  primary tools.
- Some Camel components pull Jackson 2 while Spring Boot 4 uses Jackson 3; the transform module
  avoids `camel-jsonata` for this reason, and the dependency check of phase 0 records what
  remains (`ws/_specs/project-plan.md:199`).

**What we give up:** a smaller framework footprint and a debugging story the team already
knows; Camel's own dead-letter-to-broker and Kafka's retry-topic patterns (deliberately unused);
redelivery of ambiguous outcomes to targets that do not declare idempotent receive (they go to
an operator instead).

## Revisit if

- More than 1 delivery in 1,000 ends as `AMBIGUOUS_TIMEOUT` over a week - targets need
  idempotent receive, or timeouts are mis-set.
- A target type needs a component outside the starter list above.
- Camel 4.22's LTS support ends before the next LTS upgrade is done.
