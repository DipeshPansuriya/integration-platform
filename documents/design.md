---
type: Design
title: "Design - the console's look and feel (configuration-driven integration platform)"
status: "Recreated 2026-10-07 (E3, T-095). Reference design C has no surviving description in words - two TBD lines wait for Dipesh. The console as built in E3 is described from the code. The redesign itself is the last phase (Release 2). 2026-10-16 (ledger row 325): section 5 lists the six diagrams, including the console UI shell."
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
> (`ws/_specs/project-plan.md`, "Dependencies and inputs", provenance row `design.md`; searched again 2026-10-07 - section 6). This file therefore
> holds **no visual rule** - no colour, font, spacing, layout or component style - that is not
> sourced or marked TBD (`ws/_specs/backlog.md`, E3-F3-S2, @negative scenario "No invented design"). What it does hold:
> where design C stands and what only Dipesh can supply (section 2), and the console as it really
> is after E3, the baseline the redesign restyles (sections 3-4).

**Grades.** Every statement carries one: **[Verified]** - read this session in the file cited;
**[Inferred]** - deduced from the neighbour named; **[TBD]** - a fact only Dipesh holds, one line
naming the question. Source paths starting `ws/` are in the planning workspace kept outside this
repository (see [README.md](README.md)); all others are in this repository. Workspace files
rotate, so a `ws/` source is cited by section, story or task id, or memory entry id (`m-xxxxxx`) -
never by line number; sources in this repository keep `path:line`. Rule R19 applies: no product or organisation name
appears here, so the design skill is named only by where it is recorded.

## 1. What this document is for, and when the redesign happens

| Statement | Grade | Source |
|---|---|---|
| The look-and-feel redesign comes **last**, after all functionality: it is plan phase 19, the end of Release 2, and starts only after the full end-to-end suite passes | [Verified] | `ws/_specs/project-plan.md`, "Size and estimate" (Release 2 of the proposed split) and "Phases" (build-order note; phase "Look-and-feel redesign"); `ws/_specs/backlog.md`, E19 epic header; PRD FR-19 (`PRD.md:122`); rule R20 (`rule.md:54`) |
| The redesign makes **no functional change**: the full suite must re-pass on the restyled build, and Dipesh reviews it screen by screen | [Verified] | `ws/_specs/project-plan.md`, phase "Look-and-feel redesign"; `ws/_specs/backlog.md`, E19-F1-S1 |
| Every restyled screen must pass **WCAG AA contrast** and work **by keyboard with a visible focus** | [Verified] | `ws/_specs/project-plan.md`, phase "Look-and-feel redesign"; `ws/_specs/backlog.md`, E19-F1-S2 |
| E3 needs this file only to (a) record design C in words or a TBD line for Dipesh, and (b) stay free of invented design. It does not restyle anything | [Verified] | `ws/_specs/backlog.md`, E3-F3-S2 (task T-095) and E3-C10 ("Traceability - E3"); `ws/_specs/project-plan.md`, phase "Flow configuration: source to destination" |
| The redesign task (T-154) restyles every screen "to design.md", so this file is its single reference | [Verified] | `ws/_specs/backlog.md`, E19-F1-S1 (scenario "Restyled to design.md", task T-154) and E19-C1 |
| UI work, the redesign included, uses the design skill named on the project's Skills line in the hub | [Verified] | rule R20 (`rule.md:54`); `ws/_memory/overview.md`, **Skills:** line; m-2446fd (hub workspace `ws/_memory/shared.md` by entry id - the file rotates, so no line ref survives; Dipesh 2026-10-01, row 268) |

## 2. Reference design C

