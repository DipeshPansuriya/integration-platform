---
type: Index
title: "Documents - configuration-driven integration platform"
date: 2026-10-02
updated: 2026-10-16
project: ipass/integration-platform
---

# Documents

Design documents and architecture decision records (ADRs) for the rebuild. The previous set was
deleted together with the old repository on 2026-10-02; phase 0 of the plan recreates it with
provenance - each section names the surviving source it came from, or is labelled a new decision.

| Document | What it holds |
|---|---|
| [PRD.md](PRD.md) | Purpose, the worked example, roles, requirements with acceptance criteria, figures, out of scope |
| [architecture.md](architecture.md) | Modules, data model, the message path with failure paths, tenant isolation controls, deployment |
| [rule.md](rule.md) | Engineering rules, including the lessons measured on the deleted build |
| [design.md](design.md) | The console's look and feel: reference design C (TBD for Dipesh), the console as built, what a restyle must keep |
| [fetch.md](fetch.md) | Data and integration: systems registry, credentials, test-connection and the destination check, versioned contracts, target types, what crosses Kafka |
| [interface.md](interface.md) | The common API (ADR-011 Decision 4, built in E11) and the admin API as built |
| [adr/README.md](adr/README.md) | The ADR index, with each ADR's status against the code |
| [HANDOVER.md](HANDOVER.md) | One-document handover for a new team and its AI agents: scope, Release 1 and 2 state, end to end, architecture, how to run, test and work, open items |
| [diagrams/README.md](diagrams/README.md) | Six archify diagrams (architecture, end to end in two parts, configuration workflow, delivery states, console UI shell) drawn against the code |

Recreated in the phase that first needs them: `fetch.md` and `interface.md` were recreated in the
systems and contract registry phase (E5, T-107); `design.md` in the flow configuration phase (E3, T-095).

## Status of this set

**Baseline: code main `90c1ddd` (2026-10-16), ledger row 325.** The documents describe the **target** design; where
the code does not match it yet, a dated *Status* note says so, with a `path:line` behind it - see
[architecture.md](architecture.md) section 9 for the whole picture and [PRD.md](PRD.md) section 10 per requirement.
In short: the running application accepts a message and stores it with its outbox row; relay, routing, send and
retry are built and tested but not connected yet.

## How to read the references

These documents were written by a planning team, and some references point outside this repository:

| You see | It means |
|---|---|
| `ws/...` paths (for example `ws/_specs/project-plan.md:110`) | The planning workspace kept outside this repository: the approved plan, backlog and project memory. Ask the project owner for an extract if you need one |
| `m-xxxxxx` | A project-memory entry id in that workspace (stable; its line number is not) |
| "ledger row NNN" | The request log of the planning team; it dates and traces a change, it is not a requirement |
| E1-E19, T-NNN, F/S ids | Plan phases (epics), tasks and stories of the backlog |
| `mahesh-architect`, `arjun-fullstack`, `kavita-release-manager`, `vikram-cto` in author or source lines | Planning-team roles: the architect, the development lead, the quality and release lead, and the technical reviewer who challenges designs |
| "Dipesh" | The project owner, who approves the plan and decides open questions |
| [Verified] / [Inferred] / [TBD] | Read in the cited file / deduced from the neighbour named / not decided or not built yet |
