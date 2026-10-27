---
type: Design
title: "Interface - the common API and the admin API (configuration-driven integration platform)"
status: "Recreated 2026-10-12 (E5, T-107). Section 2 restates ADR-011 Decision 4 with 0 differences; the common API itself is built in E11. Sections 4-6 describe the admin API as built at row278-integration 4e9de1d. Re-checked 2026-10-16 at main 90c1ddd (ledger row 325): section 2 gains the intake endpoint as built, section 7 the endpoints added since; every other cited file is unchanged since 4e9de1d."
date: 2026-10-12
author: "mahesh-architect (ledger row 278, T-107)"
project: ipass/integration-platform
provenance: recreation
related:
  - fetch.md
  - architecture.md
  - PRD.md
  - adr/ADR-007-identity.md
  - adr/ADR-011-targets-contracts-bulk-ordering.md
---

# Interface - the common API and the admin API

> **Recreation.** The earlier api-contracts document was deleted with the old repository on
> 2026-10-02; its surviving quotes are the sources the plan names for this file
> (`ws/_specs/project-plan.md:161`: `ws/_specs/f3-plan-brief.md:58`, `:176`, `:532`). The common
> API is restated from ADR-011 Decision 4 and must never contradict it (E5-F3-S1 @negative,
> `ws/_specs/backlog.md:1579-1583`): if this file and ADR-011 ever differ, ADR-011 wins and this
> file is the defect. Data, credentials and outbound calls are in [fetch.md](fetch.md).

**Grades.** Every statement carries one: **[Verified]** - read on 2026-10-12 in the file cited;
**[Inferred]** - deduced from the neighbour named; **[TBD]** - not decided or not built yet, with
the phase or person that settles it. Source paths starting `ws/` are in the planning workspace kept
outside this repository (see [README.md](README.md)); all other paths are in this repository at
row278-integration `4e9de1d`. ADR-011 means
`documents/adr/ADR-011-targets-contracts-bulk-ordering.md`.

**Re-checked 2026-10-16 at main `90c1ddd`.** Of the code files cited in sections 4-7 only `FlowAdminController.java`
changed since `4e9de1d` (`git diff --stat 4e9de1d 90c1ddd -- <file>`); its lines are re-cited in section 7. The
common API now has its first endpoint - section 2, "As built".

## 1. Two APIs, one version rule

*Sources: `ws/_specs/project-plan.md:116` (ONE common API whose path names the interface),
`:180` (API v1 window 12 months), `:161` (provenance row); `ws/_specs/f3-plan-brief.md:532`
(admin API of the deleted design); `ws/_specs/backlog.md:3094-3111` (E11 version story, T-169).*