| Statement | Grade | Source |
|---|---|---|
| Design C is the agreed reference for the redesign (ledger row 276) | [Verified] | `ws/_specs/project-plan.md`, phase "Look-and-feel redesign"; `ws/_specs/backlog.md`, E19-F1-S1 and E19-C1 |
| Its origin: on 2026-10-02 Dipesh rejected both directions the team had built (A and B) as "not like new modern design", and the team asked him for a named visual reference instead of adjectives | [Verified] | m-b5697d (`ws/_memory/shared.md`) |
| "C" is the third direction, the one to be reworked only from his reference | [Inferred] | m-b5697d: "Direction C rework only after his reference" |
| What he sent: a **screenshot of a schema editor in an API-design tool**, as his modern reference | [Verified] (as recorded) | m-dbda93, m-5916ad, m-13338f (`ws/_memory/shared.md`); ledger row 276 |
| The screenshot was kept **in that chat session only** - no file, and no description of it in words, was ever written | [Verified] | m-5916ad ("saved in this chat only"); `ws/_specs/project-plan.md`, "Dependencies and inputs", provenance row `design.md`; search of 2026-10-07 (section 6) |
| Directions A and B are **not** the reference; their branches (`dir-a`, `dir-b`, cut from base 5b01d68) lived in the repository deleted on 2026-10-02, and no branch or commit of them exists here | [Verified] | branch names and base: m-c31f0a, m-c73a0d (`ws/_memory/shared.md`); deletion on 2026-10-02: `ws/_specs/project-plan.md`, front-matter description; measured in this repository on 2026-10-16: `git branch -a \| grep -ciE 'dir-a\|dir-b\|row276\|direction'` = 0, `git cat-file -t 5b01d68` and `git cat-file -t 504c9ec` (dir-a tip, m-c73a0d) both "Not a valid object name" |
| His words on colour (row 276): he asked for a modern design and said that for "color and such thing" the design skill can be used | [Verified] (paraphrased: the original names the skill) | ledger row 276, request column |
| Visual rules in force today (colour, type, spacing, layout, components): the **token layer** - `ui/src/tokens.css` (every colour a light and dark pair; e.g. `--color-accent: #2563eb` at `:25`, `--topbar-h: 48px` at `:66`) and the base primitives and app shell in `ui/src/ui/base.css`, both added in ac2f52c on 2026-10-14 ("T78 lane F: token layer + app shell"); the shell is redesigned on that layer (02-style-guide v3). Reference design C itself is still undescribed (TBD-1) | [Verified] | `ui/src/tokens.css:1-6`, `:25`, `:66`; `ui/src/ui/base.css:1-4`; `ui/src/App.tsx:10-13`; `git log --diff-filter=A --format='%h %ad' --date=short main -- ui/src/tokens.css ui/src/ui/base.css` = ac2f52c 2026-10-14 |
| Styling tokens: **recorded** in the hub workspace - `ws/_specs/ui-modernization/01-tokens.md` (section 1, colour tokens for both themes; its header: palette fixed by Dipesh on 2026-10-13, slate with a blue accent) with the execution values in `ws/_specs/ui-modernization/02-style-guide.html` (v3); both added in hub commit 26fd0c4c on 2026-10-13. The workspace now holds `_diagrams`, `_documents`, `_memory`, `_reports` and `_specs` (`_diagrams` first committed 2026-10-15, hub 792f1ec2); there is still no `_mockups/` folder | [Verified] | `ws/_specs/ui-modernization/01-tokens.md`, header and section 1; hub `git log --diff-filter=A --format='%h %ad' --date=short -- <01-tokens.md> <02-style-guide.html>` = 26fd0c4c 2026-10-13; listing of `ws/` on 2026-10-16; hub `git log --diff-filter=A --format='%h %ad' --date=short -- <ws>/_diagrams \| tail -1` = 792f1ec2 2026-10-15 |

