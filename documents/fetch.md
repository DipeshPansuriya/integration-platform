---
type: Design
title: "Fetch - data and integration (configuration-driven integration platform)"
status: "Recreated 2026-10-12 (E5, T-107) from the surviving hub sources and the E5 build at row278-integration 4e9de1d. Design follows ADR-011; where the build differs from ADR-011 the difference is listed in section 8, not hidden. Re-checked 2026-10-16 at main 90c1ddd (ledger row 325): every code file cited here is unchanged since 4e9de1d; section 8 gains a status column."
date: 2026-10-12
author: "mahesh-architect (ledger row 278, T-107)"
project: ipass/integration-platform
provenance: recreation
related:
  - interface.md
  - architecture.md
  - PRD.md
  - adr/ADR-007-identity.md
  - adr/ADR-010-camel-routing-core.md
  - adr/ADR-011-targets-contracts-bulk-ordering.md
---

# Fetch - data and integration

> **Recreation.** The earlier data-design and integration documents were deleted with the old
> repository on 2026-10-02. The plan names the surviving sources for this file
> (`ws/_specs/project-plan.md:161`: `ws/_specs/f3-plan-brief.md:58`, `:176`, `:532` and
> `ws/_specs/f3-flow-configuration/spec.md`) and the phase that recreates it - the systems and
> contract registry phase, E5 (`ws/_specs/project-plan.md:110`; `ws/_specs/backlog.md:1566-1587`).
> This file holds what the platform stores about the systems it connects, how it reaches them, and
> what the E5 build really contains. The HTTP contracts are in [interface.md](interface.md).

**Grades.** Every statement carries one: **[Verified]** - read on 2026-10-12 in the file cited;
**[Inferred]** - deduced from the neighbour named; **[TBD]** - not decided or not built yet, with
the phase or person that settles it. Source paths starting `ws/` are in the planning workspace kept
outside this repository (see [README.md](README.md)); all other paths are in this repository at
row278-integration `4e9de1d`. Rule R19 applies: no product or organisation name.

**Re-checked 2026-10-16 at main `90c1ddd`.** Every code file cited in this document is unchanged between `4e9de1d`
and `90c1ddd` (`git diff --stat 4e9de1d 90c1ddd -- <file>` is empty for each), so every `path:line` here still
holds. What has moved on is the runtime around them: section 4's send-time decryption (`CredentialedDelivery`) and
section 7's Kafka records are built but **not wired** - `platform-app` does not call them yet
([architecture.md](architecture.md) section 9).

## 1. What this document covers

*Sources: `ws/_specs/project-plan.md:161` (provenance row), `:110` (E5 done-when);
`ws/_specs/backlog.md:1566-1587` (E5-F3-S1, T-107), `:540` (E0-C4).*

| Statement | Grade | Source |
|---|---|---|
| `fetch.md` and `interface.md` are recreated in the systems and contract registry phase (E5), the phase that first cites them | [Verified] | `ws/_specs/project-plan.md:102`, `:110`, `:161`; `ws/_specs/backlog.md:540` |
| This file covers data and integration: the systems registry, credentials, test-connection with the outbound destination check, versioned contracts, target types, what crosses Kafka and how background work finds its tenant | [Verified] | `ws/_specs/backlog.md:1568-1569` |
| Every section cites its surviving hub source as file:line or is labelled a new decision; none of the sections here needed that label | [Verified] | `ws/_specs/backlog.md:1574-1577`; this file |

## 2. The systems registry - one systems table, typed settings

*Sources: `ws/_specs/project-plan.md:110` (one systems table, API/DB/MQ/File);
`ws/_specs/f3-plan-brief.md:532` (systems in the admin API of the deleted design);
`ws/_specs/backlog.md:1342-1377` (E5-F1-S1); ADR-011 Decision 1 and 2.*

