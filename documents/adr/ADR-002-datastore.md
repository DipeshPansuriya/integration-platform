---
type: ADR
title: "ADR-002 - Datastore: PostgreSQL 17, configuration as rows and JSONB, Flyway-owned schema"
status: "Accepted (originally before 2026-10-02; RECREATED 2026-10-02 from surviving sources) - Dipesh re-confirms"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0) - recreation"
project: ipass/integration-platform
provenance: recreation
related:
  - ADR-001-modular-monolith.md
  - ADR-009-tenant-directory-rls.md
  - ADR-011-targets-contracts-bulk-ordering.md
---

# ADR-002 - Datastore: PostgreSQL 17, configuration as rows and JSONB, Flyway-owned schema

> **Recreation.** The original file (`documents/adr/ADR-002-datastore.md`) was deleted with the
> old repository on 2026-10-02. Every section below names the surviving hub source it is rebuilt
> from (`ws/_specs/project-plan.md:164`, provenance table). Text marked **Added in the
> recreation** has no surviving source and is a new team decision for Dipesh's re-confirmation.

Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`.

## Context

*Source: `ws/_specs/f3-plan-brief.md:73` (quoting the deleted README.md:45), `:66`, `:74`,
`:167`, `:168` (verbatim quotes of the deleted ADR-002:33 and :35).*

The platform's premise is that configuration is data, not code: adding an interface inserts
rows and never alters the schema; if any onboarding step needs a deployment the platform has
failed its own premise (deleted README.md:45). Two requirements drove the datastore choice:

| Requirement | What it needs | Source |
|---|---|---|
| FR-9.2 - interface, routes and mappings publish as one atomic bundle | A single ACID transaction across several tables | ADR-002:33, quoted at `ws/_specs/f3-plan-brief.md:167` |
| Requirement 5 - no schema change per interface | JSONB columns hold source schemas, mapping definitions and payloads; adding an interface inserts rows | ADR-002:35, quoted at `ws/_specs/f3-plan-brief.md:168` |

## Options

*Source: `ws/_specs/f3-plan-brief.md:167` (the original comparison named MongoDB).*

| Option | Atomic bundle publish | Schema-free configuration | Tenant isolation | Verdict |
|---|---|---|---|---|
| **PostgreSQL** | A single ACID transaction - "native, unremarkable" | JSONB columns | Row-level security in the database | **Chosen** |
| MongoDB | Multi-document transactions exist but are "the awkward path, not the default one" | Native documents | Application-level only | Rejected |

Also out of scope from day one (*source: `ws/_specs/project-plan.md:229`*): MongoDB,
Elasticsearch/OpenSearch, a distributed cache, free-text search inside payload bodies.

## Decision

*Sources as marked per item.*

1. **PostgreSQL 17** is the single datastore for configuration, messages, payloads, deliveries,
   attempts, audit events and scheduled jobs (`ws/_specs/project-plan.md:71`, `:90`).
2. **Configuration is rows plus JSONB.** Source and target schemas, mapping definitions, route
   conditions and payloads are JSONB or text columns; no table per interface, no column per
   customer (deleted data-design.md:677 and README.md:45, quoted at
   `ws/_specs/f3-plan-brief.md:47`, `:73`).
3. **Flyway owns the schema; JPA validates only** (`ddl-auto: validate`). Configuration
   additions never need a migration - "that is the point of JSONB" (deleted backend-stack.md:31,
   quoted at `ws/_specs/f3-plan-brief.md:74`; `ws/_memory/architecture.md:10`).
4. **Closed value sets are `text` with a `CHECK`, never a PostgreSQL `ENUM`**, because adding an
   ENUM value needs a migration and an exclusive lock (deleted data-design.md:21, quoted at
   `ws/_specs/f3-plan-brief.md:74`).
5. **Every tenant-owned table carries `tenant_id` and a row-level security policy** - the
   database is the second wall behind the application filter (`ws/_specs/project-plan.md:103`;
   ADR-009 for the tenant directory itself).
6. **High-volume tables are partitioned by day from their first migration** (delivery, delivery
   attempt, payload, audit event), so purge is a partition drop, never a scan or a lock
   (`ws/_specs/project-plan.md:104`, `:117`).
7. **Added in the recreation:** the immutable published snapshot of a flow is one `jsonb` column
   holding the whole bundle, so a half-published bundle is structurally impossible (the deleted
   data-design.md:284 said this; quoted at `ws/_specs/f3-plan-brief.md:123`; it is restated here
   because the snapshot is now also where contract-version ids are pinned - ADR-011).

## Consequences

*Source: `ws/_specs/f3-plan-brief.md:66`, `:67` (publish is one transaction), `:74`, `:168`
(onboarding inserts rows); `ws/_specs/project-plan.md:104`, `:117` (partitions dropped by purge),
`:229` (MongoDB and free-text search inside payloads out of scope); measured lessons
`ws/_memory/shared.md:90` (m-af0e71), `:152` (m-13d1a2); otherwise derived from the decision
above; the measured lessons are in [rule.md](../rule.md).*

- Publish is one transaction; onboarding is inserts; both are testable on a running system.
- PostgreSQL holds payloads (Kafka carries ids only - ADR-011), so database storage grows with
  message volume and retention; payload partitions are dropped by the purge job.
- Test containers run real PostgreSQL; the image tag is pinned and the test JVM time zone is
  `Etc/UTC` (measured lessons, [rule.md](../rule.md)).

**What we give up:** a document store's flexible querying of payload contents and horizontal
write sharding. Free-text search inside payloads is out of scope.

## Revisit if

*Source: `ws/_specs/f3-plan-brief.md:395` (OQ-08: payload size, volume and retention were open
inputs); `ws/_specs/project-plan.md:117` (retention figures), `:229` (free-text search inside
payloads out of scope). The point of measurement (end of the Release 1 load test) is
**Added in the recreation** - new team decision, for Dipesh's re-confirmation.*

- Payload storage passes what one PostgreSQL primary can hold within the retention window
  (measure at the end of Release 1 load test).
- A requirement for free-text search inside payloads is accepted into scope.
