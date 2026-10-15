---
type: ADR
title: "ADR-006 - Transformation engine: JSONata (Dashjoin Java port) called directly by platform-transform, confirmed by the spike"
status: "Accepted (approved by Dipesh 2026-10-12, as recommended) - the spike T-030 to T-032 ran 2026-10-12 (lane A, T50) and confirms G1 5/5 and G4 on Dashjoin JSONata 0.9.10; G2 and G3 are measured by their owning tasks T-109 and T-038 in lanes B2/B3 against the real port, as the spike was scoped; T-036, T-039 and T-108 are unblocked; the filled results table goes to Dipesh for review"
approved: "Dipesh, 2026-10-12 (hub memory m-f1fcfc)"
amended: "2026-10-15 (Dipesh, option 1; ledger row 278) - Decision 7 raw-expression editor is plain CodeMirror 6 for wave 2, gnata an open follow-up; spike evidence repointed to the hub backup"
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

> **Status of this record.** The plan writes ADR-006 "before the canvas code, after its spike"
> (`documents/adr/README.md:22`) [Verified]. The spike (T-030, T-031, T-032, lane A amit-developer,
> tracker T50) **ran 2026-10-12** in the lane worktree `worktree-amit-developer-integration-platform-t50`;
> its evidence file was `platform-transform/src/test/resources/spike/results.md`, committed at
> `e39d24f` on that branch. **Candidate A (Dashjoin JSONata 0.9.10) passed all five breaking cases and
> gate G4 is green; candidates C, D and E are disqualified** by the one-failed-case rule. The results
> table below is filled from that file.

> **Evidence location - amended 2026-10-15.** The branch `worktree-amit-developer-integration-platform-t50`
> was retired in the row-278 branch tidy (2026-10-14) and `e39d24f` is reachable from no branch of this
> repository. The durable copy of the evidence is in the hub:
> `ws/_reports/row278-retired-branches-20261014/t50/0002-feat-row278-E7-ADR-006-spike-green-engine-x-case-tab.patch`
> - commit `e39d24f` as a format-patch; `results.md` is its last hunk (patch lines 838-962 hold
> `results.md` lines 1-125). `0001-red-row278-E7-spike-failing-first-ADR-006-G1-G4-guar.patch` beside it
> is the failing-first commit, and `TIP.txt` records tip `e39d24f` on base `95e2513` (an ancestor of
> `main`). Every later "`results.md` at `e39d24f`" in this record means that hub file. [Verified,
> read 2026-10-15]
>
> **Correction (2026-10-15).** An earlier version of this block said the harness re-runs the exact
> rows (`SpikeRecordedTest` re-derives every PASS/FAIL cell from a fresh run), so the table cannot
> drift from the code. That is not true on `main`: the spike harness
> (`platform-transform/src/test/java/io/integrationplatform/transform/spike/`, including
> `SpikeRecordedTest`) and `results.md` were never merged, so nothing on `main` re-derives this table.
> The table is a recorded snapshot of the 2026-10-12 run; reproducing it means applying the two backup
> patches onto `95e2513` in a throwaway worktree. [Verified: no `spike` path in `git ls-tree -r main`
> at `89e961c`]

> **Approved by Dipesh 2026-10-12** (hub memory m-f1fcfc), as recommended, before the spike ran.
> What he approved: (1) the JSONata language through the Dashjoin port, **provided the spike
> T-030 to T-032 confirms gates G1 to G4** - the results table below is still filled from the
> spike report, and Decision 2 applies if candidate A fails a gate; (2) lane D **may start T-034
> and T-035 now**; (3) **T-036, T-039 and T-108 wait for the spike results**; (4) JSONata4Java
> 3.0.0 joins the spike as a same-language control. If the spike moves the decision to C (ZEN) or
> no candidate passes, the record goes back to Dipesh before any canvas expression code.
> **Outcome of that condition:** A did not fail a gate, so the approval holds (see Open item 3 for
> the one clause this spike did not discharge - candidate B was not in the T50 brief and did not run).

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
`ws/_reports/2026-09-30-oss-rnd.md:95-98`; every other score was [Inferred]. **The two assumptions
this table leaned on are now measured** (T-032, `results.md` at `e39d24f`): A's dialect score
assumed reference parity - measured 7 of 8 probes match with one recorded deviation (`$round`), and
A's hygiene score assumed no Jackson dependency of its own - confirmed, the engine carries zero
Jackson. The desk ranking stands; the spike verdicts above, not these scores, decided. [Verified]