| Statement | Grade | Source |
|---|---|---|
| There is one systems table, `system`, created by the flow-configuration phase; E5 **adds** `type` and `settings` to it - no second systems table | [Verified] | `platform-config/src/main/resources/db/migration/V2_2_7__config_system_type_settings.sql:12-16`; ADR-011 `:83-85` |
| `type` is a closed set `API`, `DB`, `MQ`, `FILE`; `settings` is a JSON object; type and settings are both set or both null (an E3 untyped system has neither) | [Verified] | `V2_2_7__config_system_type_settings.sql:13-16` |
| Type settings, checked on every save: `API` - `baseUrl` (http/https, required), `timeoutMs`, `followRedirects`; `DB` - `host`, `port`, `database`; `MQ` - `host`, `port`, `queue`; `FILE` - `rootPath` | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/system/application/SystemSettings.java:33`, `:49-55`, `:135` |
| Every system row is tenant-scoped under row-level security (`system_tenant_isolation`, `target_endpoint_tenant_isolation`) | [Verified] | `platform-config/src/main/resources/db/migration/V2_2_1__config_systems.sql:52`, `:56` |
| Adding a system of an existing type is rows only; adding a new type is a code change (route template, settings schema, ADR update) | [Verified] | ADR-011 `:62-65`; `ws/_specs/project-plan.md:110` |

## 3. Contracts and the version graph - one home for every schema

*Sources: `ws/_specs/project-plan.md:110`, `:141` (versioned per-target contracts, approval,
version recorded per message); `ws/_specs/f3-plan-brief.md:58` (approver is never the author,
deleted api-contracts quote); `ws/_specs/backlog.md:1516-1562` (E5-F2-S1); ADR-011 Decision 2-3.*

| Statement | Grade | Source |
|---|---|---|
| Every schema lives only in `contract_version`; `contract` carries tenant, system, code, role, name | [Verified] | `platform-config/src/main/resources/db/migration/V2_2_2__config_contracts.sql:10-21`; ADR-011 `:86-89` |
| Roles: `SOURCE_REQUEST` (E3), widened in E5 to `SOURCE_REQUEST`, `SOURCE_RESPONSE`, `TARGET_REQUEST`, `TARGET_RESPONSE` | [Verified] | `V2_2_2__config_contracts.sql:15`; `V2_2_5_1__contract_target_roles_and_approval.sql:21-23` |
| `contract_version`: label `major.minor`, status `DRAFT`, `PENDING_APPROVAL`, `APPROVED`, `RETIRED`, JSON Schema draft 2020-12 (JSONB), optional original OpenAPI document, `derived_from_version_id`, `compatibility` (`COMPATIBLE`/`BREAKING`), author, approver | [Verified] | `V2_2_2__config_contracts.sql:23-44` |
| The approver may be the author (single-user workflow): `V2_4_1420` drops the `approver_not_author` CHECK and the definer function `config_approve_contract_version` approves any `PENDING_APPROVAL` version of the bound tenant | [Verified] | `V2_4_1420__allow_self_approval.sql` |
| OpenAPI documents are imported with `swagger-parser` and validated with the networknt validator; JSON Schema is canonical | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/contract/application/ContractDocumentImporter.java:3-12`; ADR-011 `:103-105` |
| Target A v1.0 and Target B v2.0 sit side by side as rows; a target contract version added on a running system needs 0 deploys (container test) | [Verified] | `platform-app/src/test/java/io/integrationplatform/e5/contract/E5ZeroDeployContractVersionContainerTest.java`; `ws/_specs/project-plan.md:110` |
| Routing asks the registry for a usable target contract version per system | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/contract/application/ContractVersionResolver.java:13`, `:22` |
| The contract version a message used is recorded: `outbox.contract_version_id` (written by the outbox writer) and `delivery.contract_version_id` (NULL until the routing phase E9 writes it) | [Verified] | `platform-ingest/src/main/resources/db/migration/V2_1_2__outbox_contract_version.sql:13-16`; `platform-ingest/src/main/java/io/integrationplatform/ingest/internal/outbox/JdbcOutboxWriter.java:38-67`; `platform-delivery/src/main/resources/db/migration/V2_3_3_1__delivery_contract_version.sql:15-18` |
| Version-graph rules not yet enforced in code - lineage compatibility at submit, mapping binding, publish-time pin check, retire only at 0 non-final pins - are owned by later phases | [TBD] | ADR-011 `:107-137`; E7 mapping designer (`ws/_specs/backlog.md:1757`) and E9 routing core (`ws/_specs/backlog.md:2208`) |

## 4. Credentials - source credentials and write-only target secrets

*Sources: `ws/_specs/project-plan.md:110` (tenant admin only, write-only, never logged, target
credentials encrypted with the out-of-database key of ADR-007), `:138` (customer from the
credential, never from the body); `ws/_specs/backlog.md:1422-1512` (E5-F1-S3, E5-F1-S4).*

| Statement | Grade | Source |
|---|---|---|
| A source credential is a confidential client in the identity provider, issued, rotated and revoked through the identity-provider admin adapter | [Verified] | `platform-tenancy/src/main/java/io/integrationplatform/tenancy/internal/web/KeycloakAdminAdapter.java:45`; `platform-config/src/main/java/io/integrationplatform/config/credential/application/SourceCredentialService.java:69-78` |
| `source_credential` holds tenant, source system, `customer_id`, issuer, client id, status `PENDING`/`ACTIVE`/`REVOKED`, `idp_disable_pending`, `revoke_after`; (issuer, client id) is unique; row-level security on the table | [Verified] | `platform-tenancy/src/main/resources/db/migration/V2_3_4__source_credential.sql:13-32`, `:54`; `V2_3_6__source_credential_rotation.sql:16-18` |
| The tenant and customer of a call come from the credential: the definer function `ipaas_client_bootstrap(issuer, client_id)` returns them only for an `ACTIVE` credential inside its grace window and an `ACTIVE` tenant | [Verified] | `V2_3_6__source_credential_rotation.sql:22-46` |
| Rotation keeps the replaced credential working for a grace window, default 7 days (`platform.tenancy.source-credential.rotation-grace`); after it the bootstrap refuses | [Verified] | `platform-tenancy/src/main/java/io/integrationplatform/tenancy/internal/credential/SourceCredentialLifecycleConfiguration.java:29`, `:39`; `V2_3_6__source_credential_rotation.sql:44` |
| A source secret is shown once, in the issue or rotate answer; every read shows metadata only | [Verified] | `SourceCredentialService.java:27`; `platform-config/src/main/java/io/integrationplatform/config/credential/adapter/in/SourceCredentialAdminController.java:64-65` |
| Target secrets are write-only and envelope-sealed per field: a fresh 256-bit data key encrypts the value (AES-256-GCM), the key-encryption key wraps the data key (AES-256-GCM) | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/credential/crypto/EnvelopeSealer.java:19-20`, `:80` |
| Key-encryption keys come only from the environment, `PLATFORM_CREDENTIAL_KEK_<ID>`, the sealing key named by `PLATFORM_CREDENTIAL_KEK_ACTIVE`; no key is stored in the database | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/credential/crypto/EnvironmentKeyEncryptionKeys.java:33`, `:36`, `:49-59` |
| `target_credential` holds one row per (endpoint, field), the field drawn from a closed per-type register, with `kek_id`, `wrapped_key` (60 bytes), `ciphertext`; row-level security; the application role cannot delete | [Verified] | `platform-config/src/main/resources/db/migration/V2_3_7__target_credential.sql:17-64` |
| Decryption happens only at send, in routing: an unknown key id is TRANSIENT (no call, `RETRY_WAIT`, `KEY_UNAVAILABLE` event, readiness out of service); a tag mismatch is PERMANENT (no call, `DEAD_LETTERED`); only ids are logged | [Verified] | `platform-routing/src/main/java/io/integrationplatform/routing/credential/CredentialedDelivery.java:22-35`; `platform-delivery/src/main/java/io/integrationplatform/delivery/DeliveryEventType.java:40` |

## 5. Test-connection and the outbound destination check

*Sources: `ws/_specs/project-plan.md:110` (API connections only in E5; allow-listed
destinations; private and loopback ranges refused after DNS and after every redirect; audited and
rate-limited); `ws/_specs/backlog.md:1379-1420` (E5-F1-S2).*

| Statement | Grade | Source |
|---|---|---|
| E5 tests `API` systems only; any other type answers `NOT_AVAILABLE_YET` until its adapter phase adds its test; an untyped system answers `NOT_CONFIGURED` | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/system/application/TestConnectionService.java:75-82` |
| Order before anything is dialled: http/https only, no user information; an IP literal in a refused range is refused even if allow-listed; a name must be on the allow-list before any DNS query; the name is resolved once, every address checked, the first pinned; every redirect hop runs the check again | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/destination/DestinationGuard.java:14-26`, `:63-68` |
| The allow-list is operator configuration, `platform.config.destinations.allowed-hosts` (exact names or `*.domain`); empty by default, so nothing is reachable until an operator lists it | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/internal/persistence/SystemPersistenceConfiguration.java:40`, `:60-63`; `platform-config/src/main/java/io/integrationplatform/config/destination/AllowList.java:8` |
| Rate limit per tenant and user, default 10 per 1 minute (`platform.config.test-connection.rate-limit.limit` / `.window`); over it -> `429 TEST_CONNECTION_RATE_LIMITED`, refused at once | [Verified] | `SystemPersistenceConfiguration.java:41-42`, `:78-79`; `TestConnectionService.java:54-71` |
| Every attempt and every refusal is audited as one structured event `system.test_connection` | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/system/application/TestConnectionAudit.java:9`, `:26-34`; `TestConnectionService.java:70-99` |
| The five controls on the outbound call: connect timeout 5 s, read timeout 10 s, 3 attempts with backoff from 200 ms (x2.0, jitter 0.5, cap 2 s), circuit breaker after 5 failures for 30 s, at most 5 redirects, metrics and observations | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/destination/OutboundHttpCheck.java:89-107`, `:214`; `SystemPersistenceConfiguration.java:66-71` |

