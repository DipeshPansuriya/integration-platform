---
type: ADR
title: "ADR-014 - Message store and one delivery per message and route: day-partitioned message and payload on the accepted day, deliveries created on their message's day, unique (message_id, route_id, created_at)"
status: "Accepted (approved by Dipesh 2026-10-12, as recommended) - payload retention 30 days; ADR-010 sections 2 and 6 and architecture.md sections 3 and 4.1 amended in the same change; unblocks E8 lane A and E9 lane C"
approved: "Dipesh, 2026-10-12 (hub memory m-f1fcfc)"
date: 2026-10-11
author: "mahesh-architect (ledger row 307 under row 278, pre-plan open items M5 and M6)"
project: ipass/integration-platform
provenance: new-decision
related:
  - ADR-005-messaging.md
  - ADR-010-camel-routing-core.md
  - ADR-011-targets-contracts-bulk-ordering.md
  - ADR-012-job-scheduler.md
  - ADR-013-mapping-versions.md
  - ../architecture.md
---

# ADR-014 - Message store and delivery uniqueness

Conventions and grades as in [ADR-013](ADR-013-mapping-versions.md). The two questions are one
decision: the way the message table is partitioned decides how the delivery can be unique.

## Context

- **No `message` or `payload` table exists**; the tables created so far are tenancy, outbox,
  idempotency key, delivery family and configuration (`git grep "CREATE TABLE"` on
  `row278-integration` `8a3d7b5`, 0 hits for message/payload). [Verified]
- The architecture fixes the message shape and its retention: interface version, source contract
  version, business key, per-key sequence, state `ACCEPTED`/`ROUTED`/`ROUTE_FAILED`, stage
  timestamps, and **daily partitions for message and payload**, a partition dropped only when 0
  dead-lettered or non-final deliveries reference it (`documents/architecture.md:78`;
  `documents/adr/ADR-011-targets-contracts-bulk-ordering.md:293-297`). [Verified]
- Intake writes message, payload and outbox in one transaction (`architecture.md:98`); stage 1
  moves the message `ACCEPTED -> ROUTED` by conditional update and inserts one delivery per matched
  route with insert-on-conflict `(message_id, route_id)` plus one outbox row each
  (`architecture.md:102`; `documents/adr/ADR-010-camel-routing-core.md:97-101`, `:215`). [Verified]
- `delivery` is `PARTITION BY RANGE (created_at)` with `PRIMARY KEY (id, created_at)`
  (`platform-delivery/src/main/resources/db/migration/V2_3_1__delivery_partitioned.sql:24-43`);
  purge is a partition drop, never a row-by-row DELETE (`:1-3`). PostgreSQL requires a unique
  constraint on a partitioned table to include every partition-key column, so `UNIQUE
  (message_id, route_id)` cannot be created on `delivery`. [Verified schema; Inferred PostgreSQL
  17 rule - the documented partitioning limitation, not re-run here]
- The delivery insert already takes `created_at` from its caller
  (`platform-delivery/src/main/java/io/integrationplatform/delivery/internal/JdbcDeliveryRepository.java:43-56`)
  and every lookup is by `id` alone across partitions (`:60-152`). [Verified]
- Load figure: 50 messages per second (`ADR-011:273`) is 4.32 million messages a day, each with
  one or more deliveries. [Verified figure; Inferred arithmetic]
- The tenant work function `ipaas_work_tenant(kind, id)` that consumers must use (ADR-011
  Decision 8, `ADR-011:281-287`) exists only as a name in the architecture rule
  (`arch-test/src/test/java/io/integrationplatform/archtest/TenantFilterRule.java:46`); no
  migration defines it. [Verified]

## Options - the message table (M5)

| Option | Complexity | Risk | Cost | Reversibility |
|---|---|---|---|---|
| **A. Non-partitioned `message`, day-partitioned `payload`** (pre-plan) | Low | Purging 4.32 M message rows a day becomes a row DELETE with its vacuum and bloat - the exact pattern `V2_3_1:1-3` rejects; contradicts `architecture.md:78` | Daily DELETE load grows with traffic | Low once data exists |
| **B. Both day-partitioned on `accepted_at`, payload carrying its message's `accepted_at`** (chosen) | Medium | No FK into `message` - already the delivery family's rule (`V2_3_1:9-12`); by-id lookups probe each kept partition, as delivery does today | ~38 kept partitions per table (30-day retention + 7 ahead + 1) | Low once data exists |
| C. One partitioned table with the payload as a column | Low | Mutable state and immutable business data share grants; payload cannot be INSERT/SELECT-only | Same as B | Low |

## Options - one delivery per message and route (M6)