**TBD-1 - Dipesh: what does reference design C look like?** Please describe it in words, or send the
schema-editor screenshot again so it can be written down here and you confirm the wording in one
reply. The description needs: navigation placement (top bar or side bar), page layout and
density, colour (background, one accent or several), type, and how the schema editor shows a
field (tree, table or form; type, required flag, description). *Recommendation:* send the
screenshot again when Release 2's redesign phase starts (E19); mahesh-architect writes the
description with each point traced to the screenshot, and you confirm it before T-154 begins.
This does not block Release 1 (`ws/_specs/project-plan.md`, "Dependencies and inputs", "Facts only Dipesh or the client holds"). [TBD]

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
| **Styling is applied** - measured on main f34767a, 2026-10-16: 9 stylesheet files in `ui/src` (`tokens.css`, `ui/base.css`, `ui/drawer.css`, `pages/dashboard.css`, `pages/flows.css`, `pages/contracts/contracts.css`, `pages/systems/systems.css`, `rules/rules-editor.css`, `mapping/mapping.css`), `className` on 278 lines (22 files) and an inline `style` on 2 lines of `ui/src` (the E3-era "no stylesheet, 0 hits" note was stale) | [Verified] | `git ls-tree -r --name-only main -- ui \| grep -c '\.css$'` = 9; `git grep -n className main -- ui/src \| wc -l` = 278; `git grep -nE 'style=' main -- ui/src \| wc -l` = 2 |
| The code comments state that nothing beyond the scaffold is invented, and this file governs the look | [Verified] | `ui/src/App.tsx:4-5`; `ui/src/pages/FlowDefinition.tsx:5` |
| Browser title "Integration Platform Console"; page language English | [Verified] | `ui/index.html:2`, `:6` |

### 3.1 Screens

| Screen | Route | What it shows | Grade | Source |
|---|---|---|---|---|
| Shell | every route | A persistent left-side nav of grouped real links - Overview (Dashboard, Queue), Build (Flows, Versions, Systems, Contracts), Author (Rules, Mappings); a 48px top bar; hash routing so every screen loads directly by URL | [Verified] | `ui/src/App.tsx:65-89`, `:331-353`; hash-route comment `:1-2`; 48px top bar `ui/src/App.tsx:12`, `:282` and `ui/src/tokens.css:66` |
| Home | `#/` | The signed-in Dashboard (heading "Dashboard"; "Checking your session..." while the session check runs) | [Verified] | `ui/src/App.tsx:371-372`; `ui/src/pages/Dashboard.tsx:106` |
| Flow definition (T-017, E3-F1-S1; T88 redesign) | `#/flows` (list landing), `#/flow/{id}`, `#/flow/new` | Heading "Flow definition"; a two-pane form with a sticky action bar (T88); fields Source system, destinations as configuration rows (two at first, addable and removable), Interface name, Business-key rule (mandatory), Ordering (`PER_KEY` default or `NONE`), Source schema (JSON Schema, a text area); a **Save draft** submit button | [Verified] | `ui/src/pages/FlowDefinition.tsx:6-8` (2-pane + routes), `:249` (heading), `:292-374` (fields), `:387-388` (Save draft) |
| Versions and approval (T-022, E3-F2-S1; T88 redesign) | `#/versions` | Heading "Versions and approval"; a table of flows with each version status (the selected row is marked for assistive tech with `aria-current` plus a hidden "(selected)" text, and shown bold with an accent bar); a lifecycle panel that offers only the actions valid for the selected status - `DRAFT`: **Save draft**, **Submit for approval**; `SUBMITTED`: **Approve**, **New draft**; `APPROVED`: **Publish**, **New draft**; `PUBLISHED`: **New draft**. A submitted version shows no **Save draft** at all | [Verified] | `ui/src/pages/flow-lifecycle.ts:8-13`; `ui/src/pages/Versions.tsx:222-237`, `:266-291` |
| Versions - test-harness only | `#/versions` | "Approve as the harness approver" renders only while the screen-test harness exposes its second login; it is not a product control | [Verified] | `ui/src/pages/Versions.tsx:6-7`, `:338-342` |

### 3.2 Messages each screen can show