## 6. Target types - the closed register

*Sources: `ws/_specs/project-plan.md:110` (API, DB, MQ, File), `:140` (DB only to approved
tables, restricted account), `:147` (queue manager pluggable through JMS), `:151` (Kafka topic
kept by Mira); ADR-011 Decision 1.*

| Type (ADR-011) | Release | Sends with | Idempotent receive | Grade | Source |
|---|---|---|---|---|---|
| `API` | 1 | `camel-http` | `Idempotency-Key: <delivery id>` header | [Verified] | ADR-011 `:56` |
| `MQ_CSV` | 1 | `camel-csv` / `camel-bindy`, then `camel-jms` | JMS correlation id = delivery id | [Verified] | ADR-011 `:57` |
| `DB` | 2 | `camel-sql`, approved tables only, restricted account, parameterised | insert-or-update on a declared natural key | [Verified] | ADR-011 `:58` |
| `FILE` | 2 | `camel-file`, temporary name then atomic rename | deterministic name from the delivery id | [Verified] | ADR-011 `:59` |
| `KAFKA_TOPIC` | 2 | `camel-kafka` | idempotent producer, key = delivery id | [Verified] | ADR-011 `:60` |

| Statement | Grade | Source |
|---|---|---|
| Every type's host passes the one outbound destination check at save, at test-connection and at send; where a driver cannot pin the checked address, an egress policy enforces the same refused ranges | [Verified] | ADR-011 `:68-75` |
| A target that does not declare idempotent receive gets no automatic retry of an ambiguous outcome | [Verified] | ADR-011 `:66-67` |
| The queue-manager product is unknown; it plugs in through its JMS connection factory, chosen from an allow-list of factory classes | [TBD] | ADR-011 `:57`; Dipesh names the product (`ws/_specs/project-plan.md:147`) |
| SFTP for File targets is out of scope unless Dipesh names it | [TBD] | ADR-011 `:59` |

