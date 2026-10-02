---
type: ADR
title: "ADR-004 - Mapping canvas: React Flow (@xyflow/react) core library"
status: "Accepted (originally before 2026-10-02; RECREATED 2026-10-02 from surviving sources) - Dipesh re-confirms"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0) - recreation"
project: ipass/integration-platform
provenance: recreation
related:
  - ADR-003-rules-engine.md
  - ADR-011-targets-contracts-bulk-ordering.md
---

# ADR-004 - Mapping canvas: React Flow (@xyflow/react) core library

> **Recreation.** The original ADR-004 was deleted with the old repository on 2026-10-02. Its
> surviving sources are the R&D report of 2026-09-30 (`ws/_specs/project-plan.md:166`,
> provenance table). Text marked **Added in the recreation** is a new team decision for Dipesh's
> re-confirmation.

Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`.

## Context

*Source: `ws/_specs/project-plan.md:112`; `ws/_reports/2026-09-30-oss-rnd.md:57-61` (whole-product
answer, point 3).*

The field-mapping canvas is the product's centre: the user sees the source contract and a
target contract side by side, draws field-to-field links, and adds, changes and deletes
elements. No adoptable platform has it in any form, so the canvas is ours; the question is what
the drawing surface is built on.

## Decision

*Source: `ws/_reports/2026-09-30-oss-rnd.md:11` ("ADR-004 mapping designer (React Flow)"),
`:148-158`, `:206-207`; `ws/_specs/project-plan.md:84`.*

**Adopt React Flow (`@xyflow/react`) core library** for the canvas mechanics: pan and zoom, edge
routing, connection validation hooks, virtualisation.

- Licence: MIT (verified 2026-09-30, npm `@xyflow/react` 12.12.0; repository active). MIT covers
  commercial and hosted use without restriction.
- The paid **Pro subscription** (source of Pro example apps, support) is not used and not
  needed; we never copy Pro example code. Sponsoring the project is good practice, not a legal
  obligation.
- Effort saved, the original ADR's own estimate: about 60% of the canvas mechanics.

The original options compared are not recorded in any surviving file; only the verdict, the
licence reading and the effort estimate survive.

## Rules carried with the decision

*Sources as marked.*

1. **The drawing is not the program.** The drawn graph emits exactly the JSONata expression a
   person could type, proven by a test (`ws/_specs/project-plan.md:112`). The engine is decided
   by ADR-006 in the mapping phase.
2. **One mapping per interface-target pair**, so Target A and Target B never share one (deleted
   data-design.md:329, quoted at `ws/_specs/f3-plan-brief.md:43`; `ws/_specs/project-plan.md:112`).
3. **Added in the recreation:** each mapping is bound to one source contract version and one
   target contract version from the registry (ADR-011); the canvas shows the two schema trees
   of exactly those versions.

## Consequences

- The UI depends on one MIT library for canvas mechanics; the mapping semantics stay ours.
- Version currency is checked when the mapping phase starts.

**What we give up:** a ready-made mapping product (none exists that fits); we build the schema
trees, the link model and the expression emitter ourselves.

## Revisit if

- The `@xyflow/react` licence changes, or the core repository is archived.
- ADR-006 chooses an engine whose expressions the graph cannot emit one-to-one.