| Situation | Text shown | Grade | Source |
|---|---|---|---|
| Business-key rule missing (API 400) | "The mandatory business-key rule is missing: businessKeyRule" - shown under the field, which is `aria-invalid` and described by it | [Verified] | `ui/src/pages/flow-lifecycle.ts:92`, `:103-105`; `ui/src/pages/FlowDefinition.tsx:344-351` |
| Source schema not a JSON object | "sourceSchema must be a JSON Schema object" - under the field, as above | [Verified] | `ui/src/pages/flow-lifecycle.ts:93`; `ui/src/pages/FlowDefinition.tsx:367-375` |
| Source system missing | "sourceSystem is required" - under the field, as above | [Verified] | `ui/src/pages/flow-lifecycle.ts:91`; `ui/src/pages/FlowDefinition.tsx:298-305` |
| Draft saved | "Draft saved." with a link to submit it for approval | [Verified] | `ui/src/pages/FlowDefinition.tsx:381-383` |
| Flow of another tenant, or absent (API 404) | "This flow does not exist for your tenant." - no hint of whose row it is | [Verified] | `ui/src/pages/FlowDefinition.tsx:83`, `:202`, `:276` |
| Author approves own version | No refusal: the version becomes APPROVED and Publish is offered (single-user workflow, `V2_4_1420`; the old `AUTHOR_IS_APPROVER` alert is removed) | [Verified] | `ui/src/pages/Versions.tsx` (`onApprove`) |
| A submitted (or approved) version is selected | **Save draft** is not offered; the notice "A submitted version is immutable. A new draft is the only way to change it." and **New draft**. If the API still refuses an edit, the alert "The submitted version cannot be edited: a new draft version is the only way." replaces the notice (accepted behaviour, Dipesh 2026-10-15, row 310) | [Verified] | `ui/src/pages/flow-lifecycle.ts:8-13`; `ui/src/pages/Versions.tsx:294-298`, `:311-315` |
| Nothing pending approval (API 409) | "No version is pending approval." | [Verified] | `ui/src/pages/Versions.tsx:133`, `:316-320` |
| Any other refusal | "The API refused this action (...)." | [Verified] | `ui/src/pages/Versions.tsx:326-330` |
| Published | "Published." and whether the published bundle equals the snapshot taken at submit | [Verified] | `ui/src/pages/Versions.tsx:331-336` |

### 3.3 What a restyle must keep

These are functional constraints, not visual rules; they follow from "no functional change" and
"the full suite re-passes" (`ws/_specs/project-plan.md`, phase "Look-and-feel redesign").

| Statement | Grade | Source |
|---|---|---|
| The screen tests and the page-load measurement find elements by `data-*` attributes. The rule, kept generic so it cannot go stale: **every `data-*` attribute used in `e2e-ui/tests/screens/*.spec.mjs` and `e2e-ui/tests/perf/pages.json`**. Measured on main 8c6f193, 2026-10-16: 66 distinct attributes across the 12 screen specs and the 6 measured pages (e.g. `data-nav`, `data-page`, `data-action`, `data-systems-loaded`, `data-contracts-loaded`, `data-mapping-loaded`, `data-queue-loaded`, `data-system-row`, `data-decision-table`, `data-session-tenant`, `data-test-panel-run`); the first command in the source column lists them all | [Verified] | `git grep -ohE 'data-[a-z][a-z0-9-]*' main -- 'e2e-ui/tests/screens/*.spec.mjs' e2e-ui/tests/perf/pages.json \| sort -u` (66 lines with `\| wc -l`); `git ls-tree --name-only main e2e-ui/tests/screens/ \| grep -c '\.spec\.mjs$'` = 12; `e2e-ui/tests/perf/pages.json:4-9` |
| A restyle therefore keeps every attribute that command lists, and every visible text a test asserts, unchanged | [Inferred] | the row above plus `ws/_specs/project-plan.md`, phase "Look-and-feel redesign" |
| Every API refusal stays visible, never hidden, and the not-found text keeps giving no hint of another tenant's data | [Verified] | `ui/src/pages/Versions.tsx:4-5`; `ui/src/pages/FlowDefinition.tsx:277-278` |
| Hash routes stay, so every admin screen remains directly loadable for the page-load figure | [Verified] | `ui/src/App.tsx:1-2`; `e2e-ui/tests/perf/pages.json:2` |
| Figure: admin page load p95 under 1.5 s | [Verified] | `PRD.md:158`; `ws/_specs/project-plan.md`, phase "Flow configuration: source to destination" (figure owned) and the "Figures by phase" table |
| Last measured 117 / 103 / 120 ms (home, flow definition, versions) by the E3 judge on 2026-10-07; not re-run for this file | [Inferred] | m-a16de7 (hub workspace `_memory/shared.md` by entry id - the file rotates, so no line ref survives) |