## 7. What crosses Kafka, and how background work finds its tenant

*Sources: `ws/_specs/project-plan.md:113` (record key), `:102` (ids only, tenant from the
RLS-protected row); ADR-011 Decision 7-8. Built in the backbone phase E8 (`ws/_specs/backlog.md:2011`), not in E5.*

*Status 2026-10-16 (main `90c1ddd`):* the relay and the consumers are built but **not wired**, and the Kafka publish
port is created only when `IPASS_KAFKA_BOOTSTRAP_SERVERS` is set
(`platform-routing/src/main/java/io/integrationplatform/routing/publish/KafkaPublishPortConfiguration.java:29`), so
nothing crosses Kafka in the running application yet. `ipaas_work_tenant` exists
(`platform-routing/src/main/resources/db/migration/V2_3_811__ipaas_work_tenant.sql:13`); `ipaas_active_tenants` has
no migration yet [TBD].

| Statement | Grade | Source |
|---|---|---|
| A Kafka record carries ids only: value `{"v": 1, "type": "message.accepted" \| "delivery.due", "id": "<uuid>"}`, header `traceparent`, key SHA-256 of (tenant, interface, business key); no tenant id in value or headers; the payload stays in PostgreSQL | [Verified] | ADR-011 `:269-279` |
| Consumers take the tenant from the row through the definer function `ipaas_work_tenant(kind, id)`; scanning jobs loop over `ipaas_active_tenants()`; no application role bypasses row-level security | [Verified] | ADR-011 `:283-290` |
| A daily payload partition is dropped only when 0 dead-lettered or non-final deliveries reference it | [Verified] | ADR-011 `:293-297` |

