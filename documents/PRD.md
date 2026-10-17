---
type: PRD
title: "Product requirements - configuration-driven integration platform"
status: "Recreated 2026-10-02 (phase 0) from surviving sources; scope and acceptance criteria as approved in the plan of 2026-10-02; build-status table added 2026-10-16 at main 90c1ddd in section 10 (ledger row 325), requirements unchanged"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0)"
project: ipass/integration-platform
provenance: recreation
related:
  - architecture.md
  - rule.md
  - adr/README.md
---

# Product requirements - configuration-driven integration platform

> **Recreation.** The earlier requirement documents were deleted with the old repository on
> 2026-10-02. This file is rebuilt from the approved plan and Dipesh's formal requirement
> (`ws/_specs/project-plan.md:158`, provenance table). Each section names its source. Text marked
> **Reading taken** or **Team default** is not in any source and waits for Dipesh's
> re-confirmation, as does the section "Changing a rule" in rule.md (labelled **Added in the
> recreation**). **The acceptance criteria are the plan's done-when, unchanged; where this file
> and the approved plan differ, the plan wins.**

Source paths starting `ws/` are in the planning workspace kept outside this repository (see
[README.md](README.md)). Memory lines drift; the id (`m-xxxxxx`) is the
stable key.

## 1. Purpose and premise

*Sources: `ws/_specs/project-plan.md:38-42`; `ws/_memory/shared.md:25` (m-703833);
`ws/_specs/f3-plan-brief.md:72-73` (quoting the deleted approach document :56 and README.md:45).*

A configuration-driven integration platform. An end user **logs in** and defines, through the
application: the source system and its interface; the interface's request and response
contract; one or more target systems for the same source interface; a **versioned contract per
target**; field mapping and transformation rules; validations and other integration settings. A
source system calls **one common API**, possibly with a **bulk** request. The platform identifies
customer, interface and applicable targets, applies each target's own contract and mapping, and
sends. **The source never knows the target formats.** A new interface, target or contract
version needs **no code change and no deploy**.

**The test of the premise:** can an administrator onboard a new target on a Friday afternoon
without a deployment? If any step needs a release, the platform is code-driven wearing a
configuration badge.

## 2. The worked example - the acceptance test of the whole idea

*Source: `ws/_specs/project-plan.md:40`, `:46`.*

Customer A sends `{customerId, orderId, amount}` once. Target A (contract v1.0) receives
`{customer_code, order_number, total_amount}`; Target B (contract v2.0) receives
`{clientId, referenceNo, invoiceValue}`. On a running system, one call from Customer A's
credential produces exactly 1 delivery to each target in its own contract's field names, and
both targets were configured through the application with 0 deploys and 0 migrations.

## 3. Users and roles

*Sources: `ws/_specs/project-plan.md:109`; ADR-007 Decision 5; `ws/_specs/project-plan.md:230`.*

| Role | Can |
|---|---|
| Tenant admin | Everything in the tenant, including users, roles, credentials; may approve |
| Configurator | Create and edit systems, contracts, flows, rules, mappings as drafts; submit for approval |
| Approver | Approve or reject submitted versions - never their own (enforced on the record) |
| Support | Read messages, deliveries and timelines; replay or discard dead letters (audited) |
| Read-only | Read configuration and tracking |
| Source system (machine) | Call the common API with its own credential |
| AI agent (machine) | List, run, pause and inspect scheduled jobs through the admin API |
| Platform operator | Onboards tenants (no self-service signup) |

`APPROVER` is a team addition to the plan's four human roles (ADR-007 Decision 5).

## 4. Vocabulary

