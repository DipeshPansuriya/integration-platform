---
type: Design
title: "Design - the console's look and feel (configuration-driven integration platform)"
status: "Recreated 2026-10-07 (E3, T-095). Reference design C has no surviving description in words - two TBD lines wait for Dipesh. The console as built in E3 is described from the code. The redesign itself is the last phase (Release 2)."
date: 2026-10-07
author: "mahesh-architect (ledger row 278, T-095)"
project: ipass/integration-platform
provenance: recreation
related:
  - PRD.md
  - architecture.md
  - rule.md
  - diagrams/README.md
  - adr/ADR-004-mapping-canvas.md
---

# Design - the console's look and feel

> **Recreation, with nothing invented.** The earlier design document was deleted with the old
> repository on 2026-10-02, and no description of reference design C survives in words
> (`ws/_specs/project-plan.md:162`, searched again 2026-10-07 - section 6). This file therefore
> holds **no visual rule** - no colour, font, spacing, layout or component style - that is not
> sourced or marked TBD (E3-F3-S2 @negative, `ws/_specs/backlog.md:1117-1121`). What it does hold:
> where design C stands and what only Dipesh can supply (section 2), and the console as it really
> is after E3, the baseline the redesign restyles (sections 3-4).

**Grades.** Every statement carries one: **[Verified]** - read this session in the file cited;
**[Inferred]** - deduced from the neighbour named; **[TBD]** - a fact only Dipesh holds, one line
naming the question. Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`; all others are in this repository. Memory lines
drift; the id (`m-xxxxxx`) is the stable key. Rule R19 applies: no product or organisation name
appears here, so the design skill is named only by where it is recorded.

## 1. What this document is for, and when the redesign happens

| Statement | Grade | Source |
|---|---|---|
| The look-and-feel redesign comes **last**, after all functionality: it is plan phase 19, the end of Release 2, and starts only after the full end-to-end suite passes | [Verified] | `ws/_specs/project-plan.md:63`, `:98`, `:129`; `ws/_specs/backlog.md:4400`; PRD FR-19 (`PRD.md:122`); rule R20 (`rule.md:54`) |
| The redesign makes **no functional change**: the full suite must re-pass on the restyled build, and Dipesh reviews it screen by screen | [Verified] | `ws/_specs/project-plan.md:129`; `ws/_specs/backlog.md:4416-4430` (E19-F1-S1) |
| Every restyled screen must pass **WCAG AA contrast** and work **by keyboard with a visible focus** | [Verified] | `ws/_specs/project-plan.md:129`; `ws/_specs/backlog.md:4436-4448` (E19-F1-S2) |
| E3 needs this file only to (a) record design C in words or a TBD line for Dipesh, and (b) stay free of invented design. It does not restyle anything | [Verified] | `ws/_specs/backlog.md:1103-1124` (E3-F3-S2, T-095), `:1139` (E3-C10); `ws/_specs/project-plan.md:105` |
| The redesign task (T-154) restyles every screen "to design.md", so this file is its single reference | [Verified] | `ws/_specs/backlog.md:4413-4419`, `:4434`, `:4486` (E19-C1) |
| UI work, the redesign included, uses the design skill named on the project's Skills line in the hub | [Verified] | rule R20 (`rule.md:54`); `ws/_memory/overview.md:12`; m-2446fd (`ws/_memory/shared.md:513`, Dipesh 2026-10-01, row 268) |

## 2. Reference design C

| Statement | Grade | Source |
|---|---|---|
| Design C is the agreed reference for the redesign (ledger row 276) | [Verified] | `ws/_specs/project-plan.md:129`; `ws/_specs/backlog.md:4416`, `:4486` |
| Its origin: on 2026-10-02 Dipesh rejected both directions the team had built (A and B) as "not like new modern design", and the team asked him for a named visual reference instead of adjectives | [Verified] | m-b5697d (`ws/_memory/shared.md:455`) |
| "C" is the third direction, the one to be reworked only from his reference | [Inferred] | m-b5697d: "Direction C rework only after his reference" |
| What he sent: a **screenshot of a schema editor in an API-design tool**, as his modern reference | [Verified] (as recorded) | m-dbda93 (`ws/_memory/shared.md:454`), m-5916ad (`:453`), m-13338f (`:452`); ledger row 276 |
| The screenshot was kept **in that chat session only** - no file, and no description of it in words, was ever written | [Verified] | m-5916ad ("saved in this chat only"); `ws/_specs/project-plan.md:162`; search of 2026-10-07 (section 6) |
| Directions A and B are **not** the reference; their branches were in the repository deleted on 2026-10-02 and no branch of them exists here | [Verified] | m-b5697d; `git branch -a` in this repository on 2026-10-07: no row-276 branch |
| His words on colour (row 276): he asked for a modern design and said that for "color and such thing" the design skill can be used | [Verified] (paraphrased: the original names the skill) | ledger row 276, request column |
| Visual rules in force today (colour, type, spacing, layout, components): **none** | [Verified] | sections 2 and 3 of this file; no stylesheet in `ui/` (section 3) |
| Styling tokens: **none recorded** - the project has no `_mockups/tokens/` folder and no token in `_memory/` | [Verified] | listing of `ws/` (`_documents`, `_memory`, `_reports`, `_specs` only), 2026-10-07 |

**TBD-1 - Dipesh: what does reference design C look like?** Please describe it in words, or send the
schema-editor screenshot again so it can be written down here and you confirm the wording in one
reply. The description needs: navigation placement (top bar or side bar), page layout and
density, colour (background, one accent or several), type, and how the schema editor shows a
field (tree, table or form; type, required flag, description). *Recommendation:* send the
screenshot again when Release 2's redesign phase starts (E19); mahesh-architect writes the
description with each point traced to the screenshot, and you confirm it before T-154 begins.
This does not block Release 1 (`ws/_specs/project-plan.md:189`). [TBD]

**TBD-2 - Dipesh: when design C and the design skill disagree, which wins?** Rule R20 makes the
design skill mandatory for UI work (2026-10-01), and design C came a day later (2026-10-02).
*Recommendation:* the design skill supplies colour, type and spacing tokens - this matches your
row-276 words about colour - and design C supplies layout, navigation and the schema-editor
pattern; on a true conflict, design C wins, because it is the later and more specific choice.
[TBD]

## 3. The console as built in E3 - the baseline the redesign restyles

Read at `row278-integration` 762061c on 2026-10-07.

| Statement | Grade | Source |
|---|---|---|
| Stack: React 18 with TypeScript, built by Vite; the typed API client is generated from the backend's own OpenAPI document and never hand-edited | [Verified] | `ui/package.json:6`, `:8-9`, `:13`, `:27`, `:37-39` |
| The console is served as static files and calls only the admin API | [Verified] | `architecture.md:59` |
| **No visual design is applied**: no stylesheet file in `ui/`, and no `style` or `className` anywhere in `ui/src` - every screen renders with browser defaults | [Verified] | `git ls-files ui` (no `.css`); search of `ui/src` for `style\|className`: 0 hits |
| The code states the same: nothing beyond the scaffold is invented, and this file governs the look | [Verified] | `ui/src/App.tsx:4-5`; `ui/src/pages/FlowDefinition.tsx:5` |
| Browser title "Integration Platform Console"; page language English | [Verified] | `ui/index.html:2`, `:6` |

### 3.1 Screens

| Screen | Route | What it shows | Grade | Source |
|---|---|---|---|---|
| Shell | every route | A persistent left-side nav of grouped real links - Overview (Dashboard, Queue), Build (Flows, Versions, Systems, Contracts), Author (Rules, Mappings); a 48px top bar; hash routing so every screen loads directly by URL | [Verified] | `ui/src/App.tsx:65-89`, `:275-293`; hash-route comment `:1-2` |
| Home | `#/` | The signed-in Dashboard (heading "Dashboard"; "Checking your session..." while the session check runs) | [Verified] | `ui/src/App.tsx:312-313`; `ui/src/pages/Dashboard.tsx:106` |
| Flow definition (T-017, E3-F1-S1; T88 redesign) | `#/flows` (list landing), `#/flow/{id}`, `#/flow/new` | Heading "Flow definition"; a two-pane form with a sticky action bar (T88); fields Source system, destinations as configuration rows (two at first, addable and removable), Interface name, Business-key rule (mandatory), Ordering (`PER_KEY` default or `NONE`), Source schema (JSON Schema, a text area); a **Save draft** submit button | [Verified] | `ui/src/pages/FlowDefinition.tsx:6-8` (2-pane + routes), `:249` (heading), `:292-374` (fields), `:387-388` (Save draft) |
| Versions and approval (T-022, E3-F2-S1; T88 redesign) | `#/versions` | Heading "Versions and approval"; a table of flows with each version status (the selected row is marked for assistive tech with `aria-current` plus a hidden "(selected)" text, and shown bold with an accent bar); a lifecycle panel that offers only the actions valid for the selected status - `DRAFT`: **Save draft**, **Submit for approval**; `SUBMITTED`: **Approve**, **New draft**; `APPROVED`: **Publish**, **New draft**; `PUBLISHED`: **New draft**. A submitted version shows no **Save draft** at all | [Verified] | `ui/src/pages/flow-lifecycle.ts:8-13`; `ui/src/pages/Versions.tsx:222-237`, `:266-291` |
| Versions - test-harness only | `#/versions` | "Approve as the harness approver" renders only while the screen-test harness exposes its second login; it is not a product control | [Verified] | `ui/src/pages/Versions.tsx:6-7`, `:338-342` |

