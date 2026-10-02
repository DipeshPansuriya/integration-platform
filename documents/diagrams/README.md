---
type: Index
title: "Planning diagrams - configuration-driven integration platform"
date: 2026-10-02
author: "mahesh-architect (ledger row 278)"
project: ipass/integration-platform
related:
  - ../architecture.md
  - ../PRD.md
  - ../adr/README.md
---

# Planning diagrams

Four diagrams for the build, each an archify spec (`*.json`, the source) and its rendered,
self-contained page (`*.archify.html`, open it in a browser). Every box and arrow traces to
[architecture.md](../architecture.md), [PRD.md](../PRD.md) or the ADRs named below; nothing is
added beyond them. Edit the spec, then re-render the page in the same change.

| Diagram | What it shows | Sources | Spec |
|---|---|---|---|
| [Architecture](architecture.archify.html) | The modules of the one deployable, PostgreSQL with RLS, Kafka, Keycloak, the console, source and target systems, observability | architecture.md sections 2, 6, 7; ADR-001, 005, 007, 009, 010, 011 | [architecture.architecture.json](architecture.architecture.json) |
| [End-to-end flow](end-to-end-flow.archify.html) | One message from token to delivery, with intake refusals, poison topic, ordering hold, retry layers, dead letter and the synchronous variant | architecture.md sections 4.1, 4.2; ADR-005, 010, 011 | [end-to-end-flow.sequence.json](end-to-end-flow.sequence.json) |
| [Configuration workflow](workflow.archify.html) | What an end user does: log in, register systems, contract versions, interface and rules, mapping, submit, approve, publish, monitor and replay | PRD sections 1, 3, 5 (FR-03 to FR-07, FR-13, FR-15, FR-17); architecture.md section 4.3; ADR-011 Decision 3 | [workflow.workflow.json](workflow.workflow.json) |
| [Delivery states](delivery-state.archify.html) | The Delivery state machine: PENDING, HELD, DUE, SENDING, DELIVERED, RETRY_WAIT, DEAD_LETTERED | ADR-010 sections 5-7; ADR-011 Decision 6 | [delivery-state.lifecycle.json](delivery-state.lifecycle.json) |

Re-render from the hub (one line per diagram):

```bash
node .claude/skills/archify/bin/archify.mjs deliver <type> <spec.json> <name>.archify.html --quality showcase --json
```