*Source: `ws/_specs/f3-plan-brief.md:36` (the deleted data design's vocabulary), ADR-011.*

| Term | Meaning |
|---|---|
| Tenant | A platform account; every row belongs to exactly one |
| Customer | **Reading taken:** the party behind a registered source system; identified from the calling credential |
| System | A registered source or target system (type API, DB, MQ, File for targets) |
| Interface | A contracted exchange from a source; its code is in the API path and immutable once published |
| Contract, contract version | A schema (JSON Schema, optionally imported from OpenAPI) and its immutable approved versions |
| Route | Interface-to-target wiring, with an optional condition |
| Mapping | The transformation for one interface-target pair, bound to one source and one target contract version |
| Flow | Informal: an interface with its routes and mappings, published as one bundle |
| Message | One accepted record from a source |
| Delivery | One message's journey to one target; a persisted state machine |
| Attempt | One call to a target that left the platform; append-only |
| Business key | The value that defines ordering and identity of a record (rule mandatory per interface) |

## 5. Functional requirements and acceptance criteria

*Sources: per requirement in the right-hand column - the plan's done-when at
`ws/_specs/project-plan.md:47-52`, `:103-120`, `:126-129`; lifecycle rules at
`ws/_specs/f3-plan-brief.md:52-58`.*

Each requirement's acceptance criteria are the done-when of the plan phase named; every item is
test-first (a failing test before the code). How far the code has got: section 10.

| # | Requirement | Acceptance criteria (source) |
|---|---|---|
| FR-01 | **Tenant isolation.** A user or source of one tenant never sees another tenant's data, even with a forged request | `ws/_specs/project-plan.md:103`, `:51`; release gate in every release |
| FR-02 | **Accepted messages are never lost; duplicates are detected** | `ws/_specs/project-plan.md:104` |
| FR-03 | **Flow configuration** as rows only; versions immutable, walking draft, submit, approve, publish; approver never the author; bundle publish in one transaction; the two earlier UI-API defects tested first | `ws/_specs/project-plan.md:105` |
| FR-04 | **Login and identity**; customer from the credential only | `ws/_specs/project-plan.md:109` |
| FR-05 | **Systems and contract registry**: four target types, credentials (write-only, encrypted), test-connection with destination controls, versioned contracts approved by a non-author, recorded per message | `ws/_specs/project-plan.md:110` |
| FR-06 | **Validation and conditional rules** authored as decision tables; every violation in one response | `ws/_specs/project-plan.md:111` |
| FR-07 | **Mapping designer**: one mapping per interface-target pair; add, change, delete elements; graph emits the typed expression; exercised before publish | `ws/_specs/project-plan.md:112` |
| FR-08 | **Message backbone**: same-key order kept; records carry ids; tenant never from a record | `ws/_specs/project-plan.md:113` |
| FR-09 | **Routing core**: routes from configuration at runtime, fan-out with pinned versions, best-effort | `ws/_specs/project-plan.md:114` |
| FR-10 | **Target adapters API and MQ-CSV** with the five controls | `ws/_specs/project-plan.md:115` |
| FR-11 | **One common API**, single or bulk, per-record results, sync only for single-target flows | `ws/_specs/project-plan.md:116`, `:47` |
| FR-12 | **Logging and audit**: one correlation id end to end, masked fields, append-only audit | `ws/_specs/project-plan.md:117` |
| FR-13 | **Tracking, stage timings and daily report** equal to the delivery rows | `ws/_specs/project-plan.md:118`, `:50` |
| FR-14 | **Job scheduler** watched by AI agents (delivery retry, payload purge, daily report) | `ws/_specs/project-plan.md:119` |
| FR-15 | **Retry, dead letter, replay** two weeks later with pinned versions and full history | `ws/_specs/project-plan.md:49` |
| FR-16 | **Target adapters Kafka topic, DB and File** | `ws/_specs/project-plan.md:126`, `:48` |
| FR-17 | **Queue dashboard** (queued, processed, converted, failed; held; ageing) | `ws/_specs/project-plan.md:127` |
| FR-18 | **End-to-end suites**: Release 1 cut, then full suite as the project gate | `ws/_specs/project-plan.md:120`, `:128`, `:52` |
| FR-19 | **Look-and-feel redesign** last, with no functional change | `ws/_specs/project-plan.md:129` |

Lifecycle rules carried from the deleted design (*source: `ws/_specs/f3-plan-brief.md:52-58`*):
draft, test, approve, publish; published versions cannot be edited and an earlier version can be
restored as a new version; every transition is audited; an approver may not approve their own
change (BR-04), enforced in the database.

## 6. Decisions applied to the requirements

*Source: `ws/_specs/project-plan.md:136-153` (Mira's decisions, reversible on Dipesh's review),
ADR-005, ADR-007, ADR-010, ADR-011.*

- Bulk: valid records accepted and tracked one by one; invalid ones rejected with **all**
  reasons; split and validated at intake. **Team default caps:** 1,000 records or 10 MB per bulk
  call, 1 MB per record (ADR-011).
- Customer identified from the calling credential; a body `customerId` is only cross-checked.
- Immediate asynchronous reply with a tracking id; synchronous only for single-target flows,
  answering with the target's mapped response or, on timeout, a pending tracking id.
- DB targets only to approved tables through a restricted per-target account.
- Contract versions per target, approved by a non-author, recorded per message.
- Same business key delivered in order per target; a delivery waiting for retry holds later
  same-key deliveries for that target until it is delivered or dead-lettered.
- Best-effort multi-target delivery, no all-or-nothing (OQ-09).
- Volume about 50 messages per second at peak, payloads under 1 MB (OQ-08).

## 7. Non-functional figures

*Source: `ws/_specs/project-plan.md:171-185` (figures by phase, OQ-10 answered: accepted for
now); `ws/_reports/2026-10-01-t10-figure-reconciliation.md:13-84` (each figure line-cited in
the deleted documents).*

All figures are proposals Dipesh accepted for now; each is owned by the phase that measures it.

| Area | Figure | Owner phase |
|---|---|---|
| Durability | RPO zero for accepted messages | Outbox and Delivery |
| Console | Admin page load p95 under 1.5 s | Flow configuration |
| Mapping | Mapping test under 500 ms; transform 5 s per transform; build a mapping in under 30 minutes; lookup edits immediate and audited (OQ-07 stays Dipesh's) | Mapping designer |
| Routing | Routing budget 1 s | Routing core |
| Targets | Per-target timeout configurable; sync target call p95 under 2 s | Adapters API and MQ-CSV |
| Intake | Intake 2 s, else 503 and nothing persisted; ingest p95 200 ms, p99 500 ms; async delivery p95 5 s; sync budget 10 s; sync p95 2 s; 500 concurrent sources; API v1 window 12 months; 50 messages per second sustained plus a 10x burst with 0 loss; onboard a target in under 1 working day; 90% of integrations with no code change | Ingest end to end |
| Retention | Logs 30 days, metrics 13 months, traces 7 days; audit 7 years | Logging and audit |
| Support | "Where is message X?" under 2 minutes | Tracking and report |
| Operations | Investigate a failure under 2 minutes; roll-up versus per-delivery tracking shape (OQ-03 stays Dipesh's) | Queue dashboard |
| Deployment | RTO 1 hour; ingest uptime 99.9%; console uptime 99.5%; configuration RPO 15 minutes; pipeline under 15 minutes | Verified at deployment |
| Not placed | Source callback 5 s; onboard a source in under 2 hours; sandbox shape (OQ-05) | Outside current scope, listed |

## 8. Out of scope

*Source: `ws/_specs/project-plan.md:224-232`.*

Source-side protocols other than the common HTTP API (polling, file pick-up, scheduled
extracts, change data capture); SOAP, EDI, fixed-width, XML formats and SFTP unless Dipesh names
one; chained or orchestrated targets; all-or-nothing delivery and compensation; visual Camel
route designers; MongoDB, Elasticsearch/OpenSearch, a distributed cache, free-text search in
payloads; self-service tenant signup.

## 9. Facts only Dipesh or the client holds

*Sources: `ws/_specs/project-plan.md:189`; ADR-007; ADR-011.*

| Fact | Needed before | Default until answered |
|---|---|---|
| The client's queue-manager product and connection details | A real MQ test (not the build) | Standard JMS broker in tests |
| What "File" target means (shared folder or SFTP) | The File adapter (Release 2) | Shared folder |
| A description of reference design C | The look-and-feel redesign | `design.md` carries a TBD line |
| Whether multi-factor login is mandatory, and for whom | The login phase | Recommended: mandatory for tenant admins |
| Payload retention (must exceed the two-week replay window) | The scheduler's purge job | **Answered:** 30 days (Dipesh, 2026-10-12, with ADR-014; [architecture.md](architecture.md) section 3) |
| The reading of "customer" (section 4) | The login phase | As read |
| The bulk caps (section 6) | The ingest phase | As stated |
| The `APPROVER` role - a team addition to the plan's four human roles (section 3; ADR-007 Decision 5) | The login phase | **Team default:** added; tenant admins may also approve |

## 10. Build status at main `90c1ddd` (2026-10-16)

The requirements and acceptance criteria above are unchanged. This table only says how far the code has got, using
the status words of [architecture.md](architecture.md) section 9: **built** (runs in `platform-app`), **built, not
wired** (code and tests exist, nothing starts them), **planned**. A requirement is met only when its acceptance
criteria pass; none is declared met here.

| # | Status | Evidence |
|---|---|---|
| FR-01, FR-02 | Controls partly built (row-level security, tenant binding, the outbox in the intake transaction); not end to end while delivery is not wired [TBD - release gates] | [architecture.md](architecture.md) sections 0 and 5 |
| FR-03 | Built: draft, submit, approve, publish; reject **planned** | `platform-config/src/main/java/io/integrationplatform/config/flow/adapter/in/FlowAdminController.java:56-172` |
| FR-04 | Built: console login (BFF) and source credentials | `platform-tenancy/src/main/java/io/integrationplatform/tenancy/internal/web/ConsoleSecurityConfiguration.java:84-88`; [interface.md](interface.md) section 5 |
| FR-05 | Built for API systems; test-connection for other types answers `NOT_AVAILABLE_YET` | [fetch.md](fetch.md) sections 2-5 |
| FR-06 | Built in the console and the admin API; intake does not run the rules yet | [design.md](design.md) section 4; [interface.md](interface.md) section 2, "As built" |
| FR-07 | Built: mapping designer and recorded sample run, required before publish | `platform-config/src/main/java/io/integrationplatform/config/mapping/adapter/in/MappingTestController.java:51`; `platform-config/src/main/java/io/integrationplatform/config/mapping/application/MappingPublishPinCheck.java:28-30` |
| FR-08, FR-09 | Built, not wired (relay, stage consumers) | [architecture.md](architecture.md) section 9 |
| FR-10 | API adapter built, not wired; MQ-CSV **planned** | `platform-routing/src/main/java/io/integrationplatform/routing/adapter/api/ApiTargetCall.java:48` |
| FR-11 | Single-record endpoint built; bulk and synchronous **planned** | [interface.md](interface.md) section 2, "As built" |
| FR-12 | Audit **planned** (no `audit_event` table) | [architecture.md](architecture.md) section 9 |
| FR-13 | **Planned**: no tracking or report screen; stage timings are recorded in `message_stage_timing` | [design.md](design.md) section 4; `platform-routing/src/main/resources/db/migration/V2_1_1110__message_stage_timing.sql:26` |
| FR-14 | Scheduler shell runs 4 jobs; their bodies are no-ops or skipped | `platform-app/src/main/java/io/integrationplatform/app/scheduler/SchedulerJobTasks.java:37-53`; `platform-app/src/main/java/io/integrationplatform/app/scheduler/JobRunWrapper.java:32-34` |
| FR-15 | Retry and dead letter built, not wired; replay **planned** (SQL only) | `platform-delivery/src/main/java/io/integrationplatform/delivery/internal/JdbcDeliveryRepository.java:202` |
| FR-16 | **Planned** | - |
| FR-17 | Built: queue dashboard and its summary API | `platform-app/src/main/java/io/integrationplatform/app/queue/QueueSummaryController.java:51`; `ui/src/pages/QueueDashboard.tsx:14` |
| FR-18 | Screen suites exist (12 specs in `ui/tests/screens/`); the `platform-e2e` module is **planned** | `pom.xml:23-38` |
| FR-19 | **Planned** (last phase) | [design.md](design.md) section 1 |