### Spike results (filled by T-030 to T-032, amit-developer; this table is the evidence Dipesh approves on)

| Candidate | G1 lookups | G1 conditionals | G1 arrays | G1 enrichment | G1 errors | G2 runaway | G3 p100 round trip | Dialect gap vs reference | Jackson cost | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| A Dashjoin JSONata 0.9.10 | PASS (33 ms) | PASS (1 ms) | PASS (6 ms) | PASS (0 ms) | PASS (0 ms) | spike n/a - measured by T-109 on the real port | spike n/a - measured by T-038 on the real port | 1 deviation on record: `$round` halves to even (0.5 -> 0, 2.5 -> 2); reference rounds away from zero (rule 8) | 0 - the engine carries no Jackson of any major; it sits on the string boundary (`Json#parseJson`) | **PASSED** (G1 5/5, G4 green) |
| B JSONata4Java 3.0.0 | - | - | - | - | - | - | - | not measured | not measured | **NOT RUN** - in the approval clause (4) but absent from the T50 brief; per Decision 2 B decides only if A fails a gate, and A did not fail (Open item 3) |
| C ZEN expressions 2.1.1 | FAIL (dynamic table key cannot be resolved) | PASS (637 ms; ternary only - `if/then/else` does not parse) | FAIL (no element-wise array mapping) | FAIL | PASS (6 ms) | n/a - disqualified at G1 | n/a - disqualified at G1 | n/a | 0 - string in/out through `JsonBuffer` | **DISQUALIFIED** (lookups, arrays, enrichment are structural, not flaky) |
| D JOLT 0.1.8 (control) | FAIL | FAIL | FAIL (shape maps, but no per-element totals, no aggregate) | FAIL | FAIL (no typed arithmetic; silent copy/drop) | n/a - disqualified at G1 | n/a - disqualified at G1 | n/a | n/a - not measured | **DISQUALIFIED** (all five cases) |
| E JSLT 0.1.14 (control) | FAIL (no `get()`, no dynamic key) | PASS (47 ms) | PASS (16 ms) | FAIL | FAIL (`(.code) * 1` is string repetition - returns `"S2"` silently, no error) | n/a - disqualified at G1 | n/a - disqualified at G1 | n/a | forces **Jackson 2** (`jackson-databind` 2.13.4.2, test scope only) onto the module | **DISQUALIFIED** (lookups, enrichment, error discipline) |

All cells from `results.md` at `e39d24f` [Verified this pass]. Gate G4 (no Camel): the
`TransformNoCamelArchitectureTest` rule passes on production classes and fires on a planted
violation - the proven-control form. G2 and G3 were never in the spike's scope: they are measured
against the real T-108 port in lanes B2/B3, by their owning tasks T-109 and T-038.

**T-032 dialect probes (A vs the JSONata reference):** 7 of 8 probes match the reference
(array-path iteration, `&` coercion, `$sum`, `$exists`, `$number`, `$uppercase`, `$substring`);
`$round` deviates. **T-032 Jackson boundary (measured, not asserted):** `platform-transform`
carries no Jackson of any major; the only candidate that would drag Jackson 2 in is the JSLT
control (test scope); Spring Boot's Jackson 3 (web layer) never reaches the transform module.

