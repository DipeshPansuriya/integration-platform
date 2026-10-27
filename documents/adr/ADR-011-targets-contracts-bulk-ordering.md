---
type: ADR
title: "ADR-011 - Target types, contract registry and version graph, bulk at intake, ordering, Kafka records carry ids only, background tenant from the row"
status: "Accepted (team, 2026-10-02) - Dipesh may reverse; Amendment 2026-10-27 Proposed - awaiting the project owner's written acceptance"
date: 2026-10-02
revised: "2026-10-02 - vikram-cto's challenge applied (memory m-817272): hold rule covers unrouted earlier messages, release in the same transaction, synchronous outbox deferral, idempotency-key scope, retire and purge guards, publish-time pin check, destination check per type. 2026-10-02 - vikram-cto's re-check applied (memory m-6a410c): per-key commit-ordered sequence (B5), every state change takes the key lock first, broker-address check and egress policy. 2026-10-22 - E16 F3 amendment (approved tables, D1-D4) and F3 hardening addendum (S1, S2, S3, S5, residuals). 2026-10-27 - Amendment: author/approver separation of duties removed for flow versions, contract versions and DB-table approvals (V2_4_1420); Proposed - awaiting the project owner's written acceptance"
author: "mahesh-architect (ledger row 278, phase 0)"
project: ipass/integration-platform
provenance: new-decision
related:
  - ADR-005-messaging.md
  - ADR-007-identity.md
  - ADR-009-tenant-directory-rls.md
  - ADR-010-camel-routing-core.md
  - ADR-001-modular-monolith.md
  - ../architecture.md
---

