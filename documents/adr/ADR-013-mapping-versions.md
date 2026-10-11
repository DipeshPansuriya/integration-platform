---
type: ADR
title: "ADR-013 - Mapping versions: an immutable, content-addressed mapping_version row created at draft save, referenced by id from the interface-version snapshot and pinned on every delivery"
status: "Proposed (mahesh-architect, 2026-10-11) - Dipesh approves. Blocks E7 lane C1 (T-190) and E9 T-117"
date: 2026-10-11
author: "mahesh-architect (ledger row 307 under row 278, pre-plan open item M1)"
project: ipass/integration-platform
provenance: new-decision
related:
  - ADR-004-mapping-canvas.md
  - ADR-006-transformation-engine.md
  - ADR-011-targets-contracts-bulk-ordering.md
  - ADR-014-message-store-and-delivery-uniqueness.md
  - ../architecture.md
---

# ADR-013 - Mapping versions

Paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`; paths without a prefix are in this repository
(read at `row278-integration` `8a3d7b5`; its SQL equals this branch's). Grades: **[Verified]** read
in the cited file this pass, **[Inferred]** reasoned from cited sources, **[TBD]** needs a
measurement or a fact only Dipesh holds.

## Context

- ADR-011 Decision 3 makes **mapping versions** a node of the version graph: each binds exactly
  one source and one target contract version, an interface version pins one per route, every
  delivery records the one it was created with, replay uses that pin, and a retired version stays
  readable (`documents/adr/ADR-011-targets-contracts-bulk-ordering.md:109-137`). [Verified]
- The schema has no mapping version. `mapping` is one mutable row per route
  (`mapping_route_unique`), content columns updatable by the application role
  (`platform-config/src/main/resources/db/migration/V2_2_3__config_flows.sql:54-67`, `:94-96`);
  it has no `(tenant_id, id)` unique key to be a composite-FK parent (`:63-66`). [Verified]
- The live `mapping` row is a **working copy, not the published state**: a draft save inserts new
  routes and their default mapping into the live rows before anything is approved
  (`platform-config/src/main/java/io/integrationplatform/config/flow/application/FlowAdminService.java:89`,
  `:274-289`), and publish later overwrites the live rows from the approved snapshot (`:193-223`).
  [Verified] So the data plane can never read `mapping` to learn what was published. [Inferred]
- Every draft save inserts a new `interface_version` row whose `snapshot` is read back from the
  live rows (`FlowAdminService.java:292-300`); the snapshot's mapping entries carry
  `id, routeId, fieldMappings, compiledExpression`
  (`platform-config/src/main/java/io/integrationplatform/config/flow/application/FlowBundle.java:18-19`,
  `:37`). The snapshot is immutable once inserted - a guard trigger refuses any content change
  (`platform-config/src/main/resources/db/migration/V2_2_4__config_interface_versions.sql:71-83`).
  [Verified]
- Precedent for versioned content: `contract_version` is its own immutable table that the
  interface version references **by id, never copying the schema**
  (`platform-config/src/main/resources/db/migration/V2_2_2__config_contracts.sql:3-7`, `:23-44`),
  with its own approval step (`V2_2_5_1__contract_target_roles_and_approval.sql:25-45`).
  Interface versions carry the lifecycle and the author-never-approver rule
  (`V2_2_4__config_interface_versions.sql:17-42`, `:124-151`). [Verified]
- The delivery already pins the target contract version (`contract_version_id`,
  `platform-delivery/src/main/resources/db/migration/V2_3_3_1__delivery_contract_version.sql:15`)
  but has no mapping-version column. [Verified]

## Options

| Option | What it is | Complexity | Risk | Cost | Reversibility |
|---|---|---|---|---|---|
| **A. Snapshot position** | No table; a "mapping version" is `(interface_version_id, route_id)` and its content lives only in the snapshot JSON | Low - no migration | Bindings live in JSON with no composite FK, so a forged or foreign contract id is caught only by code; every interface save makes a new "mapping version" even when the mapping did not change; the sample-run gate has nothing stable to key on | None now | High |
| **B. Immutable `mapping_version` table, created at draft save, content-addressed** (chosen) | One row per distinct mapping content; the snapshot entry references it by id; deliveries pin the id | Medium - one table, one hash step in the save | Low: immutability by grants, tenant safety by composite FKs, the same shape as `contract_version` | One small insert per changed mapping per save | Medium - additive migration; dropping it means re-pinning |
| **B'. As B, created at submit** (the pre-plan's wording) | Freeze the draft at submit | - | **Cannot work as stated:** the snapshot is written at save and is immutable (`V2_2_4:76-77`), so a row created later cannot be referenced from it | - | - |
| **C. Version column on `mapping`** | `mapping (route_id, version)` | Medium | Breaks `mapping_route_unique` (`V2_2_3:64`) and the byte-for-byte publish materialisation (`FlowAdminService.java:209-216`); mixes the editable copy with frozen versions | Rewrite of E3 publish | Low |

## Decision (Proposed)

1. **Table** (config module, E7 lane C1, range `V2_2_730-739`):

   ```sql
   ALTER TABLE public.mapping
       ADD COLUMN source_contract_version_id uuid,   -- draft binding, nullable for the '$' default
       ADD COLUMN target_contract_version_id uuid,
       ADD CONSTRAINT mapping_tenant_id_key UNIQUE (tenant_id, id);
   -- plus composite FKs (tenant_id, *_contract_version_id) -> contract_version (tenant_id, id)
   -- and GRANT UPDATE on the two new columns.

   CREATE TABLE public.mapping_version (
       id                         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
       tenant_id                  uuid    NOT NULL REFERENCES public.tenant (id),
       mapping_id                 uuid    NOT NULL,
       version                    integer NOT NULL CHECK (version > 0),
       source_contract_version_id uuid    NOT NULL,
       target_contract_version_id uuid    NOT NULL,
       engine                     text    NOT NULL,   -- closed set from ADR-006 [TBD until the spike]
       field_mappings             jsonb   NOT NULL,
       compiled_expression        text    NOT NULL,
       content_sha256             bytea   NOT NULL CHECK (length(content_sha256) = 32),
       author_id                  uuid    NOT NULL,
       created_at                 timestamptz NOT NULL DEFAULT now(),
       CONSTRAINT mapping_version_mapping_composite FOREIGN KEY (tenant_id, mapping_id)
           REFERENCES public.mapping (tenant_id, id),
       CONSTRAINT mapping_version_source_composite FOREIGN KEY (tenant_id, source_contract_version_id)
           REFERENCES public.contract_version (tenant_id, id),
       CONSTRAINT mapping_version_target_composite FOREIGN KEY (tenant_id, target_contract_version_id)
           REFERENCES public.contract_version (tenant_id, id),
       CONSTRAINT mapping_version_content_unique UNIQUE (tenant_id, mapping_id, content_sha256),
       CONSTRAINT mapping_version_number_unique UNIQUE (mapping_id, version),
       CONSTRAINT mapping_version_tenant_id_key UNIQUE (tenant_id, id)
   );
   -- ENABLE + FORCE row-level security, error-deny policy (the V2_2_51 form);
   -- GRANT SELECT, INSERT; REVOKE UPDATE, DELETE, TRUNCATE FROM ipaas_app.
   ```
   [Inferred] shape; every pattern in it is [Verified] in the files cited in Context.

2. **Created at draft save, deduplicated by content.** Inside the existing save transaction,
   for every live mapping that has both bindings: `content_sha256` = SHA-256 of the canonical
   JSON of `(engine, source_contract_version_id, target_contract_version_id, field_mappings,
   compiled_expression)`; `INSERT ... ON CONFLICT (tenant_id, mapping_id, content_sha256) DO
   NOTHING`, then read the id. An unchanged mapping keeps its id, so a save that touches only a
   route condition creates 0 mapping versions. `version` = max + 1 taken under `SELECT ... FOR
   UPDATE` of the `mapping` row. A mapping returning to earlier content reuses that earlier row.
   [Inferred]
3. **The snapshot references it.** Each snapshot mapping entry gains `mappingVersionId` (null
   while the mapping has no bindings). The entry keeps its content so the E3 publish
   materialisation stays as it is; publish verifies the content hash equals the referenced row.
   [Inferred]
4. **No lifecycle of its own.** A mapping version takes effect only inside an approved and
   published interface version (ADR-011 Decision 3 rule 2), so its approval is the interface
   version's approval, with its author-never-approver refusal (`V2_2_4:124-151`). This is the
   difference from `contract_version`, which is shared across flows and therefore approved on
   its own. [Inferred]
5. **Publish-time pin check (T-190, ADR-011 rule 6).** For every route in the approved snapshot:
   `mappingVersionId` is not null; the version's source binding equals the interface version's
   `source_contract_version_id` (`V2_2_4:26`); its target binding is an `APPROVED` version of a
   `TARGET_REQUEST` contract of the route's target system. The route's pinned target contract
   version **is** the mapping version's target binding - no second column on `route`. [Inferred]
6. **Sample-run gate (T-037).** `mapping_sample_run (tenant_id, mapping_id, content_sha256,
   sample_sha256, passed, actor_id, ran_at)`, INSERT/SELECT only, owned by lane C2. Keyed by
   hash, so testing unsaved content creates no version row. Publish refuses when any pinned
   version's `content_sha256` has no passing run. [Inferred]
7. **Pins and reads.** E9 adds `delivery.mapping_version_id uuid` (no FK - cross-module,
   partitioned parent, the `V2_3_3_1:7-9` rule). The data plane reads mapping versions by id
   through config's read-only API over published versions (`documents/architecture.md:48`),
   never the live `mapping` row. [Inferred]
8. **Retire and replay.** No row is ever deleted (no DELETE grant). "Retired" is derived: 0
   `PUBLISHED` interface versions and 0 non-final deliveries reference it; a retired version
   stays readable for replay (ADR-011 rule 4). [Inferred]

| Versioned thing | Table | Own approval | Referenced from |
|---|---|---|---|
| Contract (E3/E5) | `contract_version` | yes | interface version (source), mapping version (both) |
| Interface (E3) | `interface_version` (snapshot) | yes (author never approver) | message (E8) |
| Mapping (E7, this ADR) | `mapping_version` | no - rides the interface version | snapshot entry, delivery (E9) |

## What this ADR gates

- E7 lane C1 starts on this shape (migration, save path, T-190 check); lane C2 builds
  `mapping_sample_run`; E9 lane C writes `delivery.mapping_version_id`.
- Existing E3/E5 tests that publish a flow whose route still has the unbound `'$'` default
  mapping will be refused by T-190 once it lands. Lane C1 updates those fixtures to bind
  contract versions - part of T-190, not new scope. [Inferred - count the affected tests at start]

## Conformance tests this ADR adds

1. `UPDATE` and `DELETE` on `mapping_version` as `ipaas_app` fail with 42501 (1 refusal each).
2. Two saves with no mapping change create 0 new rows; a changed expression creates exactly 1,
   with `version` + 1.
3. A mapping version bound to another tenant's contract version is refused by the composite FK
   (forged-tenant matrix `E7MappingMatrix`).
4. ADR-011 rule 6: a mapping bound to source contract 1.0 cannot be published inside an
   interface version pinned to 1.1 (refused, named).
5. Replay pin: a dead letter pinned to mapping version 1 is replayed after version 2 is published
   and transforms with version 1's expression.
6. The data plane never reads the working copy: an ArchUnit/SQL-text rule finds 0 references to
   `public.mapping` (the table, not `mapping_version`) outside `platform-config`.

## Consequences

- Gains: a real graph node with database-enforced bindings and tenant safety; stable ids for
  pins, the sample gate and replay; no new rows when nothing changed.
- **Gives up:** the snapshot is no longer fully self-describing for mappings (it carries an id
  plus a duplicate of the content for publish); every save pays one hash and up to one insert per
  changed mapping; version numbers are per mapping and can be reused content (a revert points
  back at an older number rather than minting a new one).

## Revisit if

- ADR-006 picks an engine whose compiled artefact is not text (the `compiled_expression` column).
- ADR-004's one-mapping-per-route rule changes (shared mappings would need a mapping identity
  independent of the route).
- More than 1,000 mapping versions accumulate for one mapping (measured by a count query) - then
  consider pruning unreferenced, never-published versions.
