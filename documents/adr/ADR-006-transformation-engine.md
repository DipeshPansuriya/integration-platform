---
type: ADR
title: "ADR-006 - Transformation engine: JSONata (Dashjoin Java port) called directly by platform-transform, confirmed by the spike"
status: "Accepted (approved by Dipesh 2026-10-12, as recommended) - the spike T-030 to T-032 still has to confirm gates G1 to G4; lane D T-034 and T-035 may start now; T-036, T-039 and T-108 wait for the spike results"
approved: "Dipesh, 2026-10-12 (hub memory m-f1fcfc)"
date: 2026-10-11
author: "mahesh-architect (ledger row 307 under row 278, task T-033)"
project: ipass/integration-platform
provenance: new-decision
related:
  - ADR-004-mapping-canvas.md
  - ADR-003-rules-engine.md
  - ADR-010-camel-routing-core.md
  - ADR-011-targets-contracts-bulk-ordering.md
  - ../architecture.md
---

# ADR-006 - Transformation engine: JSONata (Dashjoin Java port) called directly by platform-transform, confirmed by the spike

Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`. Paths without a prefix are in this repository.
Every statement carries a grade: **[Verified]** (read in the cited file this pass), **[Inferred]**
(a reasoned conclusion, not yet measured) or **[TBD]** (needs the spike or a fact only Dipesh holds).

> **Status of this draft.** The plan writes ADR-006 "before the canvas code, after its spike"
> (`documents/adr/README.md:22`) [Verified]. The spike tasks T-030, T-031 and T-032 have not run
> (epic E7 has not started; `ws/_specs/backlog.md:197`) [Verified]. This draft therefore fixes the
> question, the candidates, the scoring rule, the gates and the rules carried with the decision;
> the measured results are **[TBD]** and the weighted table below is a desk score that the spike
> replaces before Dipesh approves.

> **Approved by Dipesh 2026-10-12** (hub memory m-f1fcfc), as recommended, before the spike ran.
> What he approved: (1) the JSONata language through the Dashjoin port, **provided the spike
> T-030 to T-032 confirms gates G1 to G4** - the results table below is still filled from the
> spike report, and Decision 2 applies if candidate A fails a gate; (2) lane D **may start T-034
> and T-035 now**; (3) **T-036, T-039 and T-108 wait for the spike results**; (4) JSONata4Java
> 3.0.0 joins the spike as a same-language control. If the spike moves the decision to C (ZEN) or
> no candidate passes, the record goes back to Dipesh before any canvas expression code.

## How this differs from ADR-004

| | ADR-004 (Accepted, recreated) | ADR-006 (this record) | Grade |
|---|---|---|---|
| Decides | The **drawing surface** of the mapping canvas in the browser: React Flow (`@xyflow/react`) for pan, zoom, edges, connection hooks | The **runtime engine and language** that executes a mapping on the server, the stored form of a mapping, and the contract between the drawn graph and the expression | [Verified] `documents/adr/ADR-004-mapping-canvas.md:39-41`, `:56-58` |
| Lives in | `ui/` | `platform-transform` (server), plus the raw-expression editor choice in `ui/` | [Verified] `documents/architecture.md:51`, `:58` |
| Link between them | ADR-004 rule 1: "the drawn graph emits exactly the JSONata expression a person could type ... The engine is decided by ADR-006" | This record confirms or replaces JSONata; if it chose an engine the graph cannot emit one-to-one, ADR-004 must be revisited | [Verified] `documents/adr/ADR-004-mapping-canvas.md:56-58`, `:86` |

Nothing in ADR-004 is repeated or changed here. [Verified]

## Context

- The plan adopts "JSONata engine `com.dashjoin:jsonata`, called directly by the transform module
  (no Camel inside it), pinned to the version Camel 4.22.1 uses - 0.9.10", with "ADR-006 spike
  confirms" and "the five breaking cases still decide" (`ws/_specs/project-plan.md:82`). [Verified]
- The R&D scan named IBM JSONata4Java as candidate 1; the plan corrected this: the engine Camel ships
  is the Dashjoin Java port (`ws/_specs/project-plan.md:82`; `ws/_reports/2026-09-30-oss-rnd.md:95`).
  [Verified]
- The phase is done when, among others, ADR-006 is written and put to Dipesh before the canvas code,
  the drawn graph emits exactly the JSONata expression a person could type, the mapping test round
  trip stays under half a second, the spike passed all five breaking cases (lookups, conditionals,
  array restructuring, enrichment, error translation) on the engine the runtime uses, and the
  transform budget is 5 s (`ws/_specs/project-plan.md:112`; clauses E7-C1 to E7-C13 at
  `ws/_specs/backlog.md:1993-2005`). [Verified]
- The spike field written into the backlog is Dashjoin JSONata 0.9.10, ZEN expressions, and JOLT and
  JSLT as controls (`ws/_specs/backlog.md:1779-1784`); one failed case disqualifies an engine
  (`:1787-1790`). [Verified]
- Two Jackson generations: Spring Boot 4 uses Jackson 3, `camel-jsonata` 4.22.1 depends on Jackson 2;
  calling the engine directly "may avoid" it, and the spike measures any conversion cost
  (`ws/_specs/project-plan.md:199`; `documents/adr/ADR-010-camel-routing-core.md:369-371`). [Verified]
- Mapping in one language and rules in ZEN means configurators learn two languages; the spike
  records whether "ZEN everywhere" is viable (`documents/adr/ADR-003-rules-engine.md:73-75`, `:88-89`;
  `ws/_specs/project-plan.md:208`). [Verified]
- The stored mapping already has a graph column and an expression column: `field_mappings jsonb`,
  `compiled_expression text NOT NULL`, `test_samples jsonb`, one mapping per route
  (`platform-config/src/main/resources/db/migration/V2_2_3__config_flows.sql:54-63`). [Verified]
- `platform-transform` does not exist yet: the parent build lists domain, delivery, routing, ingest,
  config, tenancy, app and arch-test only (`pom.xml:22-35`). [Verified]
- In the message path the transform runs on the bulkhead thread with a 5 s budget; a mapping error
  is `PERMANENT` and dead-lettered with the reason (`documents/architecture.md:105`). [Verified]

## Options

Five candidates. The first four come from the backlog's spike field; **JSONata4Java 3.0.0 is added
here as a same-language control** (see Open item 2). [Verified] for the field, [Inferred] for the
addition.

| # | Candidate | Licence | Desk evidence | Grade |
|---|---|---|---|---|
| A | **Dashjoin JSONata `com.dashjoin:jsonata` 0.9.10**, called directly | Apache-2.0 | The version Camel 4.22.1 pins; the plan's favourite | [Verified] version and role `ws/_specs/project-plan.md:82`; licence [Inferred] - T-032 records it from the POM |
| B | IBM JSONata4Java 3.0.0 | Apache-2.0 | Implements JSONata 1.8.4 (no transform operator, some functions missing); 3.0.0 moved to Jackson 3 | [Verified] `ws/_reports/2026-09-30-oss-rnd.md:95` |
| C | ZEN expressions (GoRules, already used for rules) | MIT | One language everywhere; weakest of the four for array restructuring and enrichment | [Verified] `ws/_reports/2026-09-30-oss-rnd.md:98`; `pom.xml:173` (zen-engine already managed) |
| D | JOLT (bazaarvoice) - control | Apache-2.0 | No release since 0.1.6 (2022); no maintained editor | [Verified] `ws/_reports/2026-09-30-oss-rnd.md:96` |
| E | JSLT (schibsted) - control | Apache-2.0 | Frozen language, bus factor 1, no editor | [Verified] `ws/_reports/2026-09-30-oss-rnd.md:97` |

### Gates (pass or fail - an engine that fails any one is out, whatever its score)

| Gate | Pass condition | Source | Grade |
|---|---|---|---|
| G1 - five breaking cases | Lookups, conditionals, array restructuring, enrichment and error translation each produce the expected output on the reference payloads | `ws/_specs/project-plan.md:112`; `ws/_specs/backlog.md:1787-1790` | [Verified] |
| G2 - runaway stop | A non-terminating expression returns a mapping-timeout result within 5 s + 100 ms, and the worker thread is free afterwards (no thread left spinning) | E7-C10, `ws/_specs/backlog.md:1896-1899`; the "thread is free" part is **added here** | [Verified] budget; [Inferred] thread condition |
| G3 - round trip | 100 mapping tests on the reference sample: every round trip under 500 ms, Jackson conversion included | E7-C6, `ws/_specs/backlog.md:1884-1887`; `ws/_specs/project-plan.md:199` | [Verified] |
| G4 - no Camel | 0 `org.apache.camel` imports in `platform-transform` (architecture test) | `ws/_specs/backlog.md:1799-1802` | [Verified] |

### Weighted trade-off (desk score, 1 = poor, 5 = strong; the spike replaces every score marked [Inferred])

| Criterion | Weight | A Dashjoin JSONata | B JSONata4Java | C ZEN | D JOLT | E JSLT |
|---|---|---|---|---|---|---|
| Canvas emits it one-to-one as an expression a person could type (ADR-004 rule 1) | 20 | 5 | 5 | 3 | 2 | 3 |
| Natural fit for array restructuring and enrichment | 15 | 5 | 4 | 2 | 3 | 4 |
| Runtime control: time budget, depth limit, no I/O functions | 15 | 3 | 3 | 3 | 4 | 3 |
| Raw-expression editor available (gnata, CodeMirror 6, MIT) | 10 | 4 | 3 | 3 | 1 | 1 |
| Dialect parity between runtime and editor | 10 | 4 | 2 | 5 | 4 | 4 |
| Dependency hygiene (Jackson 3 build, footprint, no Camel) | 10 | 4 | 5 | 4 | 3 | 2 |
| Maintenance and licence | 10 | 3 | 4 | 4 | 2 | 2 |
| Configurator usability (languages to learn) | 10 | 3 | 3 | 5 | 2 | 2 |
| **Weighted total (of 500)** | 100 | **400** | 375 | 345 | 265 | 275 |

Grades: weights [Inferred] (team judgement, derived from the done-when clauses); C's
array-restructuring score and D/E's editor and maintenance scores [Verified] against
`ws/_reports/2026-09-30-oss-rnd.md:95-98`; every other score [Inferred]. In particular A's dialect
score assumes the Dashjoin port follows the JSONata 2.x reference that gnata targets, and A's hygiene
score assumes the port carries no Jackson dependency of its own - both are the open research
question at `ws/_specs/project-plan.md:217` and are measured by T-032. [TBD]

### Spike results (filled by T-030 to T-032, amit-developer; this table is the evidence Dipesh approves on)

| Candidate | G1 lookups | G1 conditionals | G1 arrays | G1 enrichment | G1 errors | G2 runaway | G3 p100 round trip | Dialect gap vs reference | Jackson cost | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| A Dashjoin JSONata 0.9.10 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| B JSONata4Java 3.0.0 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| C ZEN expressions | TBD | TBD | TBD | TBD | TBD | TBD | TBD | n/a | TBD | TBD |
| D JOLT (control) | TBD | TBD | TBD | TBD | TBD | TBD | TBD | n/a | TBD | TBD |
| E JSLT (control) | TBD | TBD | TBD | TBD | TBD | TBD | TBD | n/a | TBD | TBD |

All cells [TBD]. The spike report goes to `ws/_reports/` and is linked here by path.

## Decision (Accepted 2026-10-12 - the spike confirms the gates)

1. **Adopt the JSONata language, executed by `com.dashjoin:jsonata` 0.9.10, called directly by
   `platform-transform`** - provided candidate A passes gates G1 to G4. [Inferred until the spike]
2. **Decision rule if A fails a gate:** the highest-scoring candidate that passes all four gates wins,
   in table order B, then C. If B wins, the language is unchanged (JSONata), ADR-004 stands and only
   the library changes. If C wins, ADR-004 rule 1 must be revisited (its Revisit-if line
   `documents/adr/ADR-004-mapping-canvas.md:86`) and ADR-003's "one language" revisit fires
   (`documents/adr/ADR-003-rules-engine.md:88-89`). If no candidate passes, the canvas does not start
   and Dipesh is asked. [Inferred]
3. **Version pin:** the engine version is held to the one Camel 4.22.1 manages, so a later
   `camel-jsonata` on the classpath can never bring a second version (`ws/_specs/project-plan.md:82`).
   [Verified] for the plan's pin; [Inferred] for the reason.

## Rules carried with the decision

1. **One port, no engine types outside the module.** `platform-transform` exposes one port
   (T-108): compile an expression once, apply a compiled mapping to a payload with a budget. No
   engine class appears in any other module's imports; an architecture test enforces it next to G4.
   [Inferred - T-108 implements]
2. **The expression is the program; the graph is its drawing.** `mapping.compiled_expression` is
   what runs; `mapping.field_mappings` is what the canvas draws
   (`platform-config/src/main/resources/db/migration/V2_2_3__config_flows.sql:58-59`). The graph
   emits a canonical subset of JSONata; the parity test proves 10 of 10 hand-typed references equal
   their drawn emissions (`ws/_specs/backlog.md:1859-1862`). An expression edited by hand outside the
   subset is kept and shown on the canvas as one raw-expression node, never silently rewritten.
   [Verified] columns and test; [Inferred] raw-node rule - T-036 confirms.
3. **Engine identity is pinned with the version.** The published interface-version snapshot records
   the engine and its version (for example `dashjoin-jsonata:0.9.10`) for every mapping, so a replay
   of a retired version (ADR-011 Decision 3) runs on a known dialect; an engine upgrade re-runs every
   published mapping's stored samples before the version changes. [Inferred - new team decision]
4. **Closed function set, no I/O during evaluation.** Only the language's built-in functions plus
   one registered lookup function. The lookup function reads from a snapshot of the tenant's lookup
   entries loaded **before** evaluation through the tenant-scoped repository, with the tenant taken
   from the delivery row (ADR-011 Decision 8). Evaluation itself touches no database, file, network
   or clock beyond the budget timer; configurators cannot register functions. Lookup values stay
   immediate and unversioned (OQ-07 stays Dipesh's; `documents/architecture.md:77`). [Verified]
   OQ-07 and lookup model; [Inferred] snapshot and closed-set rule.
5. **The 5 s budget is enforced outside the engine.** Evaluation runs on a small bounded pool of
   platform threads (not virtual threads: a CPU-bound runaway would pin a carrier); at 5 s the
   result is `MAPPING_TIMEOUT`, classed `PERMANENT` and dead-lettered with the reason
   (`documents/architecture.md:105`). G2 proves the engine stops on interrupt or offers a native time
   box; an engine that keeps spinning fails G2. A recursion-depth limit is set where the engine
   offers one. [Verified] budget and class; [Inferred] pool and thread choice; [TBD] engine
   interrupt behaviour.
6. **Jackson boundary.** Payloads are parsed by the platform's Jackson 3 into plain Java maps and
   lists; the engine receives no Jackson 2 type. T-032 records the conversion cost inside G3.
   [Inferred - T-032 measures]
7. **Raw-expression editor.** If JSONata wins (A or B), T-039 integrates the gnata CodeMirror-6
   editor (MIT); otherwise T-039 is dropped (`ws/_specs/backlog.md:1873`). [Verified]

## What this ADR gates

| Work | Gate | Grade |
|---|---|---|
| T-030, T-031, T-032 (spike, lane A) | None - they produce this ADR's evidence and start at once | [Verified] `ws/_specs/backlog.md:197` |
| T-108 transform port, T-109 5 s budget, T-038 round-trip test (lane B) | ADR-006 approved with spike results | [Verified] `ws/_specs/backlog.md:198` |
| T-036 graph-to-expression emitter (lane B) | ADR-006 approved and T-034 graph types fixed | [Verified] `ws/_specs/backlog.md:198` |
| T-039 raw-expression editor (lane D) | ADR-006 approved **and** JSONata chosen | [Verified] `ws/_specs/backlog.md:200`, `:1873` |
| T-034 canvas scaffold, T-035 schema trees and links (lane D) | May start now - Dipesh 2026-10-12 (see "Can lane D start" below) | [Verified] m-f1fcfc |
| E9 route templates (E9-F1-S1 depends on E7-F1-S1) and E11 composition T-049 | Indirect: they call the transform port | [Verified] `ws/_specs/backlog.md:2225`; `:3050` |

### Can lane D start while this ADR is Proposed?

- The backlog's build-order table says "ADR-006 **approved** before the canvas lane (D) starts"
  (`ws/_specs/backlog.md:115`); the plan's done-when and the Gherkin say "written and **put to**
  Dipesh before the canvas code" (`ws/_specs/project-plan.md:112`; `ws/_specs/backlog.md:1793-1797`).
  The two sources disagree. [Verified]
- T-034 (React Flow scaffold) is governed by ADR-004, which is Accepted, and is engine-independent.
  T-035 (schema trees from contract JSON Schema, link creation, add/change/delete) is
  engine-independent as long as its link model stores field paths, not expressions. Both are
  reversible within lane D's own files in `ui/`. [Inferred]
- **Recommendation:** read the gate as "put to Dipesh" for T-034 and T-035 only, so lane D starts
  them now; keep T-036, T-039 and T-108 behind approval with spike results.
- **Decided by Dipesh 2026-10-12:** as recommended - T-034 and T-035 start now; T-036, T-039 and
  T-108 wait for the spike results. [Verified] m-f1fcfc

## Consequences

- One engine library (0.9.x, a small vendor's port) sits on the data path of every delivery; it is
  isolated behind one port, so replacing it touches one module. [Inferred]
- Configurators learn two languages (JSONata for mappings, ZEN for rules) unless C wins. [Verified]
  `documents/adr/ADR-003-rules-engine.md:73-75`
- We own the emitter and the canonical subset; the canvas never becomes a black box because the
  parity test fails on any drift. [Verified] `ws/_specs/backlog.md:1865-1868`
- `platform-transform` is a new Maven module: the parent `pom.xml` module list and dependency
  management change once, as an integration seam before E7 lanes start. [Verified] `pom.xml:22-35`

**What we give up:** a single expression language across rules and mappings; a large-vendor engine
with a long release history; and the option to let configurators add their own functions.

## Conformance tests this ADR adds

Each written failing first: G1 per candidate (T-031); G2 runaway stop with the thread freed (T-109);
G3 100 round trips under 500 ms (T-038); G4 and rule 1 architecture tests (T-108); the 10-of-10
parity test (T-036); an engine-version bump without a samples re-run is refused (rule 3, T-037); a
lookup evaluated with tenant A bound never returns tenant B's entry (rule 4, T-163). [Inferred - owners
as in `ws/_specs/backlog.md:197-200`]

## Revisit if

- Candidate A fails any gate in the spike (then Decision 2 applies). [TBD]
- The Dashjoin port has no release for 12 months, changes licence, or Camel moves to another engine.
  [Inferred]
- A mapping needs streaming or payloads above the 1 MB intake cap. [Inferred]
- Dipesh decides one language must serve rules and mappings. [Inferred]

## Open

1. **Decided (Dipesh, 2026-10-12):** lane D starts T-034 and T-035 now; T-036, T-039 and T-108
   wait for the spike results.
2. **Decided (Dipesh, 2026-10-12):** JSONata4Java 3.0.0 joins the spike as a same-language control.
3. Still open: the weighted table and the results table are re-scored from the spike report
   (T-030 to T-032). The approval holds while candidate A or B passes all four gates; C winning or
   no candidate passing returns this record to Dipesh.