### 3.2 Messages each screen can show

| Situation | Text shown | Grade | Source |
|---|---|---|---|
| Business-key rule missing (API 400) | "The mandatory business-key rule is missing: businessKeyRule" - shown under the field, which is `aria-invalid` and described by it | [Verified] | `ui/src/pages/flow-lifecycle.ts:92`, `:103-105`; `ui/src/pages/FlowDefinition.tsx:349` |
| Source schema not a JSON object | "sourceSchema must be a JSON Schema object" - under the field, as above | [Verified] | `ui/src/pages/flow-lifecycle.ts:93`; `ui/src/pages/FlowDefinition.tsx:373` |
| Source system missing | "sourceSystem is required" - under the field, as above | [Verified] | `ui/src/pages/flow-lifecycle.ts:91`; `ui/src/pages/FlowDefinition.tsx:303` |
| Draft saved | "Draft saved." with a link to submit it for approval | [Verified] | `ui/src/pages/FlowDefinition.tsx:381` |
| Flow of another tenant, or absent (API 404) | "This flow does not exist for your tenant." - no hint of whose row it is | [Verified] | `ui/src/pages/FlowDefinition.tsx:83`, `:202`, `:276` |
| Author tries to approve own version (API 403) | "Refused: the author of a version cannot approve it themselves. Ask the listed approver to approve this version." with `AUTHOR_IS_APPROVER` under Details | [Verified] | `ui/src/pages/Versions.tsx:132`, `:302-310` |
| A submitted (or approved) version is selected | **Save draft** is not offered; the notice "A submitted version is immutable. A new draft is the only way to change it." and **New draft**. If the API still refuses an edit, the alert "The submitted version cannot be edited: a new draft version is the only way." replaces the notice (accepted behaviour, Dipesh 2026-10-15, row 310) | [Verified] | `ui/src/pages/flow-lifecycle.ts:8-13`; `ui/src/pages/Versions.tsx:294-298`, `:311-315` |
| Nothing pending approval (API 409) | "No version is pending approval." | [Verified] | `ui/src/pages/Versions.tsx:133`, `:316-320` |
| Any other refusal | "The API refused this action (...)." | [Verified] | `ui/src/pages/Versions.tsx:326-330` |
| Published | "Published." and whether the published bundle equals the snapshot taken at submit | [Verified] | `ui/src/pages/Versions.tsx:331-336` |