## Decision (Accepted 2026-10-12 - the spike confirms the gates)

1. **Adopt the JSONata language, executed by `com.dashjoin:jsonata` 0.9.10, called directly by
   `platform-transform`** - the spike ran 2026-10-12 and A passed G1 on all five breaking cases and
   G4 is green; G2 and G3 remain measured-in-code by T-109 and T-038 on the real port
   (`results.md` at `e39d24f`). [Verified for G1/G4; TBC for G2/G3]
2. **Decision rule if A fails a gate:** the highest-scoring candidate that passes all four gates wins,
   in table order B, then C. If B wins, the language is unchanged (JSONata), ADR-004 stands and only
   the library changes. If C wins, ADR-004 rule 1 must be revisited (its Revisit-if line
   `documents/adr/ADR-004-mapping-canvas.md:86`) and ADR-003's "one language" revisit fires
   (`documents/adr/ADR-003-rules-engine.md:88-89`). If no candidate passes, the canvas does not start
   and Dipesh is asked. **Note on the B-first fallback:** B did not run in the spike (Open item 3), so
   if A fails G2 or G3, B must be measured before it can be declared the winner. [Inferred]
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
   lists; the engine receives no Jackson type of any major - the string boundary is final. Now
   **measured, not asserted** (T-032b): `com.dashjoin:jsonata` 0.9.10 declares jackson-databind only
   at TEST scope and its jar holds no `com.fasterxml` reference; `platform-transform` carries no
   Jackson of any major; the only candidate that drags Jackson 2 in is the disqualified JSLT control
   (2.13.4.2, test scope); Boot's Jackson 3 stays in the adapters. The conversion cost inside G3
   remains for T-038. [Verified in `results.md` at `e39d24f`]
7. **Raw-expression editor - amended 2026-10-15 (Dipesh, option 1; ledger row 278).** Wave 2 ships
   plain CodeMirror 6 as the raw-expression editor: `codemirror` 6.0.2 (MIT, `ui/package.json:25`)
   with `minimalSetup` (`ui/src/mapping/expression/RawExpressionEditor.tsx:13`, `:42`), behind the
   pure editor contract `ui/src/mapping/expression/raw.ts`. [Verified at `89e961c`] **What this gives
   up:** JSONata-aware editing - no JSONata syntax highlighting, function completion or inline
   parse-error marks in the raw surface; a malformed expression is caught only when it is evaluated.
   **Open follow-up (not scheduled, no date committed):** integrate the gnata CodeMirror-6 JSONata
   editor (MIT) behind the same `raw.ts` contract, so the swap does not touch the graph, the emitter
   or the save request. [TBD - follow-up owner and wave to be set when it is planned]
   *Superseded wording (2026-10-11), kept for the record:* ~~If JSONata wins (A or B), T-039
   integrates the gnata CodeMirror-6 editor (MIT); otherwise T-039 is dropped
   (`ws/_specs/backlog.md:1873`). [Verified]~~
8. **Dialect deviations land in the port, not in hope.** `$round` in the Dashjoin port rounds
   halves to even (0.5 -> 0, 2.5 -> 2) while the reference rounds halfway away from zero (0.5 -> 1,
   2.5 -> 3): **money rounding never rides `$round`** - where a mapping must round monetary
   amounts, a port-side registered function defines the rule explicitly, so server and docs can
   never disagree silently. The same finding names the palette contract: the canvas's expression
   palette (T-036, ADR-004) lists the port's real function names (for example `$substring` exists,
   `$substr` does not), not the reference's. [Verified deviation in `results.md`; Inferred rule]

## What this ADR gates

