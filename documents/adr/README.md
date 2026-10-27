---
type: Index
title: "Architecture decision records - index"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0)"
project: ipass/integration-platform
---

# Architecture decision records - index

The previous ADR files were deleted with the old repository on 2026-10-02. The records below
were recreated with per-section provenance, or written new, in phase 0 of the rebuild. The project owner
re-confirms each recreation; every decision stays reversible on the project owner's review.
Author lines name planning-team roles ([../README.md](../README.md) explains the references).

| ADR | Title | Status | Provenance |
|---|---|---|---|
| [ADR-001](ADR-001-modular-monolith.md) | One deployable, a modular monolith with enforced module boundaries | Accepted (team) - **new decision, not a recreation**, for the project owner's re-confirmation | No hub source records the original subject |
| [ADR-002](ADR-002-datastore.md) | Datastore: PostgreSQL 17, configuration as rows and JSONB, Flyway-owned schema | Accepted - recreated | Recreation |
| [ADR-003](ADR-003-rules-engine.md) | Rules engine: GoRules ZEN decision tables, authored in jdm-editor | Accepted - recreated | Recreation |
| [ADR-004](ADR-004-mapping-canvas.md) | Mapping canvas: React Flow core library | Accepted - recreated | Recreation |
| [ADR-005](ADR-005-messaging.md) | Messaging backbone: Apache Kafka, carrying work only | Accepted (team, 2026-10-02) - the project owner may reverse | Context recreated; decision new (was Deferred) |
| [ADR-006](ADR-006-transformation-engine.md) | Transformation engine: JSONata (Dashjoin Java port) called directly by platform-transform, confirmed by the spike | **Accepted (the project owner, 2026-10-12)** - the spike T-030..T-032 still confirms gates G1-G4; lane D T-034/T-035 may start; T-036/T-039/T-108 wait for the spike | New |
| [ADR-007](ADR-007-identity.md) | Identity: Keycloak, tenant bound from our database, target credentials encrypted with an out-of-database key | Accepted (team, 2026-10-02) - the project owner may reverse; Decision 5 caller kinds amended 2026-10-12 by ADR-012 (`OPERATOR_AGENT`) | New |
| [ADR-008](ADR-008-camel-adapter-only.md) | Apache Camel as outbound adapters only in v1 | **Superseded by ADR-010** | Recreation, for the record |
| [ADR-009](ADR-009-tenant-directory-rls.md) | Tenant directory under row-level security, one trusted login bootstrap function | Accepted (the project owner, 2026-09-30) - recreated | Recreation |
| [ADR-010](ADR-010-camel-routing-core.md) | Apache Camel as the routing core, inside one module, with exactly two retry layers | Accepted (team, 2026-10-02) - the project owner may reverse; amended 2026-10-12 by ADR-014 (sections 2, 6) and ADR-015 (section 1); section 8 amended 2026-10-19 (global allow-list, T-202) | New |
| [ADR-011](ADR-011-targets-contracts-bulk-ordering.md) | Target types, contract registry and version graph, bulk at intake, ordering, ids-only Kafka records, background tenant | Accepted (team, 2026-10-02) - the project owner may reverse; amended 2026-10-22 (E16 F3 approved tables, project owner D1-D4) with F3 hardening addendum (two open decisions); amendment 2026-10-27 (author/approver separation of duties removed, V2_4_1420) Accepted - the project owner accepted the residual risk in writing on 2026-10-27 | New |
| [ADR-012](ADR-012-job-scheduler.md) | Job scheduler: db-scheduler, job definitions as our rows, platform-scoped jobs, no-tenant operator-agent credential, alerts as rows plus Prometheus | **Accepted (the project owner, 2026-10-12)** - tenant admins hold no job operation; agent polls alerts every 5 min, no push in R1; no-tenant machine credential with its attack suite in T-147; per-task acceptance criteria added 2026-10-19 (T127 = T-060) | New |
| [ADR-013](ADR-013-mapping-versions.md) | Mapping versions: immutable, content-addressed `mapping_version` rows created at draft save, referenced from the snapshot, pinned on deliveries | **Accepted (the project owner, 2026-10-12)** - unblocks E7 lane C1 and E9 T-117 | New |
| [ADR-014](ADR-014-message-store-and-delivery-uniqueness.md) | Message store and delivery uniqueness: day-partitioned message and payload, deliveries on their message's day, unique (message_id, route_id, created_at) | **Accepted (the project owner, 2026-10-12)** - payload retention 30 days; ADR-010 sections 2 and 6 amended; unblocks E8 lane A and E9 lane C | New |
| [ADR-015](ADR-015-target-type-register-and-adapter-placement.md) | Target adapters in platform-routing; system-type register aligned to ADR-011 (MQ_CSV, KAFKA_TOPIC) in E10 | **Accepted (the project owner, 2026-10-12)** - ADR-010 section 1 and architecture.md section 2 amended; code rename in E10; E16 addendum 2026-10-19 (T0 dispatch, Kafka/DB/File lanes, FILE = shared folder in R2) | New |
| [ADR-016](ADR-016-retention-enforcement.md) | Retention enforcement: fifth job type `AUDIT_PARTITION_MAINTENANCE` run as the maintenance role; metrics and trace retention in the Prometheus and Tempo configuration; log retention enforced by the deployment's log collector | Decisions 1-2 Accepted (team, 2026-10-17) - the project owner may reverse; **Decision 3 (logs) decided by the project owner 2026-10-17: A** - stdout only, 30 days enforced by the deployment's log collector (deployment-checklist item), B (Loki) rejected for now; amends ADR-012 Decision 2 and architecture.md section 5 | New |
| [ADR-017](ADR-017-publish-refusal-contract.md) | A refused publish crosses routing -> config as the config-owned public `InterfacePublishRefused`; routing keeps its own refusal type; first enforced rule of ADR-015 Decision 2 | Accepted (team, 2026-10-17) - the project owner may reverse; amends ADR-015 Decision 2 (review finding C2, row 278) | New |
| [ADR-018](ADR-018-jms-client.md) | JMS client for the MQ_CSV target (T98): Artemis JMS client inside platform-routing, Spring auto-configuration off | Accepted (team, 2026-10-19) - the project owner may reverse | New |