### 3.3 What a restyle must keep

These are functional constraints, not visual rules; they follow from "no functional change" and
"the full suite re-passes" (`ws/_specs/project-plan.md:129`).

| Statement | Grade | Source |
|---|---|---|
| The screen tests and the page-load measurement find elements by `data-*` attributes: `data-nav`, `data-page`, `data-field`, `data-action`, `data-saved`, `data-validation-error`, `data-refusal`, `data-version-status`, `data-published`, `data-bundle-equals-snapshot`, `data-flow-row` | [Verified] | `ui/tests/screens/flow-definition.docker.spec.mjs:22-385`; `ui/tests/screens/lifecycle.docker.spec.mjs:22-279`; `ui/tests/perf/pages.json:4-6` |
| A restyle therefore keeps every one of those attributes, and every visible text a test asserts, unchanged | [Inferred] | the row above plus `ws/_specs/project-plan.md:129` |
| Every API refusal stays visible, never hidden, and the not-found text keeps giving no hint of another tenant's data | [Verified] | `ui/src/pages/Versions.tsx:4-5`; `ui/src/pages/FlowDefinition.tsx:277-278` |
| Hash routes stay, so every admin screen remains directly loadable for the page-load figure | [Verified] | `ui/src/App.tsx:1-2`; `ui/tests/perf/pages.json:2` |
| Figure: admin page load p95 under 1.5 s | [Verified] | `PRD.md:158`; `ws/_specs/project-plan.md:105` |
| Last measured 117 / 103 / 120 ms (home, flow definition, versions) by the E3 judge on 2026-10-07; not re-run for this file | [Inferred] | m-a16de7 (hub workspace `_memory/shared.md` by entry id - the file rotates, so no line ref survives) |