| Work | Gate | Grade |
|---|---|---|
| T-030, T-031, T-032 (spike, lane A) | None - they produced this ADR's evidence; **done** (T50, `e39d24f`) | [Verified] `ws/_specs/backlog.md:197` |
| T-108 transform port, T-109 5 s budget, T-038 round-trip test (lane B) | ADR-006 approved with spike results - **the spike confirmed A on G1/G4, so these are unblocked**; their acceptance criteria include G2/T-109 and G3/T-038 passing | [Verified] `ws/_specs/backlog.md:198` |
| T-036 graph-to-expression emitter (lane B) | ADR-006 approved and T-034 graph types fixed - **unblocked**; rule 8 gives it the palette contract | [Verified] `ws/_specs/backlog.md:198` |
| T-039 raw-expression editor (lane D) | ADR-006 approved **and** JSONata chosen - **JSONata is chosen** | [Verified] `ws/_specs/backlog.md:200`, `:1873` |
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
- Dependency shape at merge (measured in the spike): **production scope carries exactly one new
  library - `com.dashjoin:jsonata` 0.9.10 (Apache-2.0)**, already pinned main-scope by the seam
  commit. The disqualified candidates stay TEST-scope only so the recorded results table keeps re-running
  (`SpikeRecordedTest`), and they never enter compile scope - G4's architecture test watches the
  production classes, and no `org.apache.camel` import may appear in `platform-transform`.
  [Verified] licences and scopes in `results.md` at `e39d24f`; control-scope discipline [Inferred]

**What we give up:** a single expression language across rules and mappings; a large-vendor engine
with a long release history; and the option to let configurators add their own functions.

## Conformance tests this ADR adds

Each written failing first: G1 per candidate (T-031); G2 runaway stop with the thread freed (T-109);
G3 100 round trips under 500 ms (T-038); G4 and rule 1 architecture tests (T-108); the 10-of-10
parity test (T-036); an engine-version bump without a samples re-run is refused (rule 3, T-037); a
lookup evaluated with tenant A bound never returns tenant B's entry (rule 4, T-163). [Inferred - owners
as in `ws/_specs/backlog.md:197-200`]

## Revisit if

- Candidate A fails a remaining gate (G2 via T-109 or G3 via T-038) - then Decision 2 applies, and
  note its B-first fallback needs B measured first (Open item 3). [TBC - lanes B2/B3]
- A mapping needs money rounding and the port-side rounding function is not built before it is
  needed (rule 8). [Inferred]
- The Dashjoin port has no release for 12 months, changes licence, or Camel moves to another engine.
  [Inferred]
- A mapping needs streaming or payloads above the 1 MB intake cap. [Inferred]
- Dipesh decides one language must serve rules and mappings. [Inferred]

## Open

1. **Decided (Dipesh, 2026-10-12):** lane D starts T-034 and T-035 now; T-036, T-039 and T-108
   wait for the spike results - the spike has now run (2026-10-12, T50), so the waiting part is
   discharged.
2. **Decided (Dipesh, 2026-10-12):** JSONata4Java 3.0.0 joins the spike as a same-language control.
3. **Candidate B did not run** (flagged to arjun-fullstack in the spike notes): the T50 brief
   (T-030 to T-032 as lane A received it) did not include it, and lane A correctly built only what
   was assigned. Per Decision 2, B decides only if A fails a gate, and A passed every gate the spike
   measures - so B's absence does not block this ADR. If Dipesh wants the same-language control
   measured anyway (for example to keep the B-first fallback measurable against a real engine), it
   is one small lane-A add to the harness. [Verified absent, `results.md` note 6]
4. G2 (runaway stop, thread freed) and G3 (100 round trips under 500 ms) are open until lanes B2/B3
   run T-109 and T-038 against the real port - the spike deliberately left them there, since they
   are properties of the port, not of the engine standalone. [Verified] scoping in `results.md`
5. **Follow-up (Dipesh, option 1, 2026-10-15):** the gnata CodeMirror-6 JSONata editor replaces or
   extends the wave-2 plain CodeMirror 6 raw-expression editor behind the `raw.ts` contract
   (Decision 7). Not scheduled; no date is committed. [TBD]
