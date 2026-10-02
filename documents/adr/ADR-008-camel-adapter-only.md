---
type: ADR
title: "ADR-008 - Apache Camel as outbound adapters only in v1"
status: "Superseded by ADR-010 (2026-10-02). Originally Accepted 2026-09-30; RECREATED 2026-10-02 from surviving sources for the record - Dipesh re-confirms the recreation"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0) - recreation"
project: ipass/integration-platform
provenance: recreation
superseded-by: ADR-010-camel-routing-core.md
related:
  - ADR-010-camel-routing-core.md
  - ADR-005-messaging.md
---

# ADR-008 - Apache Camel as outbound adapters only in v1

> **Superseded by [ADR-010](ADR-010-camel-routing-core.md) on 2026-10-02.** Kept because its
> reasons 1-2 (the persisted Delivery state machine) still hold and are carried into ADR-010, and
> because ADR-010 reopens this record through the trigger this record named itself.
>
> **Recreation.** The original file was deleted with the old repository on 2026-10-02. Surviving
> sources: `ws/_memory/shared.md:29` (m-6b1152, which quotes ADR-008:78 and :93),
> `ws/_reports/2026-09-30-oss-rnd.md:11`, `:51-56`, `:171`, `:182`; `ws/_specs/project-plan.md:24`,
> `:78`, `:86` (provenance table row `ws/_specs/project-plan.md:168`). The plan's table cites the
> memory line as `:28`; that line has since moved to `:29` - the memory id is the stable key.

Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`.

## Context (as it stood on 2026-09-30)

*Source: `ws/_memory/shared.md:29` (m-6b1152); `ws/_reports/2026-09-30-oss-rnd.md:51-56`.*

v1 scope was HTTP targets only; CSV and file-drop delivery were explicitly v2 (deleted
integration-platform-design.md:85, quoted in m-6b1152). The Delivery entity - a persisted state
machine with pinned mapping versions, append-only attempt history, dead-letter and manual retry
two weeks later - was the product's differentiator; the R&D scan confirmed that none of the
workflow engines provides it: "their in-flight retry/execution model is the mismatch"
(`ws/_reports/2026-09-30-oss-rnd.md:54-55`).

## Decision (as originally Accepted)

*Source: m-6b1152 quoting ADR-008:78; `ws/_reports/2026-09-30-oss-rnd.md:11`, `:171`, `:182`.*

1. **Camel only behind the outbound adapter port.** v1's `OutboundAdapter` is HTTP-only
   (ADR-008:78). Camel is not the routing or orchestration core.
2. **Reasons 1-2: retry, dead-letter, replay and version pinning stay in our persisted Delivery
   state machine**, not in an in-flight framework retry (`ws/_specs/project-plan.md:86` records
   "ADR-008 reasons 1-2 still hold"; `ws/_reports/2026-09-30-oss-rnd.md:182`).
3. **Resilience4j** for circuit breaking and timeouts (`ws/_reports/2026-09-30-oss-rnd.md:171`).
4. **Correcting an earlier claim:** routes built at runtime from configuration are a verified
   Camel capability - the record corrected an earlier statement that they were not
   (`ws/_specs/project-plan.md:78`, which cites the deleted section by that title; its wording
   did not survive).

## Reopen trigger (as originally written)

*Source: m-6b1152 quoting ADR-008:93.*

**"v2 scope pulled into v1."**

## Why it is superseded

*Source: `ws/_specs/project-plan.md:24`; `ws/_memory/shared.md:25` (m-703833).*

On 2026-10-02 Dipesh's formal requirement pulled v2 scope into v1 - CSV over a queue manager,
DB and File targets - and made Apache Camel the integration, routing and transformation
technology by his binding decision. That is this record's own reopen trigger. ADR-010 replaces
it; reasons 1-2 above are carried into ADR-010 unchanged.
