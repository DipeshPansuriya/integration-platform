---
type: ADR
title: "ADR-001 - One deployable, a modular monolith with enforced module boundaries"
status: "Accepted (team, 2026-10-02) - NEW DECISION, NOT A RECREATION - for Dipesh's re-confirmation"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0)"
project: ipass/integration-platform
provenance: new-decision
supersedes: none
related:
  - ADR-002-datastore.md
  - ADR-010-camel-routing-core.md
  - ../architecture.md
---

# ADR-001 - One deployable, a modular monolith with enforced module boundaries

> **New decision, not a recreation - for Dipesh's re-confirmation.** An ADR-001 was Accepted
> before the old repository was deleted on 2026-10-02, but no surviving hub file records its
> subject (`ws/_specs/project-plan.md:163`; vikram-cto's challenge, `ws/_memory/shared.md:19`,
> m-273754, point 2). This record therefore does not claim to restore it. It takes the number
> for the most basic structural decision the rebuild needs, using facts that did survive; Dipesh
> either confirms it, or names what the old ADR-001 decided and this one is renumbered.

Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`. Memory lines drift as entries are added; the
memory id (`m-xxxxxx`) is the stable key.

## Context

The platform has a control plane (people configure systems, contracts, flows, rules and
mappings) and a data plane (messages are accepted, routed, transformed and delivered). It must
be built by one development team of five, run self-hosted, and carry tenant isolation from the
first table.

Facts that survived the deletion and constrain the shape:

| Fact | Source |
|---|---|
| The deleted design named `platform-app/` "the only deployable" | `ws/_specs/f3-plan-brief.md:611` (quoting system-design.md:670) |
| The deleted design's rule: `config` is written only by the control plane and read only by the data plane | `ws/_specs/f3-plan-brief.md:512` (quoting architecture-and-patterns.md:71) |
| The module list of the deleted design: platform-domain, -tenancy, -config, -ingest, -routing, -transform, -delivery, -ledger, -audit, -app, ui/ | `ws/_specs/work-breakdown.md:16-19` |
| Base stack: Java 25, Spring Boot 4.1.1, Spring Modulith 2.1.1 | `ws/_specs/project-plan.md:71` |
| Camel must not leak outside the routing module (ArchUnit) | `ws/_specs/project-plan.md:78`, `:114` |
| Volume: about 50 messages per second at peak, payloads under 1 MB | `ws/_specs/project-plan.md:143` |

**Premise check - is there a load or team reason for separate services?** No. 50 messages per
second with a 10x burst is well inside one horizontally scaled JVM process; one team owns every
module; nothing needs an independent release cadence. The reasons that usually justify services
(independent scaling of one hot part, separate teams, separate release trains) are absent.

## Options

| Option | Complexity | Risk | Cost | Reversibility |
|---|---|---|---|---|
| **A. Modular monolith** - one Spring Boot deployable, Maven modules per bounded context, boundaries enforced by Spring Modulith verification and ArchUnit | Low | A boundary can erode if the rules are not build-breaking | Lowest: one build, one deploy, one database | High: a module with clean ports can be lifted into a service later |
| B. Microservices per bounded context | High: service-to-service calls, distributed transactions around the outbox, per-service tenancy wiring | Every in-process call becomes a network call with the five resilience controls | High: N deployables, N pipelines | Low once data is split |
| C. Unstructured single application | Lowest at first | Boundaries erode; tenant filter and Camel containment cannot be enforced per module | Low now, high later | Medium |

## Decision

**Option A.** One deployable (`platform-app`), several horizontally scaled instances behind a
load balancer, no local state in any instance. Maven modules per bounded context; the module
list is fixed in [architecture.md](../architecture.md). Module boundaries are build-breaking:

1. Spring Modulith's module verification test runs in the non-container build and fails on a
   cycle or on access to another module's internal package.
2. ArchUnit rules fail the build when: `org.apache.camel` is used outside `platform-routing`
   (ADR-010); a repository or raw JDBC call lacks the tenant filter (every module's package in
   scope); `platform-config` is written from a data-plane module.
3. The control plane writes configuration; the data plane only reads published, immutable
   versions of it.
4. The UI (`ui/`, React) is a separate front-end build served as static files; it talks only to
   the platform's admin API.

## Consequences

- One database transaction can cover message, outbox and delivery rows - the transactional
  outbox (phase 2) needs no distributed transaction.
- Scaling is whole-process: a hot module cannot be scaled alone. Accepted at the stated volume.
- A defect in one module can take the process down for all modules; instances are redundant and
  the outbox keeps accepted work safe across a crash.
- The build verifies boundaries; a boundary is only as good as the rule that tests it, so each
  rule carries a test that proves it fails on a planted violation.

**What we give up:** independent deployment and scaling per bounded context, and technology
choice per module (every module is Java 25 / Spring Boot 4.1.1).

## Revisit if

- A single module needs more than 4 instances while the others need 1 (measured, sustained for
  a month).
- A second team takes ownership of one bounded context with its own release cadence.
- Sustained load passes 10 times the stated 50 messages per second.