### 3.4 Accessibility today (the bar is checked at E19)

| Statement | Grade | Source |
|---|---|---|
| Every input, select and text area sits inside its own `<label>`, so each has an accessible name | [Verified] | `ui/src/pages/FlowDefinition.tsx:292-374`; `ui/src/pages/Versions.tsx:260-263` |
| All actions are native `<button>` elements, so they are reachable by keyboard | [Inferred] | native element behaviour; `ui/src/App.tsx:224-263`, `ui/src/pages/Versions.tsx:268-291`, `:341` |
| Accessibility markup exists in `ui/src` - measured 2026-10-16: `aria-` on 72 lines (76 occurrences, 20 files), `role=` on 39 lines (39 occurrences, 18 files) (the E3-era "0 hits" note was stale; the T88 redesign and B13-B16 work added it). Refusals carry `role="alert"`, saved messages and loading notices `role="status"` or `aria-live="polite"`, so a screen reader announces them when they appear | [Verified] | `grep -rn` of `ui/src` for `aria-` (72 lines) and `role=` (39 lines), `grep -ro` for the occurrences; e.g. `ui/src/pages/FlowDefinition.tsx:277`, `:284`, `:382`; `ui/src/pages/Versions.tsx:194`, `:303-322` |
| Colour contrast and visible focus are not measured in E3; they are measured on the restyled build | [Verified] | `ws/_specs/project-plan.md:129`; `ws/_specs/backlog.md:4448` |

## 4. Screens still to come (function first, look at E19)

| Screen | Phase | Grade | Source |
|---|---|---|---|
| Console login | Login and identity (FR-04) | [Verified] | `PRD.md:107` |
| Systems and contract registry | FR-05 | [Verified] | `PRD.md:108` |
| Validation and conditional rules (decision tables) | FR-06 | [Verified] | `PRD.md:109` |
| Mapping designer - the product's core screen, on the React Flow canvas | FR-07; ADR-004 | [Verified] | `PRD.md:110`; [ADR-004](adr/ADR-004-mapping-canvas.md) |
| Tracking, stage timings, daily report | FR-13 | [Verified] | `PRD.md:116` |
| Queue dashboard | FR-17 (Release 2) | [Verified] | `PRD.md:120` |
| Each is built functional and unstyled like the E3 screens, and restyled only at E19 | [Inferred] | rule R20; `ws/_specs/project-plan.md:98` |

## 5. Diagrams

| Diagram | Why it matters here |
|---|---|
| [Configuration workflow](diagrams/workflow.archify.html) | The user journey the screens serve: log in, register systems, contract versions, interface and rules, mapping, submit, approve, publish, monitor and replay |
| [Architecture](diagrams/architecture.archify.html) | Where `ui/` sits: static console calling only the admin API |
| [Diagram index](diagrams/README.md) | All five diagrams with their sources |

This file adds no module, flow or state, so no diagram changes with it (rule R21). [Inferred]

## 6. Search for design C, 2026-10-07

| Where | Result | Grade |
|---|---|---|
| Hub repository, every tracked text file (`git grep` for "design C", "API-design-tool", "schema-editor screenshot", "direction C") | Only the memory and ledger lines cited in section 2 - none describes the look | [Verified] |
| Project memory (`ws/_memory/`) and ledger row 276 | The lines cited in section 2 - none describes the look | [Verified] |
| Project workspace (`ws/`) | No `_mockups/` folder, no image, no token file | [Verified] |
| This repository (`documents/`, `ui/`) | No earlier design document, no stylesheet; images are never committed here (hub rule) | [Verified] |
| Scratch screenshot folder named in memory (m-a5cf0d) | The folder no longer exists | [Verified] |

## Changing this file

When Dipesh answers TBD-1 or TBD-2, the answer replaces the TBD line in the same commit, with his
words (or the confirmed screenshot description) as the source, and the grades of section 2 move to
[Verified]. A visual rule enters this file only with a source.
