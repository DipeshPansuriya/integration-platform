# Handover - configuration-driven integration platform

This document is written for a team, and for that team's AI coding agents, taking over this repository with no other
briefing. It was written on 2026-10-26 against `main` at `5b9a6c36`.

How to read the evidence:

- A claim about the code carries `path:line`, relative to the repository root.
- Facts that come from the planning workspace outside this repository (the plan, backlog, dated test and security
  reports) are cited as `ws/<path>`. That workspace is not shipped with the repository. Ask the project owner for an
  extract if you need one. `documents/README.md` explains this `ws/` convention.
- **not verified** means the writer could not check the claim against code or a dated report. All of these are
  collected in section 13.
- Test counts are quoted with the date and commit they were measured at. Nothing in this document was re-run on
  `5b9a6c36` unless it says so.

## Table of contents

1. [Purpose and who this is for](#1-purpose-and-who-this-is-for)
2. [Product in one page](#2-product-in-one-page)
3. [Actual scope](#3-actual-scope)
4. [Release 1](#4-release-1)
5. [Release 2](#5-release-2)
6. [End-to-end walkthrough](#6-end-to-end-walkthrough)
7. [Architecture](#7-architecture)
8. [Deployment and environments](#8-deployment-and-environments)
9. [Security](#9-security)
10. [How to run and test](#10-how-to-run-and-test)
11. [How to work on this code](#11-how-to-work-on-this-code)
12. [Guide for AI agents](#12-guide-for-ai-agents)
13. [Open items and risks](#13-open-items-and-risks)
14. [Glossary](#14-glossary)
15. [Source index](#15-source-index)

---

## 1. Purpose and who this is for

| Reader | Use this document to |
|---|---|
| A developer or tech lead joining the project | Learn what the product does, what is built, what is left, and how to build, test and change it safely |
| An AI coding agent working in this repository | Follow section 12 as its operating instructions, together with `AGENTS.md` |
| A reviewer or security reviewer | Find the current position of the Release 1 security statement (section 4.6) and the findings table (section 9) |
| An operator of the hosted showcase | Read section 8 and then [deployment.md](deployment.md), which holds the operational detail |

This document is a map. It does not replace the design documents. When it disagrees with
[PRD.md](PRD.md), [architecture.md](architecture.md), [rule.md](rule.md) or an ADR, the design document wins for
*intent*. For *current state*, trust the dated reports cited here, because those documents carry status notes frozen at
`main 90c1ddd` (2026-10-16) (`documents/README.md:109-113`, `documents/PRD.md:194`). Those status notes are now out of
date: for example, they still say the relay and the consumers are "not wired". Section 4 gives the current state.

## 2. Product in one page

**What it is.** A configuration-driven, multi-tenant integration platform (an iPaaS). An end user signs in and defines,
as configuration: a source system and its interface, the request contract, one or more target systems, a **versioned
contract per target**, the field mapping, validation and conditional rules, and other settings. A source system calls
**one common API**. The platform finds the customer, the interface and the targets, then applies each target's own
contract and mapping and sends. The source never knows the target formats. A new interface, target or contract
version needs no code change and no deploy (`documents/PRD.md:34-41`).

**The acceptance test of the idea.** Customer A sends `{customerId, orderId, amount}` once. Target A (contract v1.0)
receives `{customer_code, order_number, total_amount}`. Target B (contract v2.0) receives
`{clientId, referenceNo, invoiceValue}`. Both are configured through the application, with 0 deploys and 0 migrations
(`documents/PRD.md:51-55`).

**Who uses it.** The human roles are in code as `TENANT_ADMIN`, `CONFIGURATOR`, `APPROVER`, `SUPPORT` and `READ_ONLY`
(`platform-tenancy/src/main/java/io/integrationplatform/tenancy/Role.java:11-19`). The machine caller kinds include
`SOURCE_SYSTEM` and `OPERATOR_AGENT` (`platform-tenancy/src/main/java/io/integrationplatform/tenancy/RolePolicy.java:57-59`).
Tenants are onboarded by the platform operator; there is no self-service signup (`documents/PRD.md:61-70`).

| Role | Can |
|---|---|
| Tenant admin | Everything in the tenant, including users, roles and credentials; may approve |
| Configurator | Create and edit systems, contracts, flows, rules and mappings as drafts; submit for approval |
| Approver | Approve or reject submitted versions, never their own |
| Support | Read messages, deliveries and timelines; replay or discard dead letters (audited) |
| Read-only | Read configuration and tracking |
| Source system (machine) | Call the common API with its own credential |
| AI agent (machine, `OPERATOR_AGENT`) | List, run, pause and inspect scheduled jobs |

**The message path, ingest to delivery** (asynchronous default; full table in `documents/architecture.md:98-115`):

1. The source posts to `POST /api/v1/interfaces/{interfaceCode}/messages` or `.../messages:bulk` with its bearer token
   (`platform-ingest/src/main/java/io/integrationplatform/ingest/adapter/in/IntakeController.java:118`, `:185`).
2. The tenant, source system and customer are resolved from the credential, never from the body. A `customerId` in the
   body is only cross-checked.
3. The platform checks size caps, validates against the contract, applies rules and takes the business key.
4. One database transaction writes the message, payload and outbox rows. The reply is 202 with a tracking id.
5. The outbox relay publishes the message **id only** to Kafka topic `platform.message.accepted`.
6. Routing stage 1 (Camel) loads the pinned interface version, evaluates the routes, and creates one Delivery per
   matched target, pinning the endpoint, contract and mapping versions. It publishes to `platform.delivery.due`.
7. Routing stage 2 claims the delivery, applies the JSONata mapping, checks the destination, and sends through the
   target adapter (API, MQ-CSV, Kafka topic, DB or File) with timeout, circuit breaker and in-attempt redelivery.
   Every call writes one attempt row.
8. The delivery ends `DELIVERED`, or `RETRY_WAIT` (picked up again by the delivery-retry job), or `DEAD_LETTERED`
   (an operator replays it later from the Failures screen). An unreadable Kafka record goes to `platform.poison`.

**Tenants.** Every tenant-owned row carries `tenant_id` and is protected by PostgreSQL row-level security as a second
wall behind the application filter. The tenant is never taken from a request body, header, path or Kafka record
(`documents/architecture.md:142-155`). The hosted showcase is seeded with two tenants and four users
(`deploy/hostinger/README.md:20`).

## 3. Actual scope

The scope is the approved plan (`ws/_specs/project-plan.md`, status `approved`). It is mirrored, with the same
requirement numbers, in [PRD.md](PRD.md) section 5. Where the PRD and the plan differ, the plan wins
(`documents/PRD.md:22-23`).

### 3.1 In scope - functional requirements

| FR | Requirement | Release (plan / backlog epic) |
|---|---|---|
| FR-01 | Tenant isolation, even against a forged request; a release gate | R1 (E1) |
| FR-02 | Accepted messages are never lost; duplicates are detected | R1 (E2) |
| FR-03 | Flow configuration as rows only; immutable versions: draft, submit, approve, publish; the approver is never the author; bundle publish in one transaction | R1 (E3) |
| FR-04 | Login and identity; the customer comes from the credential only | R1 (E4) |
| FR-05 | Systems and contract registry: four target types, write-only encrypted credentials, test-connection with destination controls, versioned contracts approved by a non-author | R1 (E5) |
| FR-06 | Validation and conditional rules as decision tables; every violation in one response | R1 (E6) |
| FR-07 | Mapping designer: one mapping per interface-target pair; the graph emits the typed expression; exercised before publish | R1 (E7) |
| FR-08 | Message backbone: same-key order kept; records carry ids; the tenant never comes from a record | R1 (E8) |
| FR-09 | Routing core: routes from configuration at runtime, fan-out with pinned versions, best-effort | R1 (E9) |
| FR-10 | Target adapters API and MQ-CSV with the five controls | R1 (E10) |
| FR-11 | One common API, single or bulk, per-record results, synchronous only for single-target flows | R1 (E11) |
| FR-12 | Logging and audit: one correlation id end to end, masked fields, append-only audit | R1 (E12) |
| FR-13 | Tracking, stage timings and a daily report equal to the delivery rows | R1 (E13) |
| FR-14 | Job scheduler watched by AI agents (delivery retry, payload purge, daily report) | R1 (E14) |
| FR-15 | Retry, dead letter, and replay two weeks later with pinned versions and full history | R1 (E2, E9, E13) |
| FR-16 | Target adapters Kafka topic, DB and File | R2 (E16) |
| FR-17 | Queue dashboard (queued, processed, converted, failed; held; ageing) | R2 (E17) |
| FR-18 | End-to-end suites: the Release 1 cut, then the full suite as the project gate | R1 (E15) and R2 (E18) |
| FR-19 | Look-and-feel redesign, last, with no functional change | R2 (E19) |

Sources: `documents/PRD.md:102-122`; the epic-to-release map is in `ws/_specs/backlog.md` "Epic map".

### 3.2 Engineering rules

Every rule in [rule.md](rule.md) is a MUST (`documents/rule.md:27`). In short:

| Rule | Summary |
|---|---|
| R01 | Test first: a failing test before the code |
| R02 | Gates in counts, not ratios; every claimed control has a test that fails on a planted violation |
| R03 | A bare `mvn test` never runs container tests |
| R04 | Every Maven run sets `JAVA_TOOL_OPTIONS=-Duser.timezone=Etc/UTC` |
| R05 | Every container image tag is pinned |
| R06 | One Docker/Testcontainers suite at a time on the build machine, with capped JVM flags |
| R07 | No literal password or secret in any file; only environment variable names |
| R08 | Tenant filter on every query, raw JDBC included, in every module's package |
| R09 | Explicit `?::uuid` casts in hand-written SQL |
| R10 | Configuration additions never need a migration; closed sets are `text` plus `CHECK`, never `ENUM` |
| R11 | The tenant setting is stamped per transaction only; SQL is always parameterised |
| R12 | Camel only in `platform-routing`; no spring-kafka; exactly one kafka-clients version (4.3.1) and one networknt version |
| R13 | Five controls on every call that leaves the process: timeout, transient-only retry, circuit breaker, fallback, metrics and trace |
| R14 | Structured JSON logs; masked fields never appear; secrets are write-only |
| R15 | Audit, delivery attempts and published versions are append-only |
| R16 | Pipeline target under 15 minutes to the DEV gate |
| R17 | No push, remote, pull request or merge without the project owner's word (see section 11 for how this works today) |
| R18 | No AI-agent folder (`.claude/`) and no second agent guidance file in this repository |
| R19 | No employer, client or product names in any file; English only |
| R20 | UI work follows the project's design system; the redesign comes after all functionality |
| R21 | Diagrams stay in sync with the code in the same change |

Sources: `documents/rule.md:35-55`.

### 3.3 Out of scope

From `documents/PRD.md:173-177` and `ws/_specs/project-plan.md:226-232`:

- Source-side protocols other than the common HTTP API: polling, file pick-up, scheduled extracts, change data capture.
- SOAP, EDI, fixed-width and XML formats, and **SFTP**, unless the project owner names one.
- Chained or orchestrated targets (one target's response feeding another).
- All-or-nothing multi-target delivery and compensation. Delivery is best-effort per target.
- Visual Camel route designers.
- MongoDB, Elasticsearch/OpenSearch, a distributed cache, free-text search inside payloads.
- Self-service tenant signup.

### 3.4 Assumptions (decisions applied, reversible by the project owner)

From `documents/PRD.md:134-145`:

- Bulk: valid records are accepted one by one and invalid ones are rejected with all reasons. Team default caps:
  1,000 records or 10 MB per bulk call, 1 MB per record.
- The customer is identified from the calling credential.
- The reply is immediate and asynchronous, with a tracking id. Synchronous replies are only for single-target flows.
  On timeout the reply is a pending tracking id, never a retry on the caller thread.
- DB targets write only to approved tables, through a restricted per-target account.
- The same business key is delivered in order per target. A delivery waiting for retry holds later same-key deliveries.
- Volume is about 50 messages per second at peak, with payloads under 1 MB.
- Payload retention is 30 days, answered by the project owner on 2026-10-12 (`documents/PRD.md:189`).
- "File" target means a shared folder until the owner says otherwise (`documents/PRD.md:186`).

### 3.5 Not decided

| Item | Default until answered | Source |
|---|---|---|
| The client's queue-manager product and connection details | A standard JMS broker (ActiveMQ Artemis in tests) | `documents/PRD.md:185` |
| Whether "File" also means SFTP | Shared folder; SFTP is out of scope unless named | `documents/PRD.md:186` |
| Whether multi-factor login is mandatory, and for whom | Recommended mandatory for tenant admins | `documents/PRD.md:188` |
| OQ-03 roll-up versus per-delivery tracking shape; OQ-05 sandbox shape; OQ-07 lookup immediacy | Open, owner's call | `documents/PRD.md:159`, `:165`, `:167` |
| Release 2 detailed schedule and re-estimate | The plan asks for a re-estimate when Release 1 closes; none is on record | `ws/_specs/release-base-plan-20261013.md:120` |
| The platform-operator principal and the operator dashboards (ADR-H-IPASS-02, proposed, three open owner decisions) | Not built | `ws/_documents/2026-10-25-adr-operator-dashboards.md:16`, `:153` |
| The Kafka authentication layer (SASL/ACL) behind the ops Kafka viewer | Open design item | `ws/_documents/2026-10-26-hosted-deployment-runbook.md:68`, `:78` |

## 4. Release 1

### 4.1 Definition

Release 1 is "the example works end to end": phase 0 (decisions and documents) and epics E1 to E14, closed by E15,
the Release 1 cut of the end-to-end suite. That cut proves, on real containers plus a browser journey: login, then
configure source, contracts and targets, then one call, with every target receiving its own format for the API and
MQ-CSV targets. It covers the worked example, bulk with invalid records, retry then success, dead letter then replay
with pinned versions, a new contract version with 0 deploys, same-key ordering, cross-tenant denial, report counts
equal to the delivery rows, and stage timings. The security review must leave no open Critical or High finding
(`ws/_specs/project-plan.md:122-124`).

### 4.2 What is built and merged

Release 1 was tagged `release-1` at commit `0b4cbf04`, and the tag is on the remote (`git ls-remote --tags origin`,
run 2026-10-26). Since the tag, 139 more commits have landed on `main`, through pull requests #4 to #20
(`git log 0b4cbf04..5b9a6c36`, run 2026-10-26).

**Read the epic table with care.** The project tracker lags the code. Several epics show low percentages on the tracker
while their code is on `main` and passed the gates in section 4.7. Both views are given; trust the gate evidence for
"does it work", and the tracker for "is the paperwork closed".

| Epic | Title | Tracker state (2026-10-26) | Code and gate evidence |
|---|---|---|---|
| E0 | Architecture decisions | Done (F11 6/6) | ADR-001 to ADR-018 in `documents/adr/` |
| E1 | Tenant isolation foundation | Done (F1 4/4) | Forged-tenant denial suite 1,225 tests, 0 failed at `347a1466` (G5 report) |
| E2 | Transactional outbox and Delivery | Done (F2 4/4) | Container suite (section 4.7) |
| E3 | Flow configuration | Done (F3 4/4) | `FlowAdminController.java:56-171` (create, submit, approve, publish) |
| E4 | Login and identity | Done (F12 4/4) | Console login through the BFF; source credentials |
| E5 | Systems and contract registry | Done (F13 4/4) | `SystemAdminController`, `ContractVersionAdminController`, credential controllers |
| E6 | Validation and rules | Done (F4 4/4) | Rule sets, lookups, `RuleEvaluationController` |
| E7 | Mapping designer | Done (F5 14/14) | `MappingAdminController`, `MappingTestController` |
| E8 | Camel/Kafka backbone | Done (F6 5/5) | Topics `platform.message.accepted`, `platform.delivery.due`, `platform.poison` |
| E9 | Camel routing core | Done (F14 8/8) | Container and e2e-full gates |
| E10 | Targets API and MQ-CSV | 16% (1/6); the rest "in review" | Adapters in `platform-routing/src/main/java/io/integrationplatform/routing/adapter/{api,mq}`; MQ-CSV journey passed in e2e-full |
| E11 | Ingest pipeline end to end | 33% (2/6) | Single and bulk intake endpoints exist (`IntakeController.java:118`, `:185`); Release 1 scenarios SC-01 to SC-09 passed |
| E12 | Logging and activity | 0% (0/7); items "in review" | Activity API `/api/v1/activity` exists (`ActivityEndpointRoles.java:18`) |
| E13 | Tracking, timings, daily report | 0% (0/6); items "in review" | Timeline, failures, retry/replay and delivery report APIs exist (section 6) |
| E14 | Job scheduler for AI agents | 16% (1/6) | Job admin API `/api/v1/admin/jobs` exists (`JobAdminController.java:32`) |
| E15 | End-to-end suite, Release 1 cut | 31% (5/16), flagged blocked | Release 1 verdict GO on 2026-10-24 (section 4.7) |

Tracker source: `hub track show ipass/integration-platform`, run 2026-10-26; the overall tracker reads
"F 12/21, T 103/203, 13 open defects". Paths in the table above are abbreviated; the full controller paths are in
section 6.

Release 2 epics that are already partly or fully built are listed in section 5.

### 4.3 What is demonstrable today

- **Locally**, the recorded demo covers sign-in; send a message (202 plus tracking id); delivery to the target stub;
  the timeline screen; two tenants delivering while tenant B cannot reach tenant A; a failure on the Failures screen;
  Replay with a confirm step; and dashboard counts matching real rows. It ran 12/12 at `0b4cbf04`
  (`ws/_reports/2026-10-24-release1-verdict-and-proof.md:41`; story in `ws/_documents/2026-10-24-demo-runbook.md`
  section 3). The specs now live under `e2e-ui/tests/` (moved there from `ui/tests/`; `documents/e2e-ui-inventory.md`).
- **Nested appointment showcase.** A source document with two array levels (`VehicleList[]`, each with
  `ContainerList[]`) is flattened to one 20-field record per container. A live proof passed 6/6 on a real backend at
  `main 1b07586d` on 2026-10-25 (`ws/_reports/2026-10-25-t215-nested-live.md`). See section 6.
- **Hosted showcase** at the three public links in section 8. On 2026-10-25 a read-only count found 0 messages ever
  processed on the hosted app, and the nested-appointment flow published with 0 messages
  (`ws/_reports/2026-10-25-hosted-queue-counts.md`). In other words, the hosted copy is deployed and healthy, but no
  end-to-end message has been run there.

### 4.4 Acceptance criteria

These are the plan's E15 done-when (section 4.1). The working "base version" definition used for the showcase is:
a signed-in user creates a flow, maps the payload on the designer, passes the sample-run gate, and publishes. A source
posts to the intake API; the message routes; the API target receives contract-translated output. The user opens the
message and sees every stage with its time, traces a failure to its stage, fixes the cause, replays from the screen,
and the dashboard counts reflect queued, processed, converted and failed. The two-tenant safety suite is green
(`ws/_specs/release-base-plan-20261013.md:96`).

### 4.5 What is left for Release 1

| Item | Source |
|---|---|
| Release 1 browser journey runs and passes, but the gate runner covers only the Java scenarios; tracker item T208 stays open by the owner's choice | `ws/_reports/2026-10-24-release1-verdict-and-proof.md:42`, `:71`; tracker T208 |
| Eight accepted "not covered" test gaps: actuator readiness/liveness bodies on the real app, a refused DB approval reaching `audit_event` under RLS, an over-limit chunked body, the FILE refusal on live Postgres, a 5x bulkhead repeat, journey Ctrl-C cleanup, target B (Artemis) in the browser journey, and a Java re-run at `0b4cbf04` | `ws/_reports/2026-10-24-release1-verdict-and-proof.md:55-64` |
| Open P1 defects on the tracker: B33, B35 (scheduler `permission denied for schema schedule` in one test stack), B36 (BS-11.1 bare 403 after republish; root cause recorded as test data in T206), T195, T199, T201, T202, T205, T206 | tracker 2026-10-26 |
| Security statement still "partly executed"; per-environment probes P1-P9 open | section 4.6 |
| Many "in review" tracker items to close against evidence | tracker 2026-10-26 |

### 4.6 Security statement position

**Release 1 security statement is 'partly executed', worded 'lane runs plus review'. It is NOT a security pass.**
(`ws/_reports/2026-10-24-release1-verdict-and-proof.md:17`, `:51`; `ws/_documents/2026-10-26-hosted-deployment-runbook.md:72`)

- Filed runs exist: 10 SecurityGate container classes with 50 tests and 0 failures at `347a1466`; Playwright security
  specs 13/13 at `0b4cbf04`; manual mutation checks where 3 of 4 turned the named test red and then green
  (`ws/_reports/2026-10-24-release1-verdict-and-proof.md:32`, `:40`, `:43`).
- An earlier case-map figure has **not** been recomputed against those runs. Do not quote any "accepted of 54" figure
  as current (`ws/_reports/2026-10-24-release1-verdict-and-proof.md:50`).
- **Standing conditional High: R1/F7, Kafka plaintext.** Release 1 is accepted only with
  `platform.config.destinations.kafka-allow-plaintext=false` in every environment and no egress policy. Turning the
  switch on without a written egress policy reopens the High. The deployed value is not verified in any environment
  (`ws/_documents/2026-10-22-release1-security-statement-and-risk-acceptance.md:29-42`).
- DB targets use `sslmode=require`; certificate-chain verification is later hardening (same document, `:30`).
- **Per-environment probes P1-P9 are open before any external exposure.** They cover: a re-run of the tampered SESSION
  cookie cases, the edge cookie rule, the `ipaas_app` grant before start, a live DB-account width check, deployed
  config facts (plaintext off, `sslmode`, allow-lists, actuator exposure), intake validation, the anonymous surface, and
  the key-encryption key being present (`ws/_reports/2026-10-23-security-statement-refresh.md:139-147`).

### 4.7 Test evidence summary

| Suite | Last known result | Commit | Evidence |
|---|---|---|---|
| Container suite `-Pcontainer` (platform-app and upstream) | 147 classes, 1,902 tests, 0 failed, 0 errors, 5 skipped (`@Disabled` SecurityGate tests) | `347a1466` (2026-10-24) | `ws/_reports/2026-10-24-g5-main-container.md` |
| Forged-tenant denial suite | 1,225 tests, 0 failed | `347a1466` | same |
| SecurityGate container classes | 10 classes, 50 tests, 0 failed | `347a1466` | same |
| Full end-to-end gate `-Pe2e-full` | 14 tests in 5 classes, 0 failed; GATE PASS 10/10 required scenarios (all five target types, three hour-long outages, memory budget) | `347a1466` | same |
| arch-test | 66 tests, 0 failed | `347a1466` | same |
| Release 1 Java scenarios SC-01 to SC-09 | PASS, 10 tests | `6ce6c561` (same tree as `347a1466`) | `ws/_reports/2026-10-24-release1-verdict-and-proof.md:35` |
| UI offline `npm test` | 428/428 | `0b4cbf04` | `ws/_reports/2026-10-24-g7-ui-gates-0b4cbf04.md` |
| Base suite (BS-10 Replay, BS-11.3, BS-11.3b) | 20/20 | `0b4cbf04` | same |
| Screens suite | 128/128 | `0b4cbf04` | same |
| Playwright security specs | 13/13 | `0b4cbf04` | same |
| Release 1 browser journey | PASS, 6 steps | `8732c738` (tree equals `0b4cbf04`) | verdict report `:42` |
| Nested appointment live proof | 6/6; `e2e-ui` unit tests 84/84 | `1b07586d` (2026-10-25) | `ws/_reports/2026-10-25-t215-nested-live.md` |
| Hosted kit offline tests (`deploy/hostinger/test/test-scripts.sh`) | 111 passed, 0 failed | hosted deploy of `4e0730a6`, 2026-10-26 | planning-workspace memory entry m-d93433 |

Two count notes:

- `e2e-ui/README.md:17` says the `e2e-ui` unit tests are 45, but the 2026-10-25 report counted 84. The newer report is
  trusted; the README is probably stale. This is not verified.
- Measured from source on `5b9a6c36` (2026-10-26, `git grep "@Test"`): `@Test` methods per module are app 455,
  config 477, routing 601, tenancy 287, delivery 211, ingest 153, e2e 64, arch-test 63, domain 28, transform 22,
  audit 5, tracking 1. These are source counts, not run results.

## 5. Release 2

### 5.1 Scope by epic (from the plan)

| Order | Epic | Done-when, short | State today |
|---|---|---|---|
| 1 | E16 Target adapters: Kafka topic, DB, File | Each adapter carries the five controls with a fault test and its own test-connection. DB writes only to approved tables through a restricted account, with no double insert on an ambiguous retry. File writes complete files only | Built ahead of plan: adapters in `platform-routing/src/main/java/io/integrationplatform/routing/adapter/{kafkatopic,db,file}`; approved-tables API (`ApprovedTableAdminController.java:30`); all three journeys passed in e2e-full at `347a1466`. Tracker F18 still shows 0% |
| 2 | E17 Queue dashboard | Counts per interface, target and tenant; dead-letter depth with ageing; drill-down to the timeline; a business alert for a silent interface; audited bulk retry or discard | Narrow version built (`/api/v1/admin/queue/summary`, `QueueSummaryController.java:35`, `:51`; tracker F10 done). Drill-downs, silent-interface alert and bulk actions are not built |
| 3 | E18 Full end-to-end suite | All target types; each dependency down for an hour with 0 loss; measured memory budget; the project-close gate | The gate (`build-checks/e2e-full-gate.sh`) passed 10/10 at `347a1466`. The browser leg (`e2e-ui/tests/e2e-full/`) and its formal close are not verified. Tracker F19 shows 0% |
| 4 (last) | E19 Look-and-feel redesign | Restyle with no functional change, WCAG AA, screen-by-screen owner review | Already delivered early (tracker F20 18/18, 2026-10-16), against the plan's "last" order. The plan text still puts it last |

Source: `ws/_specs/project-plan.md:126-131`; backlog "Epic map".

### 5.2 Roadmap items deferred from the base version

From `ws/_specs/release-base-plan-20261013.md:98-120`. "R1.x" were interim waves between the base version and the
Release 1 cut.

| Item | Planned wave | State |
|---|---|---|
| Route-core edge hardening | R1.1 | Mostly landed (E9 done) |
| MQ-CSV and DB adapters (R1.1), File (R1.2) | R1.1 / R1.2 | Built (sections 4.2, 5.1) |
| Bulk intake | R1.2 | Endpoint exists (`IntakeController.java:185`); tracker T115 "in review" |
| Full daily-report job and dashboard drill-downs | R1.2 | Report API exists (`/api/v1/reports/delivery`); daily aggregates and their screen are tracker T120 (todo); drill-downs not built |
| Multi-target fan-out per source | R1.2 | E9 fan-out built; full proof not verified |
| Scheduler plus Kafka viewer | R1.3 | Job API exists; Kafka viewer hosted on the ops address (section 8) |
| Kafka-plane audit step | R1.3 | Not verified |
| File targets, full end-to-end suite, Release 2 cut | R2 | See 5.1 |

**SFTP** is not in Release 2. It is out of scope unless the owner names it (section 3.3). ADR-015's 2026-10-19 addendum
records "FILE = shared folder R2, SFTP revisit" (planning-workspace memory m-4e0714).

### 5.3 Dependencies and order

E16 comes first, then E17 and E18; E19 is last by plan, although it is already delivered. E18 depends on E16 (it needs
every target type). E17's business alert depends on E13's daily aggregates (tracker T120). Everything depends on
closing the Release 1 open items in section 4.5.

### 5.4 Estimates and dates (stale)

| Milestone | Plan date | Status |
|---|---|---|
| Base version showcase | Thu 2026-10-22 (re-baselined from 2026-10-18) | Superseded: build complete Fri 23, owner check Sat 24, ceiling Mon 26 (`ws/_documents/showcase-features-requirements-20261022.md`, timeline line) |
| Release 1 cut | Week of 2026-11-23 | **Stale**: the `release-1` tag was cut on 2026-10-24, earlier than planned |
| Release 2 complete | About late January 2027 | Marked [Inferred] in the plan itself; no re-estimate on record |
| Release 2 size | M, about 6 to 8 weeks | UNVERIFIED in the plan ("no measured velocity") (`ws/_specs/project-plan.md:65-67`) |

Source for the dates: `ws/_specs/release-base-plan-20261013.md:55-63`.

### 5.5 Open decisions for Release 2

- Re-estimate Release 2 now that Release 1 is tagged (the plan asks for it; none exists).
- Whether Release 2 becomes its own sub-project (`ws/_specs/project-plan.md:242`).
- The Kafka-topic target is not in the owner's formal target list (API, DB, MQ, File); it was kept by a reversible
  decision (`ws/_specs/project-plan.md:153`).
- The queue-manager product (MQ-CSV against the real broker).
- The operator dashboards ADR (proposed) and the platform-operator principal.
- Whether E19 needs another pass after Release 2 functionality, since it was done early.