### 3.4 Accessibility today (the bar is checked at E19)

| Statement | Grade | Source |
|---|---|---|
| Every input, select and text area sits inside its own `<label>`, so each has an accessible name | [Verified] | `ui/src/pages/FlowDefinition.tsx:292-374`; `ui/src/pages/Versions.tsx:260-263` |
| Actions are reachable by keyboard: most are native `<button>` elements (on 42 lines of `ui/src`); some are native links with an `href` (`data-action` values `back-to-flows`, `define-flow`, `open-mapping`) and one is a native `<summary>` (the account menu, `data-action="account"`), all focusable by default; the flow rows of the Versions table are made focusable (`tabIndex={0}`) and select on click, Enter or Space | [Verified] for the markup; [Inferred] for the keyboard reach of native elements (native element behaviour) | `git grep -c '<button' main -- ui/src` summed = 42; `ui/src/App.tsx:283-322` (`<summary>` at `:320`); `ui/src/pages/FlowDefinition.tsx:251`; `ui/src/pages/FlowsList.tsx:40`; `ui/src/mapping/MappingRoutes.tsx:109`; `ui/src/pages/Versions.tsx:181-182`, `:224-230`, `:268-291`, `:341` |
| Accessibility markup exists in `ui/src` - measured 2026-10-16: `aria-` on 72 lines (76 occurrences, 20 files), `role=` on 39 lines (39 occurrences, 18 files) (the E3-era "0 hits" note was stale; the T88 redesign and B13-B16 work added it). Refusals carry `role="alert"`, saved messages and loading notices `role="status"` or `aria-live="polite"`, so a screen reader announces them when they appear | [Verified] | `grep -rn` of `ui/src` for `aria-` (72 lines) and `role=` (39 lines), `grep -ro` for the occurrences; e.g. `ui/src/pages/FlowDefinition.tsx:277`, `:284`, `:382`; `ui/src/pages/Versions.tsx:194`, `:303-322` |
| Colour contrast and visible focus are not measured in E3; they are measured on the restyled build | [Verified] | `ws/_specs/project-plan.md`, phase "Look-and-feel redesign"; `ws/_specs/backlog.md`, E19-F1-S2 |

## 4. Screens built after E3, and what is still to come

Measured on main 8c6f193, 2026-10-16. The shell parses each route at `ui/src/App.tsx:91-109` and
renders its page at `ui/src/App.tsx:355-372`; every built screen has its own spec in `e2e-ui/tests/screens/`
(`git ls-tree --name-only main e2e-ui/tests/screens/`).

