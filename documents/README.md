---
type: Index
title: "Documents - configuration-driven integration platform"
date: 2026-10-02
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
| [adr/README.md](adr/README.md) | The ADR index |

Recreated in the phase that first needs them: `fetch.md` and `interface.md` (systems and
contract registry phase); `design.md` was recreated in the flow configuration phase (E3, T-095).