## Status against the code at main `90c1ddd` (2026-10-16)

The decisions above are unchanged; an ADR is a record of what was decided and why. This table only says where the
code does not carry a decision out yet, so nobody reads an accepted decision as running code. Status words as in
[architecture.md](../architecture.md) section 9.

| ADR | Where the code stands | Evidence |
|---|---|---|
| ADR-001 | One deployable built; ArchUnit rules enforce boundaries; no Spring Modulith; `platform-ledger`, `platform-audit` and `platform-e2e` are not modules yet | `pom.xml:23-38`; `arch-test/src/test/java/io/integrationplatform/archtest/` |
| ADR-005 | Topics are named in code; the relay and the consumers are **not wired**; the Kafka publish port is created only when `IPASS_KAFKA_BOOTSTRAP_SERVERS` is set | `platform-routing/src/main/java/io/integrationplatform/routing/publish/KafkaPublishPortConfiguration.java:29` |
| ADR-006 | JSONata runs behind the mapping test only; transform in the send path **planned** | [architecture.md](../architecture.md) section 9 |
| ADR-009, ADR-011 (definer functions) | 3 of the 4 functions exist; `ipaas_active_tenants` has no migration yet | [architecture.md](../architecture.md) section 9 |
| ADR-010 | Camel routing, layer-1 send route, outcome classes and the state register are built; stage consumers, the per-target bulkhead and its bulkhead-full deferral, claim and send are **not wired**; reconciler, stale-claim sweeper and the per-target send breaker **planned** | `platform-routing/src/main/java/io/integrationplatform/routing/stage2/LayerOneRouteBuilder.java:28-31`; `platform-routing/src/main/java/io/integrationplatform/routing/stage2/bulkhead/TargetBulkhead.java:23-36`; `platform-delivery/src/main/java/io/integrationplatform/delivery/internal/deferral/JdbcBulkheadDeferrals.java:31` |
| ADR-011 Decision 4-5 | Single-record intake endpoint built; bulk, the idempotency store, intake validation and `SYNC` **planned** | [interface.md](../interface.md) section 2, "As built" |
| ADR-012 | db-scheduler runs 4 recurring jobs; their bodies are no-ops or skipped (`DELIVERY_RETRY` has no handler) | `platform-app/src/main/java/io/integrationplatform/app/scheduler/SchedulerJobTasks.java:37-53`; `platform-app/src/main/java/io/integrationplatform/app/scheduler/JobRunWrapper.java:32-34` |
| ADR-013 | Mapping versions and the publish pin check, including a passing sample run, are built | `platform-config/src/main/java/io/integrationplatform/config/mapping/application/MappingPublishPinCheck.java:28-30`, `:76-80` |
| ADR-014 | Message, payload and delivery store built; stage-1 uniqueness `(message_id, route_id, created_at)` in code, not wired | `platform-delivery/src/main/java/io/integrationplatform/delivery/internal/stage1/JdbcStage1Deliveries.java:11-13` |
| ADR-015 | Adapters sit in `platform-routing`; the `system.type` rename to `MQ_CSV` / `KAFKA_TOPIC` (E10) has **not landed** - `system.type` is still `API`, `DB`, `MQ`, `FILE` | `platform-config/src/main/resources/db/migration/V2_2_7__config_system_type_settings.sql:13`; `platform-config/src/main/resources/db/migration/V2_3_7__target_credential.sql:22` |

## Recreation additions waiting for the project owner's re-confirmation

Sections and items labelled **Added in the recreation** have no surviving hub source; each is a
new team decision until the project owner re-confirms it (ADR-001 as a whole is listed in the table above).

- ADR-002 - Decision, item 7 (the published snapshot is one `jsonb` column)
- ADR-002 - Revisit if (the point of measurement: end of the Release 1 load test)
- ADR-003 - Rules carried with the decision, item 4 (contract validation runs before the rules)
- ADR-004 - Rules carried with the decision, item 3 (each mapping bound to one source and one target contract version)
- ADR-004 - Consequences (the next version-currency check runs at the start of the mapping phase)
- ADR-009 - Update 2026-10-02 (rebuild, new - not part of the original record)

## Closed registers and where they live

A list that other documents index into changes only through the ADR that owns it, and that ADR
re-publishes every dependent list in the same change.

| Register | Owner |
|---|---|
| Delivery states and transitions; message states; outcome classes (default table, overridable per target by its error dictionary); the six controls on a target call | ADR-010 |
| Target types | ADR-011 (code aligned by ADR-015 in E10) |
| Kafka topics | ADR-005 |
| Definer functions that cross the tenant boundary | ADR-009, ADR-007, ADR-011 (listed together in [architecture.md](../architecture.md) section 5) |
| Roles and caller kinds | ADR-007 (caller kind `OPERATOR_AGENT` added by ADR-012) |
| Job types; platform-scoped tables outside row-level security (exemption register) | ADR-012 (listed in [architecture.md](../architecture.md) section 5); fifth job type added by ADR-016 |
| Camel starters in use; JMS client | ADR-010; ADR-018 |
