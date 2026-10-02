---
type: ADR
title: "ADR-011 - Target types, contract registry and version graph, bulk at intake, ordering, Kafka records carry ids only, background tenant from the row"
status: "Accepted (team, 2026-10-02) - Dipesh may reverse"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0)"
project: ipass/integration-platform
provenance: new-decision
related:
  - ADR-005-messaging.md
  - ADR-007-identity.md
  - ADR-009-tenant-directory-rls.md
  - ADR-010-camel-routing-core.md
  - ../architecture.md
---

# ADR-011 - Target types, contract registry and version graph, bulk at intake, ordering, Kafka records carry ids only, background tenant from the row

Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`. Memory lines drift; the id is the stable key.

## Context

*Sources: `ws/_memory/shared.md:25` (m-703833); `ws/_specs/project-plan.md:38-40`, `:46-48`,
`:102`, `:105`, `:110`, `:113`, `:116`, `:137-147`, `:150`, `:202`; `ws/_specs/f3-plan-brief.md:40-43`.*

Dipesh's requirement: a source calls **one common API**, possibly with a **bulk** request; the
platform identifies customer, interface and the applicable targets, applies **each target's own
versioned contract and mapping**, and sends to targets of type **API, DB, MQ (a CSV file on the
target's queue manager) and File**. His example - one call from Customer A delivering
`{customer_code, order_number, total_amount}` to Target A (contract v1.0) and `{clientId,
referenceNo, invoiceValue}` to Target B (contract v2.0) - is the acceptance test of the idea.
Decisions already applied (Mira, reversible): bulk accepts valid records and rejects invalid
ones with all reasons; same business key delivered in order; immediate async reply, sync only
for single-target flows; DB targets only to approved tables.

The plan leaves seven points to this ADR: target types; the contract registry with a version
graph and one home for the source schema, extending the flow-configuration systems table; bulk
split and validated at intake with a cap; ordering with a head-of-line rule; what a Kafka record
carries; and how background work finds its tenant.

## Decision 1 - Target types (closed register)

| Type | Release | Sends with | Idempotent receive, when the target declares it | Notes |
|---|---|---|---|---|
| `API` | 1 | `camel-http` | `Idempotency-Key: <delivery id>` header | Allow-listed destinations only; private and loopback ranges refused after DNS resolution and every redirect |
| `MQ_CSV` | 1 | `camel-csv` / `camel-bindy` to format, `camel-jms` to send | JMS correlation id = delivery id, deduplicated by the target | Queue-manager product unknown; plugged in through its JMS connection factory, chosen from an allow-list of factory classes |
| `DB` | 2 | `camel-sql` | Insert-or-update on a natural key the target declares | Only tables on the target's approved list; a restricted per-target database account with no DDL rights; parameterised statements only |
| `FILE` | 2 | `camel-file` | Deterministic file name from the delivery id; write to a temporary name, then atomic rename | A shared folder; SFTP is out of scope unless Dipesh names it; no half-written file is ever visible |
| `KAFKA_TOPIC` | 2 | `camel-kafka` | Idempotent producer, key = delivery id | Kept in scope by Mira's decision (`ws/_specs/project-plan.md:151`); not in Dipesh's formal list |

- Each type has a **settings schema** (JSON Schema) shipped in code. A target's settings are a
  JSONB column validated against its type's schema on every save. **Adding a target of an
  existing type is rows only; adding a new type is a code change** (a new route template, a new
  settings schema, an ADR update).
- A target that does not declare idempotent receive gets no automatic retry of an ambiguous
  outcome (ADR-010 section 5).

## Decision 2 - The registry extends the flow-configuration tables; one home for every schema

*Grounded in the deleted data design quoted at `ws/_specs/f3-plan-brief.md:38-43`.*

- **One systems table.** The flow-configuration phase creates `system` (code, name, direction)
  and `target_endpoint`; the registry phase **adds** `type` and `settings` to them. No second
  systems table.
- **One home for every schema: `contract_version`.** The source request schema, the source
  response schema (synchronous flows) and every target schema live only there. The flow
  configuration's interface version **references** its source contract version by id; it never
  holds a schema copy.
  - Because the flow-configuration phase (3) comes before the registry phase (5), phase 3
    creates `contract` and `contract_version` with the source role only. The draft-save API keeps
    its `sourceSchema` request field and stores it as a draft source contract version, so the
    earlier UI-API defect test ("Save draft sends the required sourceSchema and gets 200") stays
    valid unchanged. Phase 5 adds target roles, OpenAPI import, approval and the version graph.
- **Tables (shape, not DDL):**
  - `contract` - tenant, system, role (`SOURCE_REQUEST`, `SOURCE_RESPONSE`, `TARGET_REQUEST`,
    `TARGET_RESPONSE`), name.
  - `contract_version` - contract, version label (`major.minor`, e.g. `1.0`, `2.0`), status
    (`DRAFT`, `PENDING_APPROVAL`, `APPROVED`, `RETIRED`), JSON Schema (draft 2020-12, JSONB),
    optional original OpenAPI document, `derived_from_version_id`, `compatibility`, author,
    approver, timestamps, and the database check *approver is null or approver is not the
    author*.
- **Formats:** JSON Schema is canonical; OpenAPI documents are imported with `swagger-parser`
  2.1.48 and their schemas extracted (`ws/_specs/project-plan.md:80`); validation uses the
  networknt validator at the version Camel 4.22.1 manages (`:81`).

## Decision 3 - The version graph

- **Nodes:** contract versions, mapping versions, interface versions.
- **Edges:**
  - lineage: a contract version is `derived_from` its predecessor; at submit the platform
    computes `compatibility` = `COMPATIBLE` (no required field removed or renamed, no type
    narrowed, no enum value removed) or `BREAKING`;
  - binding: each mapping version binds exactly **one source contract version and one target
    contract version** (one mapping per interface-target pair - ADR-004);
  - pinning: an interface version pins its source contract version and, per route, its target
    endpoint, target contract version and mapping version.
- **Rules:**
  1. An `APPROVED` contract version is immutable. A new version needs approval by someone who
     is not its author.
  2. A new target contract version takes effect only through a new mapping version bound to it,
     exercised against a sample, inside a published interface version - so no message is ever
     transformed against a contract its mapping was not tested with.
  3. Every message records the interface version and source contract version it was accepted
     under; every delivery records the target contract version and mapping version it was
     created with. Replay uses those pins.
  4. A contract version is `RETIRED` only when 0 non-final deliveries pin it (a count query is
     the gate).
  5. Proven by a test that adds a target contract version on a running system with 0 deploys.

## Decision 4 - The one common API, and bulk split and validated at intake

- **Endpoints:** `POST /api/v1/interfaces/{interfaceCode}/messages` (one record, a JSON
  object) and `POST /api/v1/interfaces/{interfaceCode}/messages:bulk` (`{"records": [...]}`). The
  **path names the interface; the tenant and customer come from the credential** (ADR-007), so
  the path carries no tenant segment (the deleted design had one - workflows.md:21, quoted at
  `ws/_reports/2026-10-01-t10-figure-reconciliation.md:52`).
- **Intake steps, in order:** authenticate -> resolve the interface's published version (pinned
  for this call) -> size checks -> per record: JSON Schema validation (all errors), then rules
  validation (all violations; ADR-003), then business-key extraction (the interface's mandatory
  business-key rule) and idempotency check -> **one transaction** writing, for every valid
  record, its message row, payload row and outbox row -> respond.
- **Bulk is split here, before Kafka:** one record = one message = one Kafka record. Camel never
  splits bulk (it stays inside routing - ADR-010).
- **Caps (team defaults, configurable per interface downward; Dipesh may change them):**
  - a record over **1 MB** is rejected with a clear error (OQ-08);
  - a bulk call over **1,000 records** or over **10 MB** is refused as a whole with `413` and a
    clear error; nothing is persisted.
- **Responses:**

  | Case | Status | Body |
  |---|---|---|
  | At least one record accepted | `202` | One result per record: index, `ACCEPTED` with tracking id, `REJECTED` with every reason (path, code, message), or `DUPLICATE` with the original tracking id |
  | No record accepted | `422` | The same per-record results |
  | Over a cap | `413` | Which cap, and the limit |
  | Intake budget (2 s) exceeded or the database unavailable | `503` | Nothing persisted; safe to repeat with the same idempotency key |
  | Credential, role or interface access refused | `401` / `403` | Never retried by the platform |

- **Idempotency:** an `Idempotency-Key` header is required on bulk calls and optional on single
  calls; each record's key is the header plus its index. A repeated call returns the original
  per-record results. Same business key with a new idempotency key is a new message (an update).
- **Gate:** a bulk of N records with k invalid returns N results and writes exactly N - k outbox
  rows (test).

## Decision 5 - Synchronous single-target flows

*Applies Mira's decision recorded at `ws/_specs/project-plan.md:150`.*

- An interface may be `SYNC` only when exactly one route can match (the single-primary-route
  rule of the deleted data design, quoted at `ws/_specs/f3-plan-brief.md:42`).
- After the intake transaction commits, the request thread runs routing and delivery for that
  one message **inline, making exactly one target call - no redelivery on the caller thread**.
- The delivery's outbox row is written with `available_at` = now + the synchronous budget +
  5 s, so the relay publishes it only if the inline path did not finish (for example, the
  instance crashed). The inline path claims the delivery like any consumer (ADR-010 section 7),
  so the two can never both send.
- **Answer to the source:** success -> `200` with the target's response mapped to the source's
  registered response contract; `PERMANENT` failure -> an error mapped through the target's
  error dictionary; anything else, or the 10 s budget reached -> `202` with the tracking id and
  status `PENDING`, and the delivery continues on the asynchronous path. **Dipesh may reverse**
  the `PENDING` rule (for example to a plain error on timeout) - one rule in the ingest phase.

## Decision 6 - Ordering and the head-of-line rule

- **Ordering scope:** (tenant, interface, business key, **target endpoint**). Order is the
  acceptance order: each message gets a database sequence number at insert.
- **Interface setting `ordering`:** `PER_KEY` (default, Dipesh's rule) or `NONE`.
- **The hold rule (chosen):** a delivery is sent only when no earlier delivery in its ordering
  scope is non-final. While an earlier one waits for a retry, later ones become `HELD`. When the
  earlier one becomes `DELIVERED` **or `DEAD_LETTERED`**, the next held delivery is released
  (`DUE`). The check and the claim run in one transaction under a transaction-level advisory lock
  on the ordering scope, so it holds during consumer rebalances too.
- **Options considered:**

  | Option | For | Against | Verdict |
  |---|---|---|---|
  | **Hold later same-key deliveries per target; release on dead-letter** | Order kept for the target while retrying; one stuck target never blocks other targets | A stuck delivery delays later ones for up to the retry horizon | **Chosen** |
  | Never hold (ordering only at first send) | No head-of-line delay | A retried update can overwrite a newer one at the target - the exact failure ordering prevents | Rejected |
  | Hold and never release until an operator acts | Strict order | One poison message stops a business key forever | Rejected |

- **Visibility:** the queue dashboard counts `HELD` deliveries and the oldest hold's age per
  target; an alert fires when a hold is older than 1 hour (configurable). Replaying a
  dead-lettered delivery after later same-key deliveries were delivered is allowed, and the
  replay screen states how many later deliveries already went out.
- **Gate:** 100 same-key updates are received by the target in sequence (backbone phase test).

## Decision 7 - A Kafka record carries ids only

| Option | For | Against | Verdict |
|---|---|---|---|
| **Ids only** - the payload stays in PostgreSQL | One copy of business data, under row-level security, purged by partition drop; tiny records; no business data in Kafka to encrypt or restrict | One database read per stage (trivial at 50 messages per second) | **Chosen** (the plan's recommendation) |
| Full payload in the record | No read per stage | Two copies with two retention rules; tenant data in Kafka needs its own access control and encryption; records up to 1 MB | Rejected |
| Payload reference plus metadata | Middle ground | Metadata drifts into a second, unprotected copy | Rejected |

- **Record value:** `{"v": 1, "type": "message.accepted" | "delivery.due", "id": "<uuid>"}`.
  **Headers:** `traceparent`. **Key:** SHA-256 of (tenant, interface, business key), so no
  business data appears in the key either. No tenant id in the value or headers.

## Decision 8 - Background work takes its tenant from the protected row

- **Kafka consumers** call one trusted definer function, `ipaas_work_tenant(kind, id)`, which
  returns only the tenant id of the message or delivery row (the same narrow pattern as ADR-009's
  login bootstrap). The consumer binds that tenant, stamps it per transaction (ADR-007
  Decision 6), and loads the row under row-level security. Nothing in the record can choose the
  tenant. Gate: a record with a forged tenant header changes nothing (test).
- **Scanning jobs** (outbox relay, retry trigger, report aggregation) loop over tenants returned
  by a definer function `ipaas_active_tenants()` (ids only), binding one tenant per transaction.
  No application role bypasses row-level security.
- **Partition maintenance** (creating and dropping daily partitions for purge) is DDL; it runs
  as a separate maintenance database role, never as the application role, and touches no rows.

## Consequences

- The intake transaction grows with bulk size; the 1,000-record cap keeps it inside the 2 s
  budget, which the ingest phase's load test must prove (else the cap goes down).
- Ordering per target means a target in a long retry delays later same-key messages for that
  target only; the hold is visible and alerted.
- Two definer functions join ADR-009's one; each is narrow, returns ids only, and has its own
  negative test.
- Adding a target type stays a code change; that is the price of typed, validated endpoint
  settings instead of free-text Camel URIs.

**What we give up:** tenant data in Kafka for other consumers to read directly (they must call
the platform); unlimited bulk size; strict ordering after a dead-letter (the hold releases).

## Revisit if

- A bulk of 1,000 records cannot be validated and committed within the 2 s intake budget at
  the ingest phase's load test.
- A target needs ordering across targets (for example, Target B must not receive before
  Target A) - that is orchestration, out of scope today (`ws/_specs/project-plan.md:226`).
- A consumer outside the platform needs payloads from Kafka.
- More than 50 held deliveries older than 1 hour on any day for two weeks.
