---
type: ADR
title: "ADR-003 - Rules engine: GoRules ZEN decision tables, authored in jdm-editor"
status: "Accepted (originally before 2026-10-02; RECREATED 2026-10-02 from surviving sources) - Dipesh re-confirms"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0) - recreation"
project: ipass/integration-platform
provenance: recreation
related:
  - ADR-004-mapping-canvas.md
  - ADR-011-targets-contracts-bulk-ordering.md
---

# ADR-003 - Rules engine: GoRules ZEN decision tables, authored in jdm-editor

> **Recreation.** The original ADR-003 was deleted with the old repository on 2026-10-02. Its
> surviving sources are the R&D report of 2026-09-30, which quotes and checks it
> (`ws/_specs/project-plan.md:165`, provenance table). Text marked **Added in the recreation** is
> a new team decision for Dipesh's re-confirmation.

Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`.

## Context

*Source: `ws/_specs/project-plan.md:111` (rules phase done-when), `:208`.*

End users who are not developers author three kinds of rule: validation rules, conditional
routing rules (which targets a message goes to) and lookup tables. A rejected payload must list
every violation in one response, never the first error only, and the test panel in the console
must run the same engine as the runtime.

## Options and decision

*Source: `ws/_reports/2026-09-30-oss-rnd.md:11` (the code-root ADR list: "ADR-003 rules engine
(GoRules ZEN)"), `:22`, `:98`, `:110-125`, `:198-201`.*

**Decision (as originally Accepted): GoRules ZEN** - decision tables in the JSON Decision Model
(JDM) format, evaluated in the JVM by the ZEN engine's Java binding (`io.gorules:zen-engine`,
MIT, on Maven Central - the original ADR verified this; re-confirmed 2026-09-30 at
`ws/_reports/2026-09-30-oss-rnd.md:98`).

**Authoring UI: `@gorules/jdm-editor`** (MIT, verified 2026-09-30, latest 1.52.0), which
resolved the original ADR's open question #1 ("is the editor usable?") as **usable**
(`ws/_reports/2026-09-30-oss-rnd.md:114`).

The original options compared are not recorded in any surviving file; only the verdict and its
two open points (editor licence; two expression languages) survive.

**Versions for the rebuild** (*source: `ws/_specs/project-plan.md:83`*): `io.gorules:zen-engine`
2.1.1 (Maven Central, 2026-09-29) and `@gorules/jdm-editor` pinned at a fixed version.

## Rules carried with the decision

*Source: `ws/_reports/2026-09-30-oss-rnd.md:115-125`, `:198-201`; `ws/_specs/work-breakdown.md:43`.*

1. **Time-limited adoption of the editor.** jdm-editor had no release for about 4.5 months at
   the 2026-09-30 check; the engine itself is active. The editor is pinned and wrapped in one
   thin adapter so the UI emits ZEN JSON through one seam.
2. **Fallback armed.** Our own decision-table grid stays designed behind that seam, and is built
   only if the editor goes about 6 months without a release or a needed feature forces a fork
   (MIT history allows a fork at 1.52.0).
3. **Same answer in editor and runtime** - one test proves the test panel and the runtime return
   the same verdict for the same input (`ws/_specs/project-plan.md:111`).
4. **Added in the recreation:** contract validation against the registered JSON Schema runs
   **before** the rules (ADR-011); rules see only payloads that are structurally valid. All
   violations from both steps are returned together.

## Consequences

*Source: `ws/_reports/2026-09-30-oss-rnd.md:98`; `ws/_specs/project-plan.md:208`.*

- Mapping uses JSONata (ADR-006, later phase) and rules use ZEN: configurators learn two
  expression languages. This was the original ADR's stated risk; the ADR-006 spike records
  whether "ZEN everywhere" is viable (it is the weakest for array restructuring and enrichment).
- Rule sets are configuration rows (JDM JSON in JSONB), versioned with the flow bundle.

**What we give up:** a single expression language across rules and mapping, and full control
over the editor's release cadence.

## Revisit if

- jdm-editor has no release for 6 months, or its licence changes on a future release.
- The ADR-006 spike shows ZEN expressions pass all five mapping cases (then one language may
  replace two).
