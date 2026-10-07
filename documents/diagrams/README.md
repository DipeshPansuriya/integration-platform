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

Five diagrams for the build, each an archify spec (`*.json`, the source) and its rendered,
self-contained page (`*.archify.html`, open it in a browser). Every box and arrow traces to
[architecture.md](../architecture.md), [PRD.md](../PRD.md) or the ADRs named below; nothing is
added beyond them. Edit the spec, then re-render the page in the same change.

**One screen each.** Every page fits a 1440x900 screen with no scrolling (checked at 1440x900,
1600x1000, 1920x1080 and 2048x1320). The end-to-end flow is folded into two pages - part 1
(intake and routing) and part 2 (deliver and recover) - so each stays readable on one screen.
Use the zoom controls for small labels.

| Diagram | What it shows | Sources | Spec |
|---|---|---|---|
| [Architecture](architecture.archify.html) | The modules of the one deployable, PostgreSQL with RLS, Kafka, Keycloak, the console, source and target systems, observability, and the build-time arch-test (ArchUnit) | architecture.md sections 2, 3, 6, 7; rule.md R08, R13; ADR-001, 005, 007, 009, 010, 011 | [architecture.architecture.json](architecture.architecture.json) |
| [End to end, part 1 - intake and routing](end-to-end-flow.archify.html) | Token, client bootstrap and body customerId cross-check (401/403 refusals), intake transaction with idempotency key, relay in order per key bucket, idempotent stage 1, poison and consumer pause, the synchronous variant | architecture.md sections 4.1 steps 1-8, 4.2; ADR-005, 007, 010, 011 | [end-to-end-flow.sequence.json](end-to-end-flow.sequence.json) |
| [End to end, part 2 - deliver and recover](end-to-end-delivery.archify.html) | Claim on the partition thread, per-target bulkhead, send with the six controls, claim token, retry job, reconciler and stale claims | architecture.md section 4.1 steps 9-14; ADR-010 sections 2, 6-9 | [end-to-end-delivery.sequence.json](end-to-end-delivery.sequence.json) |
| [Configuration workflow](workflow.archify.html) | What an end user does: log in, register systems, contract versions, interface and rules, mapping, submit, approve, publish, monitor and replay | PRD sections 1, 3, 5 (FR-03 to FR-07, FR-13, FR-15, FR-17); architecture.md section 4.3; ADR-011 Decision 3 | [workflow.workflow.json](workflow.workflow.json) |
| [Delivery states](delivery-state.archify.html) | The Delivery state machine with every transition of ADR-010 section 6: claim from PENDING or DUE, hold from PENDING or DUE, release, success, retryable or stale claim to RETRY_WAIT, bulkhead full from PENDING or DUE, re-drive, permanent or stale claim to DEAD_LETTERED, replay | ADR-010 sections 5-7; ADR-011 Decision 6 | [delivery-state.lifecycle.json](delivery-state.lifecycle.json) |

Re-render from the hub (one line per diagram):

```bash
node .claude/skills/archify/bin/archify.mjs deliver <type> <spec.json> <name>.archify.html --quality showcase --json
```

Visual checks (`archify visual-check`) write screenshots beside the page they check: run them on
a copy outside this repository - images are never committed here.