## 8. Differences between the E5 build and ADR-011 found during the recreation

*Sources: `ws/_specs/project-plan.md:110`, `:116`; `ws/_specs/backlog.md:2807` (E11-F1, the
one common API). These are findings for the owning phases, not changes made here.*

*Status 2026-10-16 (main `90c1ddd`), measured by searching every `*.sql` migration:* all four findings below are
**still open**. `system.type` is still `API`/`DB`/`MQ`/`FILE` while `target_credential.target_type` uses `MQ_CSV` and
`KAFKA_TOPIC` (the rename ADR-015 assigns to E10 has not landed); the `idempotency_key` table is unchanged and intake
only checks that the header is present
(`platform-ingest/src/main/java/io/integrationplatform/ingest/adapter/in/IntakeController.java:68-69`); stage 1, which
would write `delivery.contract_version_id`, is not wired.

| Finding | Grade | Source |
|---|---|---|
| `system.type` uses `MQ` and has no `KAFKA_TOPIC`; ADR-011 Decision 1 and `target_credential.target_type` use `MQ_CSV` and `KAFKA_TOPIC`. The two registers need one name before the MQ adapter phase E10 (`ws/_specs/backlog.md:2500`) | [Verified] | `V2_2_7__config_system_type_settings.sql:13`; `V2_3_7__target_credential.sql:22`; ADR-011 `:57`, `:60` |
| Type settings are a Java field list, not a JSON Schema shipped in code, and carry no `max_concurrent_sends` or error dictionary yet (ADR-011 Decision 1). Expected to arrive with each adapter phase | [Verified] / [Inferred] | `SystemSettings.java:49-55`; ADR-011 `:62-63`, `:76-77` |
| The existing `idempotency_key` table is unique per tenant and stores no body hash; ADR-011 Decision 4 scopes the key per source credential, stores a body hash and purges after 24 hours. The ingest phase (E11) must meet Decision 4 | [Verified] | `platform-ingest/src/main/resources/db/migration/V2_1_1__idempotency_key.sql:10-16`; ADR-011 `:167-174` |
| `delivery.contract_version_id` is NULL until routing (E9) writes it, so "every delivery records the target contract version" is not yet true end to end | [Verified] | `V2_3_3_1__delivery_contract_version.sql:17-18`; ADR-011 `:124-126` |
