---
type: ADR
title: "ADR-015 - Target adapters live in platform-routing; the code's system-type register is aligned to ADR-011 (MQ becomes MQ_CSV, KAFKA_TOPIC added) in E10"
status: "Proposed (mahesh-architect, 2026-10-11) - Dipesh approves. Confirms pre-plan item M8; corrects a code drift from ADR-011 Decision 1"
date: 2026-10-11
author: "mahesh-architect (ledger row 307 under row 278)"
project: ipass/integration-platform
provenance: new-decision
related:
  - ADR-008-camel-adapter-only.md
  - ADR-010-camel-routing-core.md
  - ADR-011-targets-contracts-bulk-ordering.md
  - ../architecture.md
---

# ADR-015 - Target type register and adapter placement

Conventions and grades as in [ADR-013](ADR-013-mapping-versions.md). Short on purpose: both
points apply decisions already accepted; nothing new is chosen except which side moves.

## Context

- `platform-routing` owns "route templates per target type, target adapters, the per-target
  bulkheads and circuit breakers, the outbound destination check"; `platform-delivery` must not
  "send anything itself" (`documents/architecture.md:50`, `:52`). [Verified]
- `org.apache.camel` is used only in `platform-routing`, enforced by ArchUnit
  (`documents/adr/ADR-010-camel-routing-core.md:86-88`); the adapters send with `camel-http` and
  `camel-jms` (`documents/adr/ADR-011-targets-contracts-bulk-ordering.md:56-57`). [Verified]
- The backlog puts adapters, breakers and the check in `platform-delivery`
  (`ws/_specs/backlog.md:2551-2553`, `:2623`, as quoted by `ws/_specs/preplans/E10-plan.md:45`).
  [Verified in the plan; backlog lines not re-read]
- `platform-routing` depends on `platform-delivery` only (`platform-routing/pom.xml:32`); the
  destination check is implemented in `platform-config`
  (`platform-config/src/main/java/io/integrationplatform/config/destination/DestinationGuard.java`).
  ADR-011 Decision 1 requires **one** check run at save, test-connection and send
  (`ADR-011:68-75`). [Verified]
- Type register drift: ADR-011 Decision 1 names `API`, `MQ_CSV`, `DB`, `FILE`, `KAFKA_TOPIC`
  (`ADR-011:54-60`); the code has `API`, `DB`, `MQ`, `FILE`
  (`platform-config/src/main/resources/db/migration/V2_2_7__config_system_type_settings.sql:13`,
  `platform-config/src/main/java/io/integrationplatform/config/system/application/SystemSettings.java:33`,
  `:53`). [Verified]

## Decision (Proposed)

1. **Adapters live in `platform-routing`** (`routing/adapter/**`), as the E10 pre-plan decided.
   Outcome classification and retry timing stay in `platform-domain` / `platform-delivery`. The
   backlog wording is wrong and is corrected in the backlog (Arjun / Mira), not in the code.
   Reason: Camel may not leave routing, and delivery may not send. [Verified rules; Inferred
   placement]
2. **The send-time destination check reuses config's `DestinationGuard`** - one implementation.
   `platform-routing` gains a dependency on `platform-config`'s public API (it needs config's
   read-only API over published versions anyway - `architecture.md:48`, ADR-013 item 7). The
   arch-test module rules must allow routing -> config api only, never config internals.
   [Inferred; TBD - the arch-test rule is written in the E10 seam]
3. **The code changes, not the ADR**, in **E10** (the epic that builds the MQ-CSV adapter), in
   Arjun's wave-0 seam, before lane work:
   - one config migration (E10 seam range `V2_2_1000-1009`): `UPDATE public.system SET type =
     'MQ_CSV' WHERE type = 'MQ'`, then replace `system_type_closed_set` with `CHECK (type IN
     ('API', 'MQ_CSV', 'DB', 'FILE', 'KAFKA_TOPIC'))`;
   - `SystemSettings.TYPES` and its schema key `MQ` -> `MQ_CSV`; `TestConnectionService.java:80`;
     the two tests that assert `"MQ"` (`SystemRegistryContainerTest.java:179`,
     `SystemSettingsTest.java:82`); `KAFKA_TOPIC` gets its settings schema in E16 with the other
     release-2 types - until then the code refuses it as an unknown type.
   Reasons: ADR-011 is accepted; the name carries the wire format (CSV via `camel-csv` /
   `camel-bindy`), and a future JSON-over-JMS target is a different route template, so a new type
   (`ADR-011:62-65`); no long-lived database holds `MQ` yet, so the data fix is trivial.
   [Verified references; Inferred "no long-lived database" from the pre-plan Flyway note]

## Consequences

- Gains: one register in ADR, database and code; no module rule bent.
- **Gives up:** a new routing -> config module edge; one rename touching E5 tests in E10.

## Revisit if

- Two MQ wire formats appear and a "type plus format" setting looks simpler than two types.
- The destination check needs data-plane state config cannot hold - then move it to a shared
  module.
