---
type: Index
title: "Architecture decision records - index"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0)"
project: ipass/integration-platform
---

# Architecture decision records - index

The previous ADR files were deleted with the old repository on 2026-10-02. The records below
were recreated with per-section provenance, or written new, in phase 0 of the rebuild. Dipesh
re-confirms each recreation; every decision stays reversible on his review.

| ADR | Title | Status | Provenance |
|---|---|---|---|
| [ADR-001](ADR-001-modular-monolith.md) | One deployable, a modular monolith with enforced module boundaries | Accepted (team) - **new decision, not a recreation**, for Dipesh's re-confirmation | No hub source records the original subject |
| [ADR-002](ADR-002-datastore.md) | Datastore: PostgreSQL 17, configuration as rows and JSONB, Flyway-owned schema | Accepted - recreated | Recreation |
| [ADR-003](ADR-003-rules-engine.md) | Rules engine: GoRules ZEN decision tables, authored in jdm-editor | Accepted - recreated | Recreation |
| [ADR-004](ADR-004-mapping-canvas.md) | Mapping canvas: React Flow core library | Accepted - recreated | Recreation |
| [ADR-005](ADR-005-messaging.md) | Messaging backbone: Apache Kafka, carrying work only | Accepted (team, 2026-10-02) - Dipesh may reverse | Context recreated; decision new (was Deferred) |
| ADR-006 | Transformation engine | **Not yet written** - written in the mapping phase, before the canvas code, after its spike | - |
| [ADR-007](ADR-007-identity.md) | Identity: Keycloak, tenant bound from our database, target credentials encrypted with an out-of-database key | Accepted (team, 2026-10-02) - Dipesh may reverse | New |
| [ADR-008](ADR-008-camel-adapter-only.md) | Apache Camel as outbound adapters only in v1 | **Superseded by ADR-010** | Recreation, for the record |
| [ADR-009](ADR-009-tenant-directory-rls.md) | Tenant directory under row-level security, one trusted login bootstrap function | Accepted (Dipesh, 2026-09-30) - recreated | Recreation |
| [ADR-010](ADR-010-camel-routing-core.md) | Apache Camel as the routing core, inside one module, with exactly two retry layers | Accepted (team, 2026-10-02) - Dipesh may reverse | New |
| [ADR-011](ADR-011-targets-contracts-bulk-ordering.md) | Target types, contract registry and version graph, bulk at intake, ordering, ids-only Kafka records, background tenant | Accepted (team, 2026-10-02) - Dipesh may reverse | New |
| ADR-012 | Job scheduler | **Not yet written** - written in the scheduler phase, before its code | - |

## Closed registers and where they live

A list that other documents index into changes only through the ADR that owns it, and that ADR
re-publishes every dependent list in the same change.

| Register | Owner |
|---|---|
| Delivery states and transitions; message states; outcome classes (default table, overridable per target by its error dictionary); the six controls on a target call | ADR-010 |
| Target types | ADR-011 |
| Kafka topics | ADR-005 |
| Definer functions that cross the tenant boundary | ADR-009, ADR-007, ADR-011 (listed together in [architecture.md](../architecture.md) section 5) |
| Roles | ADR-007 |
| Camel starters in use | ADR-010 |