| Screen | Route | Requirement | Page file and shell wiring | Screen spec | Grade |
|---|---|---|---|---|---|
| Console login (sign-in card) | `#/login` | FR-04 (`PRD.md:107`) | `ui/src/pages/Login.tsx:28` (heading "Sign in"); `ui/src/App.tsx:93`, `:278` | `login.docker.spec.mjs` | [Verified] |
| Systems registry | `#/systems` | FR-05 (`PRD.md:108`) | `ui/src/pages/Systems.tsx:110` (heading "Systems"); `ui/src/App.tsx:98`, `:362` | `systems.docker.spec.mjs` | [Verified] |
| Contract versions | `#/contracts` | FR-05 (`PRD.md:108`) | `ui/src/pages/ContractVersions.tsx:95` (heading "Contract versions"); `ui/src/App.tsx:100`, `:364` | `contracts.docker.spec.mjs` | [Verified] |
| Validation and conditional rules (decision tables) | `#/rules`, `#/rules/{ruleSetId}` | FR-06 (`PRD.md:109`) | `ui/src/rules/RulesEditor.tsx:68` (heading "Rules"); decision table `ui/src/rules/jdm/JdmDecisionTable.tsx:16-18`; `ui/src/App.tsx:40` (loaded on first use), `:101-103`, `:365-369` | `rules-editor.docker.spec.mjs`, `rules-parity.docker.spec.mjs` | [Verified] |
| Mapping designer - the product's core screen, on the React Flow canvas | `#/mappings`, `#/mappings/{routeId}` | FR-07 (`PRD.md:110`); [ADR-004](adr/ADR-004-mapping-canvas.md) | `ui/src/pages/MappingDesigner.tsx:266` (heading "Mapping designer"); canvas `ui/src/mapping/MappingCanvas.tsx:12-14` (`@xyflow/react`); `ui/src/App.tsx:105-107`, `:370` | `mapping-designer.docker.spec.mjs`, `raw-expression.docker.spec.mjs` | [Verified] |
| Queue dashboard | `#/queue` | FR-17 (`PRD.md:120`); Release 2 in the plan's split (`ws/_specs/project-plan.md`, "Size and estimate"), built early as a narrow slice (`ui/src/App.tsx:27-28`) | `ui/src/pages/QueueDashboard.tsx:42` (heading "Queue"); `ui/src/App.tsx:99`, `:363` | `queue-dashboard.docker.spec.mjs` | [Verified] |
| **Still to come:** tracking, stage timings and daily report | none yet | FR-13 (`PRD.md:116`) | no page and no route: `git grep -niE 'tracking\|stage tim\|daily report' main -- ui/src` = 0 hits; the route list `ui/src/App.tsx:43` has no such page | none | [Verified] |

These screens were built functional first and already carry their own stylesheets (among the 9
counted in section 3: `pages/systems/systems.css`, `pages/contracts/contracts.css`,
`rules/rules-editor.css`, `mapping/mapping.css`); the formal look-and-feel phase (E19) still closes
Release 2 and restyles every screen to this file (rule R20; `ws/_specs/project-plan.md`, "Phases",
build-order note). [Verified]

## 5. Diagrams

| Diagram | Why it matters here |
|---|---|
| [Configuration workflow](diagrams/workflow.archify.html) | The user journey the screens serve: log in, register systems, contract versions, interface and rules, mapping, submit, approve, publish, monitor and replay |
| [Architecture](diagrams/architecture.archify.html) | Where `ui/` sits: static console calling only the admin API |
| [Console UI shell](diagrams/ui-modernized-shell.archify.html) | The shell of section 3.1 and the screens of section 4: menu groups, `#/login`, the session calls to `platform-app` and the redirect to the identity provider's sign-in page |
| [Diagram index](diagrams/README.md) | All six diagrams, drawn against main `90c1ddd`, with their status words |

This file adds no module, flow or state, so no diagram changes with it (rule R21). [Inferred]

## 6. Search for design C, 2026-10-07

| Where | Result | Grade |
|---|---|---|
| Hub repository, every tracked text file (`git grep` for "design C", "API-design-tool", "schema-editor screenshot", "direction C") | Only the memory and ledger lines cited in section 2 - none describes the look | [Verified] |
| Project memory (`ws/_memory/`) and ledger row 276 | The lines cited in section 2 - none describes the look | [Verified] |
| Project workspace (`ws/`) | No `_mockups/` folder, no image, no token file | [Verified] |
| This repository (`documents/`, `ui/`) | No earlier design document, no stylesheet (true on 2026-10-07; stylesheets arrived later - section 3, 9 files on 2026-10-16); images are never committed here | [Verified] |
| Scratch screenshot folder named in memory (m-a5cf0d) | The folder no longer exists | [Verified] |

## Changing this file

When Dipesh answers TBD-1 or TBD-2, the answer replaces the TBD line in the same commit, with his
words (or the confirmed screenshot description) as the source, and the grades of section 2 move to
[Verified]. A visual rule enters this file only with a source.