| Statement | Grade | Source |
|---|---|---|
| The **common API** is what source systems call to hand over data; the **admin API** is what the console calls to configure systems, credentials, contracts, flows and rules | [Verified] | `ws/_specs/project-plan.md:116`; `ws/_specs/f3-plan-brief.md:532` |
| Every endpoint is under `/api/v1`; the admin API is under `/api/v1/admin` | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/system/adapter/in/SystemAdminController.java:35`; ADR-011 `:141-142` |
| API v1 is supported for 12 months; T-169 records the window here formally and adds a test that unversioned paths are not served | [Verified] | `ws/_specs/project-plan.md:180`; `ws/_specs/backlog.md:3101`, `:3111` (E11); every controller path is under `/api/v1`: `platform-app/src/test/java/io/integrationplatform/apiversion/UnversionedPathsAreNotServedTest.java:46` (planted violation `:56`) |

## 2. The common API - ADR-011 Decision 4

*Sources: `ws/_specs/project-plan.md:116`, `:137-139` (bulk per-record results, customer from the
credential, immediate async reply), `:143` (payloads under 1 MB), `:180` (intake 2 s, else 503);
`ws/_reports/2026-10-01-t10-figure-reconciliation.md:52` (the deleted path had a tenant segment);
ADR-011 `:139-176` (Decision 4, restated here with 0 differences).*

**Status 2026-10-12:** designed, not built (no intake controller existed at `4e9de1d`).

**As built at main `90c1ddd` (2026-10-16)** - the single-record endpoint exists; the rest of Decision 4 below is
still the target. [Verified] unless marked.

| Statement | Grade | Source |
|---|---|---|
| `POST /api/v1/interfaces/{interfaceCode}/messages` exists; `:bulk` does not | [Verified] | `platform-ingest/src/main/java/io/integrationplatform/ingest/adapter/in/IntakeController.java:53`; `git grep -n ':bulk' main -- '*/src/main/*'` finds no mapping |
| The unversioned path is not mapped and answers 404 | [Verified] | `IntakeController.java:26-27` |
| Required headers: `X-Source-Contract-Version` (the interface pin check, ADR-013) and `Idempotency-Key` (presence only - it is not stored or compared yet) | [Verified] | `IntakeController.java:27-28`, `:57-58`, `:64-70` |
| Cap: one record over 1 MB (1,048,576 bytes) is refused with 413 before the transaction runs | [Verified] | `IntakeController.java:33-34`, `:40` |
| Answers: 202 with the tracking id; 400 missing header or malformed JSON; 415 missing or wrong Content-Type; 413 over the cap; 404 unknown or unpublished interface; 422 off-pin version or unresolved business key; 503 the transaction did not commit (no tracking id; the cause is logged at ERROR) | [Verified] | `IntakeController.java:30-33` |
| Checks run today: published interface, source-contract pin, business key; then one transaction writes message, payload and outbox row | [Verified] | `platform-ingest/src/main/java/io/integrationplatform/ingest/internal/intake/IntakeService.java:38-66` |
| Not yet: bulk and per-record results, JSON Schema and rules validation at intake, the idempotency store (24 h, body hash, `IDEMPOTENCY_KEY_REUSED`), the per-key sequence | [TBD] | E11 (`ws/_specs/backlog.md:2807`); the `PayloadValidationService` class exists but intake does not call it |
| After the commit nothing reads the outbox yet: the relay is not wired, so an accepted message is stored and not delivered | [Verified] | [architecture.md](architecture.md) section 9 |

| Statement | Grade | Source |
|---|---|---|
| **Endpoints:** `POST /api/v1/interfaces/{interfaceCode}/messages` (one record, a JSON object) and `POST /api/v1/interfaces/{interfaceCode}/messages:bulk` (`{"records": [...]}`) | [Verified] | ADR-011 `:141-142` |
| The **path names the interface; the tenant and customer come from the credential** (ADR-007), so the path carries no tenant segment | [Verified] | ADR-011 `:143-145`; `ws/_specs/project-plan.md:138` |
| **Intake steps, in order:** authenticate -> resolve the interface's published version (pinned for this call) -> size checks -> per record: JSON Schema validation (all errors), then rules validation (all violations; ADR-003), then business-key extraction (the interface's mandatory business-key rule) and idempotency check -> **one transaction** writing, for every valid record, its message row, payload row and outbox row -> respond | [Verified] | ADR-011 `:146-150` |
| **Bulk is split at intake, before Kafka:** one record = one message = one Kafka record; Camel never splits bulk | [Verified] | ADR-011 `:151-152` |
| **Cap:** a record over **1 MB** is rejected with a clear error | [Verified] | ADR-011 `:154` |
| **Cap:** a bulk call over **1,000 records** or over **10 MB** is refused as a whole with `413` and a clear error; nothing is persisted | [Verified] | ADR-011 `:155-156` |
| The caps are team defaults, configurable per interface **downward**; Dipesh may change them | [Verified] | ADR-011 `:153` |
| **Idempotency:** an `Idempotency-Key` header is **required on every call**, single and bulk; a call without one is refused with `400`; each record's key is the header plus its index | [Verified] | ADR-011 `:167-169` |
| The key is **scoped per source credential** - the same key from two credentials is two requests | [Verified] | ADR-011 `:169-170` |
| The platform stores the key with a hash of the request body; a repeat with the same body returns the original per-record results; **the same key with a different body is refused with `422`** (`IDEMPOTENCY_KEY_REUSED`) | [Verified] | ADR-011 `:170-172` |
| Keys are kept **24 hours**, then purged; the same business key with a new idempotency key is a new message (an update) | [Verified] | ADR-011 `:172-173` |
| **Gate:** a bulk of N records with k invalid returns N results and writes exactly N - k outbox rows | [Verified] | ADR-011 `:175-176` |

**Responses** - ADR-011 `:157-165`, [Verified]:

| Case | Status | Body |
|---|---|---|
| At least one record accepted | `202` | One result per record: index, `ACCEPTED` with tracking id, `REJECTED` with every reason (path, code, message), or `DUPLICATE` with the original tracking id |
| No record accepted | `422` | The same per-record results |
| Over a cap | `413` | Which cap, and the limit |
| Intake budget (2 s) exceeded or the database unavailable | `503` | Nothing persisted; safe to repeat with the same idempotency key |
| Credential, role or interface access refused | `401` / `403` | Never retried by the platform |

## 3. Synchronous single-target interfaces - ADR-011 Decision 5

*Sources: `ws/_specs/project-plan.md:139`, `:150` (synchronous only for single-target flows;
pending on timeout); ADR-011 `:178-200`. Applies only to an interface set to `SYNC`; it adds to
section 2 and changes none of its codes or caps.*

| Statement | Grade | Source |
|---|---|---|
| An interface may be `SYNC` only when exactly one route can match | [Verified] | ADR-011 `:182-183` |
| Success -> `200` with the target's response mapped to the source's registered response contract; `PERMANENT` failure -> an error mapped through the target's error dictionary; anything else, a full bulkhead, a held delivery or the 10 s budget reached -> `202` with the tracking id and status `PENDING` | [Verified] | ADR-011 `:192-199` |
| Dipesh may reverse the `PENDING` rule (for example to a plain error on timeout) | [TBD] | ADR-011 `:199-200`; Dipesh |

## 4. Admin API - systems and test-connection (E5, as built)

*Sources: `ws/_specs/f3-plan-brief.md:532` (systems CRUD in the deleted admin API),
`ws/_specs/project-plan.md:110`; `ws/_specs/backlog.md:1342-1420` (E5-F1-S1, E5-F1-S2).*

Every admin endpoint answers `403` for `NO_BOUND_USER` or a role that does not allow the
operation, and `404` for a row not visible to the bound tenant (row-level security, never a
cross-tenant hint). [Verified] - the `@ApiResponse` lists of the controllers cited below.

| Method and path | Answers | Grade | Source |
|---|---|---|---|
| `POST /api/v1/admin/systems` | `201`; `400` every validation error listed; `409` code already registered | [Verified] | `SystemAdminController.java:45-57` |
| `GET /api/v1/admin/systems` | `200` every system of the bound tenant, ordered by code | [Verified] | `SystemAdminController.java:59-67` |
| `GET /api/v1/admin/systems/{systemId}` | `200`; `404` | [Verified] | `SystemAdminController.java:69-79` |
| `PUT /api/v1/admin/systems/{systemId}` | `200` the edited system; `400`; `404` | [Verified] | `SystemAdminController.java:81-93` |
| `POST /api/v1/admin/systems/{systemId}/test-connection` | `200` with result `SUCCESS` (httpStatus, responseTimeMs), `REFUSED` (reason, before any data is sent), `FAILED` (reason), `NOT_AVAILABLE_YET` or `NOT_CONFIGURED`; `404`; `429 TEST_CONNECTION_RATE_LIMITED` | [Verified] | `SystemAdminController.java:95-108` |
| A system answers as `{"id","code","name","direction","type","settings"}`; `type` and `settings` are null for an untyped system | [Verified] | `SystemAdminController.java:28-29` |

## 5. Admin API - credentials (E5, as built)

*Sources: `ws/_specs/project-plan.md:110` (tenant admin only, write-only after creation, never
logged), `:138`; `ws/_specs/backlog.md:1422-1512` (E5-F1-S3, E5-F1-S4).*

| Method and path | Answers | Grade | Source |
|---|---|---|---|
| `POST /api/v1/admin/systems/{systemId}/source-credentials` | `201` issued, the secret shown once; `400` `customerId` missing; `404`; `409` not a source system; `503` the identity provider did not create the client | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/credential/adapter/in/SourceCredentialAdminController.java:32`, `:43-57` |
| `GET /api/v1/admin/systems/{systemId}/source-credentials` | `200` oldest first, metadata only; `404`; `409`; `503` | [Verified] | `SourceCredentialAdminController.java:63-74` |
| `POST /api/v1/admin/source-credentials/{credentialId}/rotate` | `201` the replacement, its secret shown once, the replaced one valid until `replacedRevokeAfter`; `404`; `409` not `ACTIVE` or already rotating; `503` | [Verified] | `SourceCredentialAdminController.java:80-92` |
| `POST /api/v1/admin/source-credentials/{credentialId}/revoke` | `200` revoked on the platform at once, `idpDisablePending` says whether the identity provider still has to disable it; `404`; `409` already revoked; `503` | [Verified] | `SourceCredentialAdminController.java:97-108` |
| `GET /api/v1/admin/target-endpoints/{endpointId}/credentials` | `200` the fields that are set, each masked; `404` | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/credential/adapter/in/TargetCredentialAdminController.java:30`, `:41-47` |
| `PUT /api/v1/admin/target-endpoints/{endpointId}/credentials` | `200` saved, each field sealed in its own row, the masked view; `400`; `404`; `409` the target system has no type yet; `503` no active key-encryption key | [Verified] | `TargetCredentialAdminController.java:53-65` |

## 6. Admin API - versioned contracts (E5, as built)

*Sources: `ws/_specs/f3-plan-brief.md:58` (approve by the author -> `403`, deleted api-contracts
quote), `:176`; `ws/_specs/project-plan.md:110`, `:141`; `ws/_specs/backlog.md:1516-1562`
(E5-F2-S1).*

Base path `/api/v1/admin/contracts` [Verified] -
`platform-config/src/main/java/io/integrationplatform/config/contract/adapter/in/ContractVersionAdminController.java:42`, `:45`.

| Method and path | Answers | Grade | Source |
|---|---|---|---|
| `POST /api/v1/admin/contracts/versions` | `200`; `400` names the refused field or value; `409` a concurrent save took the label | [Verified] | `ContractVersionAdminController.java:58-66` |
| `POST /api/v1/admin/contracts/versions/import` | `201` a new `DRAFT`, its schema imported from OpenAPI or JSON Schema; `400` with the parser's errors; `403 ROLE_NOT_GRANTED` (needs TENANT_ADMIN or CONFIGURATOR); `409` label exists | [Verified] | `ContractVersionAdminController.java:105-114` |
| `GET /api/v1/admin/contracts` | `200` every contract with its versions side by side | [Verified] | `ContractVersionAdminController.java:127-132` |
| `GET /api/v1/admin/contracts/{contractId}/versions` | `200` one contract with its versions side by side; `404` | [Verified] | `ContractVersionAdminController.java:140-147` |
| `GET /api/v1/admin/contracts/versions/{versionId}` | `200` any status - a `RETIRED` version stays readable; `404` | [Verified] | `ContractVersionAdminController.java:73-80` |
| `POST /api/v1/admin/contracts/versions/{versionId}/submit` | `200`; `400`; `404`; `409` only a `DRAFT` submits | [Verified] | `ContractVersionAdminController.java:86-96` |
| `POST /api/v1/admin/contracts/versions/{versionId}/approve` | `200` now `APPROVED` by the caller; the author may approve their own version (single-user workflow, `V2_4_1420`); `403` only for a missing role; `404`; `409 NOT_PENDING_APPROVAL` | [Verified] | `ContractVersionAdminController.java:153-162` |

## 7. Other admin endpoints already in the console

*Sources: `ws/_specs/f3-plan-brief.md:532` (interfaces, approvals in the deleted admin API);
`ws/_specs/backlog.md:936` (E3 flow configuration), `:1608` (E6 rules). Listed so the admin API
has one index; their contracts belong to E3 and E6.*

| Path | Grade | Source |
|---|---|---|
| `/api/v1/admin/flows` - create, list, read, the current version's routes (`GET /{flowId}/routes`), edit, version edit, submit, approve, publish. There is no reject endpoint yet [TBD] | [Verified] at `90c1ddd` | `platform-config/src/main/java/io/integrationplatform/config/flow/adapter/in/FlowAdminController.java:33`, `:56-172` (routes `:95`) |
| `GET` and `PUT /api/v1/admin/routes/{routeId}/mapping` - read and save a route's mapping draft; on save `fieldMappings` is an array or absent - `null` or any other type is a 400; ids that are not UUIDs are 404 (route) or 400 (contract version) | [Verified] at `90c1ddd` | `platform-config/src/main/java/io/integrationplatform/config/mapping/adapter/in/MappingAdminController.java:27`, `:46`, `:67` |
| `POST /api/v1/admin/mappings/{id}/test` - the recorded sample run publish requires | [Verified] at `90c1ddd` | `platform-config/src/main/java/io/integrationplatform/config/mapping/adapter/in/MappingTestController.java:27`, `:51` |
| `GET /api/v1/admin/lookups/{lookupId}/entries` and `PUT .../entries/{entryId}` | [Verified] at `90c1ddd` | `platform-config/src/main/java/io/integrationplatform/config/rule/adapter/in/LookupAdminController.java:31-32`, `:49`, `:66` |
| `GET /api/v1/admin/queue/summary` - the queue dashboard's counts per flow and day | [Verified] at `90c1ddd` | `platform-app/src/main/java/io/integrationplatform/app/queue/QueueSummaryController.java:35`, `:51` |
| `/api/v1/admin/flows/{flowId}/rule-sets`, `/api/v1/admin/rule-sets/{ruleSetId}` and its `/evaluate` | [Verified] | `platform-config/src/main/java/io/integrationplatform/config/rule/adapter/in/RuleSetAdminController.java:35-36`; `RuleEvaluationController.java:46` |
| `GET /api/v1/me` - the signed-in user and tenant | [Verified] | `platform-tenancy/src/main/java/io/integrationplatform/tenancy/internal/web/MeController.java:18`, `:27` |