# ADR-011 - Target types, contract registry and version graph, bulk at intake, ordering, Kafka records carry ids only, background tenant from the row

Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`. Memory lines drift; the id is the stable key.

> **Revision 2026-10-02 (challenge m-817272).** Decision 6's hold rule now also holds behind an
> earlier message with the same key that is not yet routed, releases in the same transaction, and
> follows one lock order (blockers B3, B4; S-g). Decision 5 defers the message's own outbox row too
> (B2). Decision 4's idempotency key is scoped per credential and required on every call (S-e).
> Decision 3 keeps retired versions readable and checks mapping pins at publish (S-f, S-k).
> Decision 1 applies the outbound destination check to every type (S-c). Decision 8 guards the
> payload purge (S-f).

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
| `API` | 1 | `camel-http` | `Idempotency-Key: <delivery id>` header | The one outbound destination check of ADR-010 section 8: every resolved address checked and the checked address pinned; refused ranges reachable only through an operator allow-list entry; redirects off unless the target enables them, every hop re-checked |
| `MQ_CSV` | 1 | `camel-csv` / `camel-bindy` to format, `camel-jms` to send | JMS correlation id = delivery id, deduplicated by the target | Queue-manager product unknown; plugged in through its JMS connection factory, chosen from an allow-list of factory classes. **Bindy stays (2026-10-20):** camel-bindy is live in the Camel 4.22.x data-format docs with no deprecation notice and the project pins camel-spring-boot 4.22.1; the 404 Vikram saw is camel-beanio, removed in 3.17 - lanes do not re-litigate this |
| `DB` | 2 | `camel-sql` | Insert-or-update on a natural key the target declares | Only tables on the target's approved list; a restricted per-target database account with no DDL rights; parameterised statements only |
| `FILE` | 2 | `camel-file` | Deterministic file name from the delivery id; write to a temporary name, then atomic rename | A shared folder; SFTP is out of scope unless Dipesh names it; no half-written file is ever visible |
| `KAFKA_TOPIC` | 2 | `camel-kafka` | Idempotent producer, key = delivery id | Kept in scope by Mira's decision (`ws/_specs/project-plan.md:151`); not in Dipesh's formal list |

- Each type has a **settings schema** (JSON Schema) shipped in code. A target's settings are a
  JSONB column validated against its type's schema on every save. **Adding a target of an
  existing type is rows only; adding a new type is a code change** (a new route template, a new
  settings schema, an ADR update).
- A target that does not declare idempotent receive gets no automatic retry of an ambiguous
  outcome (ADR-010 section 5).
- **Every type's host field passes the one outbound destination check** of ADR-010 section 8
  (refused ranges, pinned resolved address, operator-maintained per-tenant allow-list): the API
  URL host, the MQ connection host, the DB host, and for Kafka **every broker address the client
  connects to** - the bootstrap servers and each broker the cluster metadata returns (S-c). Where
  a driver resolves and connects on its own so the checked address cannot be pinned, an egress
  network policy enforces the same refused ranges (ADR-010 section 8). A File target's path is
  normalised and confined under its configured root. The check runs at save, at
  test-connection and at send.
- Each type's settings declare `max_concurrent_sends` (the bulkhead pool size, ADR-010 section 2),
  its error dictionary (overrides of the default outcome table, ADR-010 section 5), and
  `half_open_trial_calls` (the per-target half-open trial-call count, ADR-010 section 6;
  Resilience4j `permittedNumberOfCallsInHalfOpenState`, default 3) — an addendum (2026-10-20,
  E10 settle-up for T-183): the field lives in `target_endpoint.settings` (the JSONB column
  E5 extended the endpoint row with, V2_2_1's note), validated against the type's schema on
  save, so it needs **no new migration**.

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
    author*. *(Superseded by Amendment 2026-10-27: the check `approver_not_author` is dropped by
    `V2_4_1420`; the author may approve their own version.)*
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
     is not its author. *(Superseded by Amendment 2026-10-27: a new version still needs an
     approval by a holder of `APPROVE_VERSION`, who may be its author.)*
  2. A new target contract version takes effect only through a new mapping version bound to it,
     exercised against a sample, inside a published interface version - so no message is ever
     transformed against a contract its mapping was not tested with.
  3. Every message records the interface version and source contract version it was accepted
     under; every delivery records the target contract version and mapping version it was
     created with. Replay uses those pins.
  4. A contract version is `RETIRED` only when 0 non-final deliveries pin it (a count query is
     the gate). **Retired means "no new pins", never "unreadable":** a retired contract, mapping
     or interface version stays readable, so an operator replay of a dead letter that pins it
     still transforms and validates against it (test: replay a dead letter whose versions were
     retired after it failed).
  5. Proven by a test that adds a target contract version on a running system with 0 deploys.
  6. **Publish-time pin check (S-k):** publishing an interface version is refused unless, for
     every route, the mapping version's bound source contract version equals the interface
     version's pinned source contract version, and its bound target contract version equals the
     route's pinned target contract version. Test: a mapping bound to source contract 1.0 cannot
     be published inside an interface version pinned to source contract 1.1.

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

- **Idempotency (S-e):** an `Idempotency-Key` header is **required on every call**, single and
  bulk (a call without one is refused with `400`); each record's key is the header plus its
  index. The key is **scoped per source credential** - the same key from two credentials is two
  requests. The platform stores the key with a hash of the request body; a repeat with the same
  body returns the original per-record results; **the same key with a different body is refused
  with `422`** (`IDEMPOTENCY_KEY_REUSED`). Keys are kept **24 hours**, then purged. Same business
  key with a new idempotency key is a new message (an update). Tests: repeat, different body,
  other credential, and a repeat after 24 hours accepted as new.
- **Gate:** a bulk of N records with k invalid returns N results and writes exactly N - k outbox
  rows (test).

## Decision 5 - Synchronous single-target flows

*Applies Mira's decision recorded at `ws/_specs/project-plan.md:150`.*

- An interface may be `SYNC` only when exactly one route can match (the single-primary-route
  rule of the deleted data design, quoted at `ws/_specs/f3-plan-brief.md:42`).
- After the intake transaction commits, the request thread runs routing and delivery for that
  one message **inline, making exactly one target call - no redelivery on the caller thread**.
- **The message's own outbox row** (`message.accepted`) is written by the intake transaction
  with `available_at` = now + the synchronous budget + 5 s. The inline path runs stage 1 for the
  message in one transaction - `ACCEPTED -> ROUTED`, the delivery, the delivery's outbox row
  (also deferred by the same amount) - so if the instance dies before routing, the deferred
  message row re-drives stage 1, and a late duplicate finds the message already `ROUTED` and does
  nothing (ADR-010 section 2).
- The inline path then takes a permit from the target's bulkhead and claims the delivery like
  any consumer (ADR-010 section 7), so the inline path and the deferred record can never both
  send. A full bulkhead or a held delivery makes no call: the source gets `202` with status
  `PENDING`.
- **Answer to the source:** success -> `200` with the target's response mapped to the source's
  registered response contract; `PERMANENT` failure -> an error mapped through the target's
  error dictionary; anything else, or the 10 s budget reached -> `202` with the tracking id and
  status `PENDING`, and the delivery continues on the asynchronous path. **Dipesh may reverse**
  the `PENDING` rule (for example to a plain error on timeout) - one rule in the ingest phase.

## Decision 6 - Ordering and the head-of-line rule

- **Ordering scope:** (tenant, interface, business key, **target endpoint**). Order is the
  **commit order of intake, per key (B5)** - a sequence assigned at insert is not commit order,
  so two concurrent same-key calls could otherwise be sent out of order. The intake transaction
  takes the key's counter row in the `ordering_key` table (tenant, interface, business key) with
  `INSERT ... ON CONFLICT (tenant_id, interface_id, business_key) DO UPDATE SET last_seq =
  ordering_key.last_seq + 1 RETURNING last_seq`, and stores the value on the message as its
  **per-key sequence**. The row lock is held to commit: a second intake for the same key waits
  until the first commits (or rolls back, which also undoes its increment), so per key the
  sequence follows commit order with no duplicate. A bulk call takes its distinct keys' counter
  rows **in key-hash order** (SHA-256 of tenant, interface and business key - the Kafka key of
  Decision 7), so two bulks that share keys cannot deadlock. The wait counts against the 2 s
  intake budget (a `lock_timeout` inside it; past it -> `503`, nothing stored, safe to repeat
  with the same idempotency key). Clauses 1 and 2 below compare the **per-key sequence**, never
  a global sequence or a timestamp. When the interface's `ordering` is `NONE`, no counter row is
  taken, the message has no per-key sequence and the hold rule is skipped.
- **Interface setting `ordering`:** `PER_KEY` (default, Dipesh's rule) or `NONE`.
- **The hold rule (chosen, corrected for B3):** a delivery is held when **either**
  1. an earlier message (lower per-key sequence) with the same tenant, interface and business key
     is still `ACCEPTED` - not yet `ROUTED` or `ROUTE_FAILED` - so its deliveries do not exist yet;
     **or**
  2. an earlier delivery (its message has a lower per-key sequence) to the **same target
     endpoint** in the same scope is not final.

  While either holds, the delivery becomes `HELD`. Without clause 1 a later message routed first
  could be sent before an earlier message was routed at all; clause 1 closes that gap and also
  covers a partition-count change (ADR-005 Decision 6) and a deferred synchronous row being
  overtaken (ADR-005 Decision 11).
- **One lock, one order (S-g).** The lock is a transaction-level advisory lock on the **ordering
  key** (tenant, interface, business key) - coarser than the per-target scope so that stage 1's
  `ROUTED` update and stage 2's check serialise. Every claim runs in one transaction in this
  order: lock, read the delivery `FOR UPDATE`, check the hold, then `HELD` or `SENDING` (ADR-010
  section 7). It holds during consumer rebalances too. **Every transaction that changes a
  message's or a delivery's state takes this lock first, before any row lock** - stage 1
  (`ROUTED` / `ROUTE_FAILED`), the claim, the bulkhead-full deferral, the classifier's outcome,
  the release, the delivery-retry job's moves (`RETRY_WAIT -> DUE`, stale claim), the reconciler
  and an operator replay; inside the lock, rows are locked message first, then deliveries in id
  order. Intake takes only its `ordering_key` rows (above) and inserts new rows, so it cannot
  form a lock cycle with them. A serialization failure (40001) or deadlock (40P01) rolls the
  transaction back and it is retried (ADR-010 section 7).
- **Release in the same transaction (B4).** The transaction that makes a delivery `DELIVERED`
  **or `DEAD_LETTERED`**, or makes a message `ROUTED` or `ROUTE_FAILED`, takes the same lock,
  re-checks the oldest `HELD` delivery per target in the key's scope, and moves each one whose
  blockers are gone to `DUE` with its outbox row. The reconciler in the delivery-retry job is the
  safety net for a release that was missed (ADR-010 section 9).
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
- **Gate:** 100 same-key updates are received by the target in sequence with **3 relay
  instances** and 2 consumer instances; the same with a **synchronous** interface where some
  calls fall back to `PENDING`; the same with message 1's stage 1 delayed until after
  message 2 is routed (backbone and routing phase tests); and **two concurrent intake calls for
  one key on two instances, the first to start committing last** (single and bulk): their
  per-key sequences follow commit order with no duplicate, and the target receives them in that
  order; two bulks sharing 100 keys listed in opposite orders both commit with 0 deadlocks
  (B5; ingest and routing phase tests).

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
- **Purge guard (S-f):** a daily payload (and message) partition is dropped only when **0
  deliveries that are `DEAD_LETTERED` or not final** reference a message in it - a count query is
  the gate, run by the purge job before each drop; a partition that fails the gate is kept and
  reported, so a dead letter can always be replayed with its payload. Test: a partition holding
  one dead-lettered delivery's payload survives the purge.

## Consequences

- The intake transaction grows with bulk size; the 1,000-record cap keeps it inside the 2 s
  budget, which the ingest phase's load test must prove (else the cap goes down).
- Ordering per target means a target in a long retry delays later same-key messages for that
  target only; the hold is visible and alerted.
- Two definer functions join ADR-009's one; each is narrow, returns ids only, and has its own
  negative test.
- Adding a target type stays a code change; that is the price of typed, validated endpoint
  settings instead of free-text Camel URIs.
- The hold rule now reads the message table as well as the delivery table under one key lock;
  at 50 messages per second the lock is uncontended except for bursts on one key.
- Same-key intake is serialised by the `ordering_key` row (B5): a call waits for an earlier
  uncommitted call with the same key, and a bulk holds one row lock per distinct key until it
  commits. The table keeps one small row per key ever seen.
- Sources must send an idempotency key on single calls too - a contract change for any source
  built against the earlier "optional" wording.

**What we give up:** tenant data in Kafka for other consumers to read directly (they must call
the platform); unlimited bulk size; strict ordering after a dead-letter (the hold releases);
parallel delivery of two messages with the same key while the earlier one is still being
routed (it is held for the seconds stage 1 takes); parallel intake of two calls with the same
key (the second waits for the first to commit); purging a day's payloads on schedule while a
dead letter from that day is unresolved.

## Revisit if

- A bulk of 1,000 records cannot be validated and committed within the 2 s intake budget at
  the ingest phase's load test.
- A target needs ordering across targets (for example, Target B must not receive before
  Target A) - that is orchestration, out of scope today (`ws/_specs/project-plan.md:226`).
- A consumer outside the platform needs payloads from Kafka.
- More than 50 held deliveries older than 1 hour on any day for two weeks.

## Amendment 2026-10-22 - who approves a DB target's tables, and where it is enforced (E16 finding F3)

Dipesh decided D1-D4 on 2026-10-22; design note: option B of the E16 F3 note (`_documents/2026-10-22-design-job-state-change-and-approved-tables.md`
in the project workspace). This amends the `DB` row of Decision 1 ("only tables on the target's approved list").

- **Where the list lives.** Not in the system's `settings` (a configurator wrote both `table` and the list that was meant
  to restrict it). It is the tenant table `public.db_target_approved_table` (`V2_4_1330`): forced row-level security, the
  app role holds SELECT and INSERT only, and a decision moves only through the `SECURITY DEFINER` function
  `config_decide_db_table_approval(id, decision)` (pinned `search_path`, no PUBLIC execute).
- **Who approves (D1).** The tenant's approver (`APPROVE_DB_TABLE`: TENANT_ADMIN, APPROVER), not the platform operator. A
  configurator (`EDIT_CONFIGURATION`) only requests. **D4:** the requester can never approve their own request, even in a
  one-admin tenant (same rule as contract approval, `V2_2_5_1`). *(D4 superseded by Amendment 2026-10-27: the requester
  may approve their own request; the role check, the target hash and the width probe still bind.)* Revoking is the safe direction and is open to the
  approver roles including the requester. **D3:** no column-level list; column control belongs in the target database's own
  column grants. **D2:** existing `approvedTables` entries migrated as `PENDING_APPROVAL` and the key was stripped from
  the stored settings; a DB send is refused until someone approves.
- **Approval is bound to the target** (Karan, 2026-10-22). Each row stores a SHA-256 fingerprint of host, port, database
  and account; an approval is refused if the system was repointed since the request, and a send or publish counts an
  approval only while the system's current settings still hash to it. Editing the host or database of a system therefore
  invalidates its approvals (revoke and request again).
- **Enforcement points.** (1) Save: a settings body carrying `approvedTables` is 400 `FIELD_NOT_ALLOWED`. (2) Publish: an
  interface whose DB table is not approved is refused through the ADR-017 contract (`InterfacePublishRefused`). (3) Send:
  `DbTargetCall` asks the approval table per send; unapproved or revoked is PERMANENT (destination refused); a failed
  lookup is TRANSIENT and retried, never read as allowed. (4) Every request, approval and revoke leaves an `AuditEvent`
  (`DB_TABLE_REQUESTED/APPROVED/REVOKED`) and, refused or not, a log line.
- **Honest limits.** Four-eyes inside one tenant is an audit trail and a separation of duties, **not an authorization
  boundary**: a tenant whose admin and approver collude can approve any table - but only in a database whose credentials
  that tenant supplied itself. *(Amendment 2026-10-27: there is no longer a second person to collude with - one
  TENANT_ADMIN may request and approve; the remaining limits are listed in that amendment.)* The decider is stamped into the transaction (`app.actor_id`) by the platform from the
  validated session, not authenticated by the database (the app connects as one role); a database-authenticated actor
  needs a tenancy-module change and stays open. The privilege probe at test-connection is now built but **reports
  only** (see the hardening addendum below); the account's real privileges remain the target database owner's
  responsibility, and this approval list is the platform-side control only. A platform-operator ceiling (option A)
  waits for the operator login (ADR-012).

### Addendum 2026-10-22 - F3 hardening (merge `d2aed86e`, on main at `aeddfdd9`)

Sources: Karan's re-check and Varun's review (`_reports/2026-10-22-f3-hardening-security.md`,
`_reports/2026-10-22-f3-hardening-review.md` in the project workspace). Container tests are named for each control;
both reports are static reads (neither reviewer ran them).

- **S1 - an INSERT is only ever a fresh request** (`V2_4_1340:15-31`). The one `FOR ALL` policy of `V2_4_1330` is
  replaced by one policy per command: SELECT (tenant); INSERT `WITH CHECK` tenant AND `status = 'PENDING_APPROVAL'`
  AND `decided_by`, `decided_at`, `revoked_by`, `revoked_at` all NULL; UPDATE (tenant). The app role still holds
  SELECT and INSERT only - no UPDATE, no DELETE (`V2_4_1330:101`) - so the UPDATE policy serves only the definer
  function `config_decide_db_table_approval`, which runs as the owner under FORCE ROW LEVEL SECURITY. APPROVED and
  REVOKED are reachable only through that function; it refuses self-approval (`REFUSED_SELF_APPROVAL`, backed by the
  `four_eyes` CHECK). *(Superseded by Amendment 2026-10-27: `V2_4_1420` drops `db_target_approved_table_four_eyes` and
  the requester branch of the function; `REFUSED_SELF_APPROVAL` is no longer returned. The rest of S1 stands.)* `INSERT ... ON CONFLICT DO UPDATE` needs UPDATE and is refused (42501). No DELETE policy:
  deliberate; a later purge job must run as a role that bypasses RLS. Tests: `anInsertIsAlwaysPending`,
  `noUpdateAndNoDeleteForTheAppRole` (`ApprovedTableMigrationContainerTest`); no test names the ON CONFLICT case.
- **S5 - the fingerprint includes `sslmode`** (`V2_4_1340:39-46`). One SQL definition, `db_target_fingerprint`, read by
  the send, the publish check, the request insert and the decision, so weakening `verify-full` to `require` invalidates
  an approval like a repoint does. The data step (`V2_4_1340:48-`) re-hashes **only** rows whose stored hash equals
  the old four-field hash of the system's current settings; a row already stale from a repoint keeps its old hash and
  stays refused, so the upgrade never revives it (Karan F-1). Tests: `sslmodeIsPartOfTheFingerprint`,
  `aRepointedRowStaysStaleAcrossTheUpgrade`.
- **S2 - privilege probe, report only** (`JdbcDbPrivilegeProbe.java:32-45`). Test-connection asks the server for the
  account's effective privileges on `public.<table>` (roles, PUBLIC and inheritance included): INSERT, SELECT, UPDATE,
  DELETE, TRUNCATE, TRIGGER, BYPASSRLS, superuser, CREATE on the schema, membership of `pg_write_all_data`. Anything
  beyond INSERT and SELECT appends `WIDE ACCOUNT` to the answer's `detail` text (`DbTestConnection.java:113-121`); the
  answer stays `SUCCESS` and nothing gates a send or an approval on it. Not probed: REFERENCES, column-level grants,
  CREATEROLE, the other predefined roles, privileges on other tables; a missing table reads as "none". **Open
  decision (Dipesh):** refuse a WIDE account at approve time, or keep the probe informational - recommended: refuse,
  after widening what counts as WIDE.
- **S3 - `currentSchema=public` pinned** in `JdbcDbSender.accountProperties` (`JdbcDbSender.java:145-147`), shared by
  the send and the probe; it outranks a role-level `search_path` (test `theSchemaIsPinnedAgainstARoleSearchPath`).
  **Behaviour change:** an existing target whose account relied on another default schema now writes to
  `public.<table>` or fails 42P01/42501 - move or grant the table before upgrading.
- **Operator notes** (`JdbcDbSender.java:31-38`). The restricted account needs SELECT as well as INSERT (the
  `delivery_id` pre-state check reads the table). The partner `delivery_id` column must be `uuid` (a text column fails
  42883, PERMANENT). An INSERT-only account fails 42501, classified PERMANENT with a message naming INSERT and SELECT
  (`DbTargetCall.java:138-144`); the same text also appears for other 42501 causes (missing USAGE on the schema or the
  table's sequence). Before go-live the operator lists every migrated PENDING row to the approver: DB sends stop until
  each table is approved.
- **Known residuals - accepted risk pending Dipesh.**
  (a) Code running as the app role still chooses `requested_by`, `requested_at` and `target_hash` on a PENDING row, so
  it can name another user as requester and then approve through the function with its own stamped actor; it cannot
  insert an APPROVED row. **Open decision (Dipesh):** accept and record here, or add a `BEFORE INSERT` trigger deriving
  `requested_by` from `app.actor_id` and `target_hash` from the live settings - recommended: accept; add the trigger
  only if he asks. *(Amendment 2026-10-27: (a) no longer buys anything - a requester may approve their own request -
  so the trigger is not needed for separation of duties; a forged `target_hash` is still caught at send and publish,
  which compare against the live settings.)* (b) On PostgreSQL 14 and older PUBLIC holds CREATE on `public`, so every account reads WIDE (a
  false positive; accurate on 15+). Target database versions: TBD. (c) A repoint A to B and back to A revives the
  original approval (only the live hash is compared); a `TARGET_CHANGED` state would close it - not built. (d) The
  table owner (migration role) can still write any state; DBA-level, outside the app's reach.

## Amendment 2026-10-27 - single-user self-approval: author/approver separation of duties removed

**Status: Proposed - awaiting the project owner's written acceptance.** Built on branch `feat/single-user-self-approval`
(`94aac35f` tests first, `cfb30de1` change); plan `_documents/PLAN-single-user-self-approval.md` in the project
workspace. This amendment supersedes, for the three approvals below, every statement above that an author or a
requester cannot approve their own work (Decision 2 table check, Decision 3 rule 1, amendment 2026-10-22 D4 and
honest limits, addendum S1, residual (a)). Those statements stay above as history, each marked where it is superseded.

- **Context.** A tenant run by one person could not take a flow version, a contract version or a DB-table request from
  draft to live: three table CHECKs and three `SECURITY DEFINER` functions refused the author (or requester) as approver,
  so a one-admin tenant was blocked by design (D4 said so: "even in a one-admin tenant"). The project owner decided on 2026-10-26
  to let one tenant admin carry the whole lifecycle.
- **Decision.** `V2_4_1420__allow_self_approval.sql` drops `interface_version.approver_not_author`,
  `contract_version.approver_not_author` and `db_target_approved_table_four_eyes`, and replaces
  `config_approve_version`, `config_approve_contract_version` and `config_decide_db_table_approval` with their latest
  definitions minus the author / requester branch (`V2_4_1420__allow_self_approval.sql:29-31`). A single TENANT_ADMIN
  may author, approve and publish. Who may approve is still the role check (`APPROVE_VERSION`, `APPROVE_DB_TABLE`:
  TENANT_ADMIN, APPROVER - `RolePolicy.java:68,79`); only the "not the author / not the requester" rule goes.
  `REFUSED_AUTHOR_IS_APPROVER` and `REFUSED_SELF_APPROVAL` are no longer returned; old `AUTHOR_IS_APPROVER` refusal
  rows stay valid. Earlier migrations are not edited.
- **Consequences.**
  - Risk (security, verbatim): Separation of duties between author and approver is removed for flows, contracts and
    DB-table approvals. A single TENANT_ADMIN is trusted for the whole lifecycle. Residual: a compromised admin session
    can publish without a second person; attribution is by audit only. Reversible by migration.
  - An existing two-user workflow is unchanged; the constraint is simply no longer enforced.
  - The width probe no longer skips the requester (`ApprovedTableService.preflight`, `cfb30de1`): with the self-approval
    refusal gone, that skip would have bypassed the account-width check.
- **What still limits a compromised admin session.**
  1. **Tenant isolation** - every approval function scopes by the stamped `app.tenant_id` under forced row-level
     security (ADR-009); an admin reaches only their own tenant's rows.
  2. **Destination allow-list** - Decision 1's outbound destination check per type still applies to every send.
  3. **Width probe** - a DB-table approval is still refused for a partner account wider than INSERT and SELECT
     (`DB_ACCOUNT_TOO_WIDE`, `ApprovedTableService.java:135-140`), now including the requester's own approval.
  4. **Target hash** - an approval counts only while the system's live settings still hash to it (fingerprint incl.
     `sslmode`, S5); a repoint invalidates it.
  5. **Audit by actor** - every request, approval, revoke and publish leaves an `AuditEvent` with the actor stamped
     from the validated session; this is now the only attribution.
- **Reversible by migration.** Re-add the three constraints and restore the three functions from `V2_2_4:124-148`,
  `V2_2_5_1:25-45` and `V2_4_1330` in a new migration; prerequisite: no row has `approver_id = author_id` or
  `decided_by = requested_by` (repoint those first). Recipe: `V2_4_1420__allow_self_approval.sql:21-27`.
- **Revisit if.** A tenant gets a second admin or approver who should be required; a regulated tenant or partner asks
  for four-eyes; any security incident traced to a single admin session; or the project owner does not accept this amendment
  (then revert by migration as above before merge).