| Option | Complexity | Risk | Cost | Reversibility |
|---|---|---|---|---|
| **1. Deterministic day: `delivery.created_at := message.accepted_at`; `UNIQUE (message_id, route_id, created_at)`** (chosen) | Low - one index, one rule on the insert | A caller passing any other instant defeats it (controlled below) | 0 extra writes | High - drop the index |
| 2. Non-partitioned `delivery_route (message_id, route_id) PRIMARY KEY` written in the same transaction | Medium | Grows one row per delivery with no partition to drop; a row DELETE purge again - or partition it by message day, which is option 1 plus a table | 1 extra insert per delivery | High |
| 3. Advisory lock on the message id, then check-then-insert | Low | Not a constraint: one code path that forgets the lock duplicates; invisible in the schema | 1 lock per message | High |
| 4. Rely only on the `ACCEPTED -> ROUTED` conditional update | None | ADR-010:100 asks for a key that holds "even if the conditional update were bypassed" | 0 | - |

A global unique index across partitions does not exist in PostgreSQL 17 (`compose/compose.yaml:10`
pins `postgres:17.11-alpine`). [Verified version; Inferred feature]

## Decision (Accepted 2026-10-12, approved by Dipesh)

### 1. `message` and `payload` (E8 lane A, `V2_1_810-819`)

```sql
CREATE TABLE public.message (
    id                         uuid        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id                  uuid        NOT NULL REFERENCES public.tenant (id),
    interface_id               uuid        NOT NULL,
    interface_version_id       uuid        NOT NULL,
    source_contract_version_id uuid        NOT NULL,
    business_key               text        NOT NULL,   -- the rule is mandatory (V2_2_4:24)
    key_seq                    bigint,                 -- NULL when ordering is NONE
    state                      text        NOT NULL DEFAULT 'ACCEPTED'
                               CHECK (state IN ('ACCEPTED', 'ROUTED', 'ROUTE_FAILED')),
    accepted_at                timestamptz NOT NULL DEFAULT now(),
    routed_at                  timestamptz,
    updated_at                 timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, accepted_at)
) PARTITION BY RANGE (accepted_at);

-- reconciler: ACCEPTED older than 5 minutes (architecture.md:107)
CREATE INDEX message_unrouted_idx ON public.message (tenant_id, accepted_at) WHERE state = 'ACCEPTED';
-- the hold: an earlier same-key message still ACCEPTED (ADR-010:216)
CREATE INDEX message_ordering_idx ON public.message (tenant_id, interface_id, business_key, key_seq)
    WHERE state = 'ACCEPTED';

CREATE TABLE public.payload (
    message_id  uuid        NOT NULL,
    tenant_id   uuid        NOT NULL REFERENCES public.tenant (id),
    accepted_at timestamptz NOT NULL,          -- copied from its message: same day partition
    body        jsonb       NOT NULL,
    PRIMARY KEY (message_id, accepted_at)
) PARTITION BY RANGE (accepted_at);
```

- Row-level security: `ENABLE`, error-deny policy with `WITH CHECK`, exactly the delivery-family
  form (`V2_3_1:67-71`, `V2_0_1__outbox.sql:26-30`). Not `FORCE`: the owner-run maintenance and
  purge guard of ADR-011 Decision 8 must see the table family the same way they see `delivery`.
  [Inferred]
