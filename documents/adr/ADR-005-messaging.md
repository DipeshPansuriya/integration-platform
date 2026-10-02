---
type: ADR
title: "ADR-005 - Messaging backbone: Apache Kafka, carrying work only"
status: "Accepted (team, 2026-10-02) - Dipesh may reverse. Settled by the original ADR-005's own decision rule; the original (Deferred) record was deleted 2026-10-02"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0)"
project: ipass/integration-platform
provenance: "rewrite - context recreated from surviving sources, decision new"
related:
  - ADR-010-camel-routing-core.md
  - ADR-011-targets-contracts-bulk-ordering.md
  - ADR-002-datastore.md
---

# ADR-005 - Messaging backbone: Apache Kafka, carrying work only

> **Rewritten as Accepted.** The original ADR-005 was **Deferred** and was deleted with the old
> repository on 2026-10-02. Its context and decision rule are recreated from surviving sources
> (`ws/_specs/project-plan.md:167`, provenance table); the decision is new and is settled by that
> rule now that its two open questions are answered.

Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`. Memory lines drift; the id is the stable key.

## Context

*Recreated. Source: `ws/_specs/f3-plan-brief.md:211-213` (the deleted compose file's comment);
`ws/_reports/2026-09-30-oss-rnd.md:22`, `:170`.*

The original record deferred the broker choice: "the messaging backbone is deliberately absent -
ADR-005 defers that choice until OQ-02 (ordering) and OQ-08 (volume) are answered". The two
candidates were Apache Kafka (Apache-2.0) and RabbitMQ (MPL-2.0); both licences were checked and
carry no risk, provided vendor distributions are avoided (Redpanda server is BSL; some Kafka UIs
are commercial).

**The decision rule** (*source: `ws/_specs/project-plan.md:25`*): ordering per business key
required -> Kafka; otherwise the lighter broker.

**The answers** (*source: `ws/_specs/project-plan.md:142-143`; `ws/_memory/shared.md:25`,
m-703833*):

| Question | Answer (2026-10-02) |
|---|---|
| OQ-02 - must messages with the same business key be delivered in order? | **Yes** |
| OQ-08 - volume and size | About **50 messages per second at peak**, payloads **under 1 MB** |
| Technology direction | Dipesh's binding decision: Apache Kafka for queue and async |

## Options

| Option | Per-key ordering | Fit | Verdict |
|---|---|---|---|
| **Apache Kafka** | Native: same record key -> same partition -> order kept | Dipesh's binding direction; Camel's Kafka component is mature | **Chosen** |
| RabbitMQ | Only with single-active-consumer per queue or consistent-hash exchanges | Lighter to run | Rejected by the rule (ordering required) and by the direction |
| No broker - PostgreSQL polling only | Possible with `SKIP LOCKED` and per-key locking | Fewest moving parts | Rejected: contradicts the binding direction; kept as the scheduler's mechanism only |

## Decision

1. **Apache Kafka is the work backbone**, in KRaft mode (no ZooKeeper; Kafka 4.x has none).
   The broker image tag is pinned by the skeleton phase; dev and test run 1 broker, production
   runs at least 3 brokers with replication factor 3 and `min.insync.replicas` 2.
2. **Client: `kafka-clients` 4.3.1** - the version Camel 4.22.1's Kafka component compiles
   against. Spring Boot 4.1.1's BOM manages 4.2.1, so the build pins `kafka.version` to 4.3.1
   and a dependency-convergence check fails the build unless exactly 1 kafka-clients version is
   resolved (*source: `ws/_specs/project-plan.md:79`, `:198`*).
3. **spring-kafka stays off the classpath.** Camel's Kafka component is the only Kafka client
   user, inside `platform-routing` (ADR-010). The outbox relay publishes through the publish port
   defined in phase 2, whose Kafka implementation lives in `platform-routing`.
4. **Kafka carries work only - never retry timing, never payloads.** Records carry ids
   (ADR-011); retry timing lives in the Delivery rows in PostgreSQL (ADR-010). There are **no
   retry topics and no delay topics**.
5. **Topics** (names are configuration; these are the defaults):

   | Topic | Carries | Key | Retention |
   |---|---|---|---|
   | `platform.message.accepted` | one record per accepted message (stage 1: route and fan out) | hash of tenant + interface + business key | 7 days |
   | `platform.delivery.due` | one record per delivery to execute now - first attempt, re-drive after a persisted retry wait, release from an ordering hold, or operator replay | the same key | 7 days |
   | `platform.poison` | records that cannot be read or whose referenced row does not exist | original key | 30 days |

   Re-driving a due delivery onto `platform.delivery.due` is not a retry topic: the record says
   "execute delivery D now"; when and whether to retry was decided in the database.
6. **Partitions:** 12 per work topic at start. At 50 messages per second this is far above the
   need; it bounds consumer parallelism per instance group. The partition count is changed only
   in a quiet window, because a change remaps keys to partitions (ordering is still protected by
   the database hold rule in ADR-011).
7. **Producer:** idempotent producer, `acks=all`. **Consumer:** commits the offset only after
   the database transaction for the record has committed (at-least-once); a duplicate record is
   detected by the conditional claim on the delivery row (ADR-010) and acknowledged without
   effect.
8. **Poison topic is terminal.** A record goes there once, with the reason in a header; nothing
   consumes it automatically; an alert fires on every arrival; engineers inspect it in Kafbat UI.
9. **Trace context** travels as the W3C `traceparent` header; the outbox row stores it and the
   relay restores it (*source: `ws/_specs/project-plan.md:87`*).
10. **Engineers' view: Kafbat UI** (Apache-2.0, free, maintained); the business dashboard is
    built on PostgreSQL (*source: `ws/_specs/project-plan.md:91`; `ws/_reports/2026-09-30-oss-rnd.md:202-205`*).

## Consequences

- Same-key ordering comes from the partition; the database hold rule (ADR-011) keeps it across
  per-target retries.
- Payloads stay in PostgreSQL, so a lost or expired Kafka record loses no data: the outbox and
  the Delivery rows can re-drive any work.
- Running Kafka is a standing operational cost (brokers, disks, monitoring) for a volume
  PostgreSQL alone could carry. Accepted because of the ordering rule and Dipesh's direction.
- One more container in every end-to-end test suite (one suite at a time on the build PC).

**What we give up:** RabbitMQ's simpler operations and per-message delay features; Kafka's own
retry-topic pattern (deliberately unused - ADR-010 fixes exactly two retry layers).

## Revisit if

- Sustained volume passes 500 messages per second (10 times the stated peak) - re-size
  partitions and brokers.
- Records start needing payloads (for example, a target type that streams very large content) -
  that would reopen ADR-011's ids-only rule first.
- The poison topic receives more than 10 records in one day for two weeks - a systematic
  producer or schema defect, not an edge case.