- Grants: `message` - SELECT, INSERT, UPDATE (state, routed_at, updated_at); `payload` - SELECT,
  INSERT. DELETE and TRUNCATE revoked on both (purge is the maintenance role's partition drop).
  The application reaches partitions only through the parent. [Inferred]
- Days pre-created `CURRENT_DATE - 1 .. + 7` like `V2_3_1:51-63`; no DEFAULT partition (a default
  partition blocks later `CREATE ... PARTITION OF` for any range it holds rows for). [Inferred]
- `ipaas_work_tenant(kind text, id uuid) RETURNS uuid`, SECURITY DEFINER, kinds `MESSAGE` and
  `DELIVERY`, returns only the tenant id (ADR-011 Decision 8). It reads `delivery`, so it is
  numbered after the delivery family: `V2_3_811` (the pre-plan's "higher prefix" rule). [Inferred]
- `ordering_key` stays with intake (T-196, E11, `ws/_specs/backlog.md:3016`). [Verified]
- **Relation to the outbox.** Intake: message + payload + one outbox row (`topic =
  platform.message.accepted`, `aggregate_id = message.id`). Stage 1: deliveries + one outbox row
  each (`platform.delivery.due`, `aggregate_id = delivery.id`). The record value stays ids only
  (ADR-011 Decision 7). The outbox is not partitioned and has no DELETE grant
  (`V2_0_1__outbox.sql:9-34`) - see Open 2. [Verified columns; Inferred flow]

### 2. One delivery per message and route (E9 lane C, `V2_3_930-939`)

```sql
ALTER TABLE public.delivery
    ADD COLUMN message_id         uuid,
    ADD COLUMN route_id           uuid,
    ADD COLUMN mapping_version_id uuid;   -- ADR-013
CREATE UNIQUE INDEX delivery_message_route_unique
    ON public.delivery (message_id, route_id, created_at);
```

- **Rule:** a delivery's `created_at` is its message's `accepted_at`, read from the message row
  stage 1 has just locked. Because `accepted_at` is fixed by `message_id`, the triple is unique
  exactly when the pair is. The insert is `ON CONFLICT (message_id, route_id, created_at) DO
  NOTHING RETURNING id`; 0 rows means "already routed" and writes no outbox row. [Inferred]
- The pinned target endpoint reuses the existing `target_id` (`V2_3_1:41`, the ordering scope
  column) - no `target_endpoint_id` column. `contract_version_id` exists (`V2_3_3_1:15`).
  [Inferred]
- **Control against a wrong instant:** the `Stage1Deliveries` seam port takes the message
  (id and `accepted_at`) - not a free `Instant` - and is the only caller that sets `message_id`
  (architecture test). Replay re-drives the same row (`DEAD_LETTERED -> DUE`, `ADR-010:225`), so
  it never inserts a second delivery. [Verified replay rule; Inferred control]
- The `created_at` meaning changes from "inserted at" to "the message's day" (`V2_3_1:7-8` stays
  true: it never changes). Stage-1 timing comes from `message.routed_at`, never from
  `delivery.created_at`. [Inferred]

### 3. Retention and purge (built by the purge job of ADR-012 / E14)

- One day `D` is one unit: `message_D`, `payload_D` and `delivery_D` are created together and
  dropped together, after retention (**30 days - decided by Dipesh 2026-10-12**, m-f1fcfc; backlog Open 9), and only when the
  ADR-011 guard counts **0** deliveries in `delivery_D` that are `DEAD_LETTERED` or not final.
  A delivery always sits in its message's day, so the guard is one count on one partition.
  `delivery_attempt` keeps its own insert-day partitions; its retention is E14's. [Inferred]

## Process - stage 1, with its failure paths

```mermaid
sequenceDiagram
    participant K as Kafka (message.accepted)
    participant S1 as Stage 1 (routing)
    participant DB as PostgreSQL
    K->>S1: {v, type, id}
    S1->>DB: ipaas_work_tenant(MESSAGE, id) then bind tenant
    S1->>DB: BEGIN; key lock; UPDATE message SET state=ROUTED WHERE id AND state=ACCEPTED
    alt 0 rows (duplicate record)
        S1->>DB: ROLLBACK - no effect
    else 1 row
        S1->>DB: INSERT delivery (created_at = accepted_at) ON CONFLICT DO NOTHING, per route
        S1->>DB: INSERT outbox per new delivery; COMMIT
    end
    S1->>K: commit offset
```

| Failure | What happens | Who finds out |
|---|---|---|
| Record published twice | second run updates 0 rows; even if bypassed, the unique index inserts 0 rows | the duplicate test (ADR-010:100 gate) |
| Day partition missing (maintenance late) | insert fails, transaction rolls back, message stays `ACCEPTED`; treated as a transient database error: consumer pauses and backs off (ADR-010:331) | partition-horizon alert of the maintenance job; reconciler re-drives after 5 min |
| Pinned configuration unreadable | `ROUTE_FAILED`, alert (`architecture.md:102`) | alert |
| Crash after commit, before offset commit | redelivered record updates 0 rows | nobody needs to - harmless |

External calls: none added - stage 1 talks only to PostgreSQL and Kafka, whose five controls
are T-191 (E8) and T-197 (E9).

## Conformance tests this ADR adds

1. Same `message.accepted` record twice -> N deliveries for N matched routes, not 2N (ADR-010 gate).
2. With the conditional update bypassed (test hook), a second stage-1 insert for the same message
   and route on a **different clock day** inserts 0 rows (proves the day comes from the message).
3. `UPDATE`/`DELETE` on `payload` and `DELETE` on `message` as `ipaas_app` fail with 42501.
4. A forged tenant header or value on a record changes nothing (`ipaas_work_tenant` path).
5. Purge guard: day `D` holding 1 dead-lettered delivery survives the purge; a day with 0 is
   dropped with its message, payload and delivery partitions.

## Consequences

- Gains: a real database constraint for ADR-010's second guard at zero extra writes; message,
  payload and delivery purge as one day; the architecture's partitioning kept.
- **Gives up:** no foreign keys into `message` or `delivery` (link enforced by RLS and the insert
  paths, as today); `delivery.created_at` no longer means insert time; by-id lookups probe every
  kept partition (about 38 index probes each).
- Applied 2026-10-12 with the approval: ADR-010 section 2 (stage 1 and the unique-key paragraph),
  section 6 (the `PENDING` row) and `architecture.md` sections 3 and 4.1 (step 8) now read "(message_id, route_id)
  unique per message day - created_at = the message's accepted_at".

## Revisit if

- The by-id stage load measured in T-122 exceeds 10 ms at p95 - then carry the accepted day in the
  record or use time-ordered ids so lookups prune partitions.
- Retention grows past 90 days (over ~100 partitions per table).
- A flow needs a second delivery for the same message and route (replay as a new row).

## Open

1. **Decided (Dipesh, 2026-10-12):** payload retention is 30 days (backlog Open 9).
2. **E14 (not an E8/E9 blocker):** the outbox grows by at least 2 rows per message and nothing
   removes published rows (`V2_0_1__outbox.sql:33-34`). Recommend the E14 purge job delete
   published rows older than 24 hours in batches as the maintenance role.
