# Handover - configuration-driven integration platform

This document is written for a team, and for that team's AI coding agents, taking over this repository with no other
briefing. It was written on 2026-10-26 against `origin/main 5b9a6c36`; `main` has moved on since (at least one later
code-only commit), so check `git log 5b9a6c36..origin/main` for anything newer.

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
`main 90c1ddd` (2026-10-16) (`documents/README.md:32`, `documents/PRD.md:194`). Those status notes are now out of
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
| E15 | End-to-end suite, Release 1 cut | 31% (5/16), flagged blocked | Release 1 verdict GO on 2026-10-24 for the candidate build; the security statement is still only partly executed (section 4.6) |

Tracker source: the project tracker, read on 2026-10-26; the overall tracker reads
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
| UI offline `npm test` | 428/428 (383 + 45) at that commit; 499 tests (387 + 112) on `7b916dd7`, 2026-10-27, of which 3 `ui` tests fail (see section 10.4) | `0b4cbf04`; `main` after #32 | `ws/_reports/2026-10-24-g7-ui-gates-0b4cbf04.md`; run of 2026-10-27 (`npm test` in `ui/` and `e2e-ui/`) |
| Base suite (BS-10 Replay, BS-11.3, BS-11.3b) | 20/20 | `0b4cbf04` | same |
| Screens suite | 128/128 | `0b4cbf04` | same |
| Playwright security specs | 13/13 | `0b4cbf04` | same |
| Release 1 browser journey | PASS, 6 steps | `8732c738` (tree equals `0b4cbf04`) | verdict report `:42` |
| Nested appointment live proof | 6/6; `e2e-ui` unit tests 84/84 | `1b07586d` (2026-10-25) | `ws/_reports/2026-10-25-t215-nested-live.md` |
| Hosted kit offline tests (`deploy/hostinger/test/test-scripts.sh`) | 111 passed, 0 failed | hosted deploy of `4e0730a6`, 2026-10-26 | hosted deploy record of 2026-10-26 |

Two count notes:

- `e2e-ui` unit test count: 112 passing on `7b916dd7` (`npm test` in `e2e-ui/`, 2026-10-27); `ui` `npm run test:product` ran 387,
  384 passing. Read counts from the run summary; they move with every added test. Earlier figures were 45 at tag `release-1` (`0b4cbf04`,
  `e2e-ui/README.md:17`; 383 + 45 = 428) and 84 at `1b07586d` (2026-10-25 report). On `5b9a6c36` the `test` script in
  `e2e-ui/package.json` named five files that were not on `main` (section 13, item 16), so `npm test` failed there;
  the T224 branch restored them and is merged (`930360b0`), so `main` runs the full script (111 at that merge, 112 after PR #32).
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
records "FILE = shared folder R2, SFTP revisit".

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

## 6. End-to-end walkthrough

One scenario: the **nested appointment** showcase. The source posts one appointment with vehicles and containers; the
target receives one flat record per container. It is described in `deploy/hostinger/seed/NESTED-APPOINTMENT.md`, with
its driver, seed, sample and mapping under `e2e-ui/scripts/` (`NESTED-APPOINTMENT.md:5`). It was proven live 6/6 on
2026-10-25 (section 4.7). Every step below calls the endpoints that the seed driver uses, so a person in the console
performs the same steps.

Console screens are in `ui/src/pages/`: `Login`, `Dashboard`, `FlowsList`, `FlowDefinition`, `Versions`, `Systems`,
`ContractVersions`, `MappingDesigner`, `MessageDetail`, `Failures`, `QueueDashboard`, `DeliveryReport` and `Activity`.

| # | Step | Who | Screen | API (prefix `/api/v1`) | Code |
|---|---|---|---|---|---|
| 1 | Sign in through Keycloak (realm `platform`). The browser holds only the backend's HttpOnly `SESSION` cookie (BFF) | Tenant admin (`showcase-admin-a`, tenant `showcase-a`) | `Login` | `GET /me` | `MeController.java:27` |
| 2 | Register the source system `nested-source` and the target system `nested-target` (type API, base URL on the allow-listed demo host) | Tenant admin / configurator | `Systems` | `POST /admin/systems`, `GET /admin/systems/{id}`, `PUT /admin/systems/{id}`, `POST /admin/systems/{id}/test-connection` | `SystemAdminController.java:36`, `:54`, `:106` |
| 3 | Create contract version 1.0 for each system (JSON Schema, or import OpenAPI), submit, and have an approver approve (the author may approve their own) | Configurator, then approver | `ContractVersions` | `POST /admin/contracts/versions`, `POST .../versions/import`, `POST .../versions/{id}/submit`, `POST .../versions/{id}/approve` | `ContractVersionAdminController.java:45-46`, `:66`, `:96`, `:113`, `:161` |
| 4 | Create the flow (interface, route to the target, pinned contract versions) | Configurator | `FlowsList`, `FlowDefinition` | `POST /admin/flows`, `PUT /admin/flows/{id}`, `GET /admin/flows/{id}/routes` | `FlowAdminController.java:36`, `:56`, `:95`, `:112` |
| 5 | Map the payload. The mapping is a raw JSONata expression (`expression.jsonata`), opened in the raw editor, because the drawn-links canvas cannot repeat a parent field per child (`NESTED-APPOINTMENT.md:5`) | Configurator | `MappingDesigner` | `GET`/`PUT /admin/routes/{routeId}/mapping` | `MappingAdminController.java:27`, `:46`, `:67` |
| 6 | Run the sample. A passing sample run is required before publish (the publish pin check) | Configurator | `MappingDesigner` (Run sample panel) | `POST /admin/mappings/{id}/test` | `MappingTestController.java:51`; `MappingPublishPinCheck.java` |
| 7 | Optional: validation and conditional rules as decision tables, and lookups | Configurator | `FlowDefinition` (rules editor) | `POST`/`GET /admin/flows/{flowId}/rule-sets`, `PUT /admin/rule-sets/{id}`, `POST .../evaluate`, `/admin/lookups/{id}/entries` | `RuleSetAdminController.java:35-36`, `LookupAdminController.java:31-32` |
| 8 | Submit, approve, publish. A tenant admin or approver may approve their own version (single-user approval); the hosted seed uses a separate approver. Publish is one transaction over the interface, routes and mappings | Author submits; `showcase-approver-a` approves; tenant admin publishes | `Versions` | `POST /admin/flows/{id}/submit`, `.../approve`, `.../publish` | `FlowAdminController.java:143`, `:157`, `:171` |
| 9 | Issue a source credential (client id and secret, shown once) | Tenant admin | `Systems` | `POST /admin/systems/{id}/source-credentials`; list `GET`; `POST /admin/source-credentials/{id}/rotate` and `.../revoke` | `SourceCredentialAdminController.java:57`, `:74`, `:92`, `:108` |
| 10 | Ingest. The source gets a client-credentials token from Keycloak and posts `sample.json` with `Authorization: Bearer`, `X-Source-Contract-Version: 1.0` and `Idempotency-Key`. Expected answer: 202 with a tracking id (`NESTED-APPOINTMENT.md:22-24`) | Source system | - | `POST /interfaces/{interfaceCode}/messages` (bulk: `.../messages:bulk`) | `IntakeController.java:118`, `:185` |
| 11 | Transform and deliver: stage 1 creates the delivery, stage 2 applies the mapping and sends; the target answers 200 | Platform | - | Kafka `platform.message.accepted`, then `platform.delivery.due` | section 2 |
| 12 | Track: the timeline shows every stage with its time | Support / any reader | `MessageDetail` | `GET /admin/messages/{trackingId}/timeline` | `MessageTrackingController.java:64`; `MessageEndpointRoles.java:17` |
| 13 | Failure and fix: a failed or dead-lettered delivery appears on Failures; after the cause is fixed, Retry (`RETRY_WAIT` only) or Replay (`DEAD_LETTERED` only), with a confirm step | Support | `Failures` | `GET /admin/messages/failures`; `POST /admin/messages/deliveries/{id}/retry`, `.../replay` | `MessageTrackingController.java:79`; `DeliveryOperatorController.java:61`, `:76` |
| 14 | Queue and counts: queued, processed, converted, failed | Any reader | `Dashboard`, `QueueDashboard` | `GET /admin/queue/summary` | `QueueSummaryController.java:35`, `:51` |
| 15 | Report and audit trail | Reader / tenant admin | `DeliveryReport`, `Activity` | `GET /reports/delivery`; `GET /activity`, `GET /activity/{eventId}` | `DeliveryReportEndpointRoles.java:15`; `ActivityEndpointRoles.java:18` |

The retry and replay rule (retry only from `RETRY_WAIT`, replay only from `DEAD_LETTERED`; a 409 carries the state;
a replay starts a new attempt-budget series) is a recorded design decision of 2026-10-18; the code is in
`DeliveryOperatorController.java`.

**Showcase data.** The source document sent (`NESTED-APPOINTMENT.md:28`):

```json
{"TerminalOpsId":3,"TerminalId":2,"AppointmentId":90726015,"AppointmentDate":"2026-07-09T11:11:05.834Z",
 "AppointmentStatus":"F","NoofVehicle":1,"BerthNo":"68",
 "VehicleList":[{"SlotDate":"2026-07-09T11:11:05.834Z","SlotStartTime":"2026-07-09T14:00:00.834Z",
   "SlotEndTime":"2026-07-09T15:00:00.834Z","DriverPortId":"907261",
   "ContainerList":[{"BookingType":"FO","TransactionID":13,"ContainerNo":"26","Size":"40","Type":"GB",
     "ISOCode":"1232","NoOfPackages":0,"GrossWeight":0,"ActualWeight":0}]}]}
```

What the target receives: one POST with a one-element array of 20 fields, where the appointment and vehicle fields are
repeated on each container record (`NESTED-APPOINTMENT.md:30`):

```json
[{"terminalOpsId":3,"terminalId":2,"appointmentId":90726015,"appointmentDate":"2026-07-09T11:11:05.834Z",
  "appointmentStatus":"F","noOfVehicles":1,"berthNo":"68","slotDate":"2026-07-09T11:11:05.834Z",
  "slotStartTime":"2026-07-09T14:00:00.834Z","slotEndTime":"2026-07-09T15:00:00.834Z","driverPortId":"907261",
  "bookingType":"FO","transactionId":13,"containerNo":"26","size":"40","type":"GB","isoCode":"1232",
  "noOfPackages":0,"grossWeight":0,"actualWeight":0}]
```

Edge cases (`NESTED-APPOINTMENT.md:32-38`):

- Two vehicles with two containers each give a 4-element array.
- An empty `ContainerList` adds nothing; no containers at all gives `[]`.
- Only `AppointmentId` and `ContainerNo` are guaranteed.
- Whole numbers above 2^53 lose precision, so keep long ids as strings.

The original worked example (Customer A to Target A v1.0 and Target B v2.0, section 2) is the Release 1 scenario set
SC-01 to SC-09 in `platform-e2e`.

## 7. Architecture

The authoritative text is [architecture.md](architecture.md). Remember that its section 9 status notes are dated
2026-10-16; read them with section 4 of this document.

### 7.1 Modules

There is one deployable, a modular monolith (ADR-001). Maven modules are listed in `pom.xml:22-44`. The root package is
`io.integrationplatform`; each module's public surface is its base package plus `port` and `event`, and everything else
sits under `.internal` (`AGENTS.md:41-44`).

| Module | Plane | Owns | Must not |
|---|---|---|---|
| `platform-domain` | shared | Ids, value types, the Delivery state register and outcome classes | Depend on Spring or any other module |
| `platform-tenancy` | shared | Tenant context, per-transaction stamping, bootstrap resolvers, OIDC resource server, console login (BFF), Keycloak admin port, role checks | Read a tenant from a body, header or Kafka record |
| `platform-config` | control | Systems, endpoints, encrypted credentials, contracts, interfaces and versions, routes, mappings and mapping versions, rule sets, lookups, retry policies, lifecycle and publish, job definitions and the job API, the admin API, the outbound `DestinationGuard` | Be written by a data-plane module |
| `platform-ingest` | data | The common API, caps, validation, bulk split, idempotency, the intake transaction, the outbox relay | Call a target; use Camel |
| `platform-routing` | data | The only Camel module: stage 1 and 2 consumers, route templates, target adapters (`routing/adapter/{api,mq,kafkatopic,db,file}`), bulkheads, breakers, send-time destination check | Hold retry timing; decide a tenant from a record |
| `platform-transform` | data | JSONata mapping execution (Dashjoin port), sample tests | Use Camel |
| `platform-delivery` | data | Delivery and attempt persistence, outcome classifier, retry policy, ordering hold, re-drive and reconciler, replay, the delivery report | Send anything itself |
| `platform-audit` | shared | Append-only audit and the tenant activity view (registered as a "low-conflict contract surface", `pom.xml:35`) | Update or delete an event |
| `platform-tracking` | data | Tracking and report reads contract surface (`pom.xml:37`) | - |
| `platform-app` | - | The deployable: wiring, security chain, scheduler jobs, actuator, message and queue controllers | Contain business logic |
| `arch-test` | test | ArchUnit rules over every module | - |
| `platform-e2e` | test | Black-box end-to-end harness and the Release 1 scenarios (Testcontainers Artemis and WireMock) | Ship production code |

The module table is from `documents/architecture.md:45-60`. That document still lists a `platform-ledger` module,
which does not exist in `pom.xml`. Tracking reads sit in `platform-tracking` and `platform-app`. The `platform-audit`
and `platform-tracking` modules are small (8 and 10 tracked files).

### 7.2 Dependency rules enforced by arch-test and the build

- The tenant filter is on every repository and raw JDBC access, in every module's package; a planted violation fails
  the build (R08; `documents/architecture.md:150`).
- `org.apache.camel` is allowed only in `platform-routing` (R12; tracker T-121).
- Outbound calls go only through the guard (arch rule `OutboundOnlyThroughGuardRule` in `arch-test`).
- Routing depends only on config's public API, never on its internals (ADR-015 Decision 2, ADR-017).
- Module internals stay private; test-only modules stay test-only (`ModuleScopeGuard`).
- Dependency convergence: exactly one kafka-clients version and one networknt version, and no spring-kafka. This is
  checked by `build-checks/check-dependency-tree.sh` and `check-planted-violations.sh` (`AGENTS.md:65-66`).

The rule sources are in `arch-test/src/test/java/io/integrationplatform/archtest/`, with 11 test classes and 63 `@Test`
methods on `5b9a6c36`.

### 7.3 Tech stack and versions

| Piece | Version | Source |
|---|---|---|
| Java | 25 | `pom.xml:47` |
| Spring Boot (parent) | 4.1.1 | `pom.xml:9-11` |
| Apache Camel Spring Boot | 4.22.1 | `pom.xml:50` |
| Kafka client | 4.3.1 (no spring-kafka) | `AGENTS.md:26` |
| springdoc (OpenAPI at `/v3/api-docs`) | 3.1.1 | `pom.xml:59` |
| PostgreSQL | 17 (`postgres:17.11-alpine`) | `compose/compose.yaml:19` |
| Keycloak | 26.8.0 | `compose/compose.yaml:47` |
| Kafka broker | `apache/kafka:4.3.1` | `compose/compose.yaml:79` |
| Artemis (tests, `e2e` profile) | 2.44.0, digest-pinned | `compose/compose.yaml:134` |
| WireMock | 3.13.1, digest-pinned | `compose/compose.yaml:154` |
| Kafbat UI | digest-pinned (hosted: v1.5.0) | `compose/compose.yaml:111` |
| Mapping engine | JSONata, `com.dashjoin:jsonata` 0.9.10 | ADR-006; `ws/_specs/project-plan.md:84` |
| Rules engine | GoRules ZEN; editor `@gorules/jdm-editor` 1.52.0 | ADR-003; `ui/package.json` |
| Scheduler | db-scheduler | ADR-012 |
| Console | React 18.3.1, Vite, TypeScript, `@xyflow/react` 12.12.0, CodeMirror 6, `openapi-fetch` | `ui/package.json` |
| Browser tests | Playwright | `e2e-ui/package.json` |

The ADR index notes "no Spring Modulith" in the build (`documents/adr/README.md`, ADR-001 status row), although older
planning text mentions it.

### 7.4 Data model essentials

The full table is in `documents/architecture.md:79-89`. In short:

- Every tenant-owned table has `tenant_id` and an RLS policy. Closed sets are `text` with `CHECK`. High-volume tables
  are partitioned by day.
- **Tenancy:** `tenant`, `app_user`, `role_grant`, `source_credential`.
- **Systems:** `system` (type plus typed `settings`), `target_endpoint`, and `target_credential` (envelope-encrypted
  with an out-of-database key, `PLATFORM_CREDENTIAL_KEK_*`).
- **Contracts:** `contract`, `contract_version`.
- **Flow:** `interface`, `interface_version` (an immutable `jsonb` snapshot, with `CHECK` approver is not author),
  `route`, `mapping`, `mapping_version` (immutable, content-addressed, pinned on deliveries; ADR-013), `rule_set`,
  `lookup_table`, `lookup_entry`, and approved tables for DB targets.
- **Intake:** `message`, `payload`, `outbox`, `ordering_key`, `idempotency_key`. Message and payload are
  day-partitioned; payload is kept 30 days (ADR-014).
- **Delivery:** `delivery` (unique `(message_id, route_id, created_at)`, with pinned versions and a claim token),
  `delivery_attempt` (append-only), `delivery_event`, plus the stage timing table.
- **Audit:** `audit_event` (append-only).
- **Scheduler:** its own schema, platform-scoped, with no `tenant_id`.

The schema is owned by Flyway (`V<major>_<minor>_<n>__*.sql` under each module's `src/main/resources/db/migration`),
and JPA only validates.

### 7.5 Kafka topics

Work topics are `platform.message.accepted`, `platform.delivery.due` and `platform.poison`. The hosted kit creates them
with 3 partitions, 7-day retention and replication factor 1 (`deploy/hostinger/kafka/create-topics.sh`). Auto-create
is off, and the app requires `IPASS_KAFKA_PLATFORM_TOPICS` and fails closed without it
(`ws/_documents/2026-10-24-adr-hostinger-hosting.md`, Context). Records carry ids only; the payload stays in
PostgreSQL. There are no retry topics.

A Kafka-topic **target** may never point at a platform topic. The throughput design (tracker T181, "in review") calls
for 12 partitions and N consumer loops per stage. PR #15 `feature/t181-n-consumer-loops` is merged
(`git log`, merge `ad733861`); the partition count in production is not verified.

### 7.6 Identity

- **Keycloak realm `platform`** (`compose/keycloak/platform-realm.json`). Clients: `platform-console` (the console
  BFF), `platform-admin` (service account, used by the app to issue source credentials) and `operator-agent` (service
  account for the AI-agent caller). The hosted realm adds the client `ops-kafbat`, the role `ops-operator` and the flow
  `ops-browser` (password, role gate, OTP) (`ws/_documents/2026-10-26-hosted-deployment-runbook.md:44`).
- **Roles live in our database** (`role_grant`), not as realm roles. The realm export defines no realm roles. The
  tenant is bound from our directory through the login bootstrap function (ADR-009), never from a token claim the
  client controls.
- **Console = BFF.** The browser holds only the backend's HttpOnly, Secure `SESSION` cookie, sessions are stored in
  PostgreSQL, and the UI must be same-origin with `/api`, `/oauth2`, `/login` and `/logout` (hosting ADR, Context).
- **Source systems** use OAuth2 client credentials. The (issuer, client id) pair maps to source system, customer and
  tenant.
- **`OPERATOR_AGENT`** is the first no-tenant principal. It reaches only job and alert operations, and every
  tenant-owned policy errors for it (`documents/architecture.md:164-168`).

### 7.7 Multi-tenancy isolation controls

The closed register is in `documents/architecture.md:147-177`:

| # | Control | Proven by |
|---|---|---|
| T1 | Tenant from the authenticated principal through our database only | Forged-tenant denial suite (1,225 tests at `347a1466`) |
| T2 | Every query carries the tenant filter, enforced by ArchUnit | Planted-violation test |
| T3 | RLS on every tenant-owned table; error-deny on an empty setting | RLS-bypass negative suite |
| T4 | The tenant setting is stamped per transaction only | Pooled-connection test |
| T5 | Exactly four definer functions cross the boundary: `ipaas_login_bootstrap`, `ipaas_client_bootstrap`, `ipaas_work_tenant`, `ipaas_active_tenants` | One negative test each; a fifth needs an ADR |
| T6 | The app role has no `BYPASSRLS` and no DDL; maintenance runs as `ipaas_maintenance` | Grant inspection test |
| K1 | The app refuses to start if its DB login is superuser, can bypass RLS, or owns the tables | `DatabaseRoleGuard` (hosting ADR, Context) |

Background jobs and consumers take the tenant from the RLS-protected row. Reviewed crossings into
`platform-tenancy.internal` carry `@TenantBoundaryCrossing`.

### 7.8 Resilience rules

- Every outbound call carries the five controls: timeout, transient-only retry with backoff, jitter and a cap, one
  circuit breaker per dependency, a fallback, and metrics plus a trace span (R13). Targets also get a per-target
  bulkhead.
- There are exactly two retry layers: Camel in-attempt redelivery (seconds), and the persisted Delivery state machine
  (long horizon).
- A timeout after send is ambiguous. It is retried only if the target declares idempotent receive; otherwise it is
  dead-lettered `AMBIGUOUS_TIMEOUT`.
- Nothing stays stuck: holds are released in the same transaction, a reconciler runs inside the delivery-retry job, and
  consumers pause on a transient database error.
- Alerts fire on: outbox age over 60 s, consumer lag over 1,000 records or 2 minutes, any poison arrival, and a hold
  older than 1 hour (`documents/architecture.md:205-212`).
- **Destination controls:** refused ranges include loopback, private, link-local, cloud metadata, carrier-grade NAT,
  IPv6 unique-local and the platform's own hosts. The resolved address is pinned, every redirect is re-checked, File
  paths are confined to a root, and the allow-list fails closed (ADR-010 section 8, amended 2026-10-19).

## 8. Deployment and environments

**Operational detail is in [deployment.md](deployment.md). Read it instead of this section for any operator task.**
It covers the two ways to run, links, services, image pins, the local stack, the hosted kit and its tests, edge rules,
redeploy and rollback, the ops address, the master realm, security status and open items.

| Environment | What | Owner |
|---|---|---|
| Local compose | `compose/compose.yaml`: PostgreSQL, Keycloak (realm imported), Kafka, Kafbat; the `e2e` profile adds Artemis and WireMock. The app runs from Maven or the IDE. Variable names are in `compose/compose.env.example` | Each developer |
| Test harnesses | Testcontainers in `-Pcontainer` and `-Pe2e-full`, plus the `e2e-ui` boot scripts, which start their own containers | Developers and QA |
| Hosted showcase / staging | `deploy/hostinger/` on a single VPS behind the host's Traefik. **Fictional showcase data only, no real customer data** (`deploy/hostinger/README.md:3`) | The project owner holds the server, accounts, secrets and every production step |

**The three hosted links** (verified from outside on 2026-10-26, `ws/_documents/2026-10-26-hosted-deployment-runbook.md:19-23`):

| Link | Answer | Purpose |
|---|---|---|
| `https://ipass.dipeshpansuriya.tech` | 200 | App UI; sign-in redirects to Keycloak |
| `https://auth.dipeshpansuriya.tech/realms/platform/account` | 200 | Keycloak login and account (realm `platform`) |
| `https://ops.dipeshpansuriya.tech/kafka/` | 302 to login | The ops address: Kafbat, read-only, no message bodies, password plus OTP, role `ops-operator` |

Not public on any host: Keycloak `/admin` and the master realm, `/actuator`, `/metrics`, `/health`, `/v3`, swagger,
Postgres 5432, Kafka 9092 and the WireMock admin. Reach those over SSH only.

**Current hosted state** (2026-10-26):

- The running app tag is whatever `IPASS_APP_IMAGE` in the server `.env` says; read it with
  `grep '^IPASS_.*_IMAGE=' .env` (names and tags only). This document does not state a fixed tag.
- Docker Hub also holds a newer app image `4e0730a6`, pushed on 26 October, whose deployment is not confirmed here.
  The runbook of the same morning lists app `8ead16cc` and ui `3105c3d3`
  (`ws/_documents/2026-10-26-hosted-deployment-runbook.md:32`).
- Images are on Docker Hub. `deploy/hostinger/README.md:27` and `:29` also document a private-registry prefix form; the
  hosted copy uses the Docker Hub overrides.

**Who owns what:**

| Area | Owner |
|---|---|
| Server, DNS, registry accounts, secrets, rotation, production deploys, the master admin OTP, operator accounts | The project owner |
| Code changes to the kit (`deploy/hostinger/**`) | Developers, through a PR, with the security reviewer checking edge and identity changes |

## 9. Security

### 9.1 Findings status

| Item | Severity | Status | Source |
|---|---|---|---|
| Release 1 security statement | - | "Partly executed", "lane runs plus review"; **not a pass** | section 4.6 |
| R1/F7 Kafka plaintext to an advertised internal node | Conditional High | **Accepted** for Release 1 with the plaintext switch off everywhere and no egress policy; not closed. The deployed value is not verified | `ws/_documents/2026-10-22-release1-security-statement-and-risk-acceptance.md:33-42` |
| R3 DB targets `sslmode=require` without chain verification | - | Accepted for Release 1 | same, `:30` |
| R2 existing KAFKA rows without protocol fail TLS (migration or release note) | - | Open, owner decision | same, `:40` |
| H1 Keycloak console public with a single factor | High | **Closed** 2026-10-26 (route removed) | runbook `:63` |
| N1 request splitting into Kafbat; N2 double-encoding bypass | - | **Closed** (edge refuses `%` and dot segments) | runbook `:64` |
| M1 h2c Upgrade hang | Medium | **Closed** | runbook `:65` |
| L5 platform audience on `ops-kafbat` | Low | Closed in code and setup; token check **not verified** (needs a login) | runbook `:66` |
| N4 case-sensitive literal-prefix blocks in `edge.conf` | Low | **Open**; anonymous impact nil; fix at the next edge change | `ws/_reports/2026-10-26-t219-ops-security-recheck2.md:48`, `:94` |
| M2 Kafbat reaches a plaintext, ACL-less Kafka | Medium | **Open**; design item: SASL/ACL and its own network | `ws/_reports/2026-10-25-t219-ops-security.md:36`, `:116` |
| Master realm admin has no OTP | - | Approved, not done (T220) | runbook `:46`, `:70` |
| Audit leg of four gate cases (refusal asserted, audit row not) | - | Known gap; mandatory before any "pass" claim | statement document, section 3 |
| PATH_ID denial record C3 (before the audit writer replaces the logging recorder) | Low | Open gate | statement document `:114` |
| Issuer-validator removal survived mutation | Low | Follow-up done (tracker T209 closed) | verdict report `:53`; tracker |
| Per-environment probes P1-P9 | - | Open before external exposure | section 4.6 |
| `.env.kc-admin` in the stack directory | - | Open: move it to the credential store | runbook `:79` |
| No edge access log, so earlier `/admin` hits cannot be proven absent | - | Open | runbook `:80` |

### 9.2 Hosted hardening done on 25-26 October

All of this is from the T219 work: `ws/_documents/2026-10-26-hosted-deployment-runbook.md` sections 3, 4 and 6, and
`deploy/hostinger/README.md` "Operator address".

- The ops address serves only Kafbat at `/kafka/`, behind a Keycloak login (client `ops-kafbat`, auth-code only,
  default scope `basic`), role `ops-operator` and OTP (flow `ops-browser`, bound to that client only).
- On ops: GET only on the API; 404 for messages, serdes, smartfilters, produce and actuator; 404 for any path with `%`,
  `;`, backslash, `//` or dot segments (including directly before a query string); no `Upgrade` or `Connection`
  forwarded.
- Keycloak `/admin` and the master realm are 404 on every public host; admin work is done with `kcadm.sh` in the
  container over SSH.
- The master realm has a named admin only, brute-force detection on, `bootstrap-admin` removed and the frontend URL
  unset.
- Auth and login paths are rate-limited, including the trailing-slash forms.
- The app issues source credentials through the internal Keycloak address (`KEYCLOAK_ADMIN_BASE_URI`, unset on hosted,
  which means the internal default). Verified live on 2026-10-25 and 2026-10-26.
- A backup directory is taken before every change. Rollback steps are in [deployment.md](deployment.md) section 6.1.

### 9.3 Rules for any new endpoint

1. **Tenant from the principal only.** Never read a tenant id from the body, a header, the path or a Kafka record. Run
   every tenant-scoped access inside a tenant-stamped transaction (R11).
2. **Add a role check on the server.** Add the endpoint to the role policy, and add a failing test per role first (R01).
   Also add it to the forged-tenant denial matrix (`ForgedTenantDenialSuiteTest`); a missing entry has turned the
   container gate red before.
3. **Return 404, not 403, for another tenant's id**, so existence does not leak. Put a `::uuid` guard on path ids, so a
   malformed id gives 404 or 400, never 500 (tracker T197 R3).
4. **Clean 4xx on bad input**: malformed body 400, wrong content type 415, oversized 413, and no stack trace or input
   echo in the body. Error handling outside `io.integrationplatform.config` is an open design item (tracker T207,
   section 13).
5. **Masked fields never appear** in logs, response bodies or screens. Secrets are write-only.
6. **Outbound calls go only through `DestinationGuard`**, with the five controls (R13).
7. **Declare the real responses in OpenAPI**, then regenerate the typed client (`cd ui && npm run generate:client`).
   The contract test fails on drift.
8. **Update the docs**: [interface.md](interface.md), and a diagram if the change alters it (R21).
9. **Hosted exposure**: a new path is 404 at the edge until it is added to `deploy/hostinger/edge/edge.conf` on
   purpose. After that change the edge container must be recreated (section 12.5).

## 10. How to run and test

### 10.1 Prerequisites

- **JDK 25.** Export `JAVA_HOME`; the wrapper refuses without it (`AGENTS.md:60`). Use `./mvnw`, not a system Maven.
- **Docker** for any container test or the local stack. Use Docker Desktop on Windows.
- **Node and npm** for `ui/` and `e2e-ui/`. Run `npm ci` in **both** folders (`AGENTS.md:29`, `e2e-ui/README.md:13`).
  The Node version is not pinned in the repository (not verified).
- **Git Bash** on Windows for the `.sh` scripts.

### 10.2 Environment for every Maven run

```bash
export JAVA_HOME=/path/to/jdk-25
export JAVA_TOOL_OPTIONS=-Duser.timezone=Etc/UTC   # R04: the Postgres container rejects some host zones
export MAVEN_OPTS=-Xmx768m                          # AGENTS.md:61; an uncapped JVM ran out of commit memory
```

`rule.md` R06 gives tighter flags (`MAVEN_OPTS="-Xmx384m -Xms64m -XX:MaxMetaspaceSize=256m -Xshare:off"`). The later
`AGENTS.md` uses `-Xmx768m`, and the `container` and `e2e-full` profiles set their own surefire JVM flags
(`pom.xml:315-345`). **Never pass a bare `-DargLine`**: it overrides the pom's flags (`AGENTS.md:62`).

### 10.3 Maven commands

| Goal | Command | Needs Docker | Expected green result (last known) |
|---|---|---|---|
| Unit tests and arch rules | `./mvnw test` | No (R03: container tests are excluded by default) | BUILD SUCCESS. arch-test 66/0 at `347a1466` |
| One module | `./mvnw -pl <module> -am test` | No | - |
| Container suite | `./mvnw -Pcontainer test` (runs only `@Tag("container")`) | Yes, take the lock | 1,902 tests, 0 failed, 5 skipped at `347a1466` |
| Full end-to-end gate (project-close gate) | `build-checks/e2e-full-gate.sh` (wraps `./mvnw -o -Pe2e-full -pl platform-app -am test` with the lock and the verdict) | Yes | `GATE: PASS` 10/10 at `347a1466` (`documents/e2e-full.md`) |
| Release 1 Java scenarios | `node e2e-ui/scripts/run-release1-gate.mjs <log>` (Maven `-Pcontainer -pl platform-e2e -Dtest=Release1ScenariosContainerTest` plus its own Artemis, backend boot and seed) | Yes | `RELEASE 1 GATE: PASS` |
| Build the deployable jar | `./mvnw -pl platform-app -am package -DskipTests` | No | - |
| Build checks | `bash build-checks/check-dependency-tree.sh`, `check-planted-violations.sh`, `check-agent-guidance.sh`, `check-provenance.sh`, `test-e2e-full-gate.sh` | No | Exit 0 |

Notes:

- `Release1ScenariosContainerTest` errors under a plain `-Pcontainer` run **by design**: it needs the gate runner
  (`ws/_reports/2026-10-24-release1-verdict-and-proof.md:45`).
- Before any `-rf` resume, run `./mvnw install -DskipTests -am`, otherwise stale local-repository jars are used
  (`AGENTS.md:62-63`).
- `platform-app` test-compile needs `-pl platform-app -am`.
- Surefire `-Dtest='*Scheduler*'` does not match the `io.integrationplatform.scheduler.*` container proof classes
  (case mismatch). Name those classes explicitly.

### 10.4 Console (`ui/`) and console end-to-end suites (`e2e-ui/`)

| Goal | Command | Expected green result (last known) |
|---|---|---|
| Install | `cd ui && npm ci && cd ../e2e-ui && npm ci` | - |
| Dev server | `cd ui && npm run dev` | - |
| Build (type check, Vite build, licence check) | `cd ui && npm run build` | Exit 0 |
| All offline unit tests | `cd ui && npm test` (ui's own tests, then `e2e-ui` unit tests) | 387 + 112 = 499 tests at `7b916dd7` (2026-10-27); 3 `ui` tests red, see below |
| ui-only unit tests | `cd ui && npm run test:product` | 387 run, 384 pass, 3 fail at `7b916dd7`: `ui/tests/ui/no-allow-list-editor.test.mjs:39-41` and the allow-list endpoint test still forbid the Settings page that PR #34 added (decision open) |
| Regenerate the typed API client from OpenAPI | `cd ui && npm run generate:client` | `tsc` clean |
| Screens suite (Playwright, real backend) | `cd e2e-ui && npm run test:screens` | 128/128 at `0b4cbf04`; `playwright test --list` shows 156 tests in 20 files at `7b916dd7` (listed, not run) |
| Base suite | `cd e2e-ui && IPASS_BS_EXCEPTION_PROFILE=showcase IPASS_BS_EXCEPTION_HOST=localhost IPASS_BS_EXCEPTION_HOST_VAR=PLATFORM_CONFIG_DESTINATIONS_SHOWCASE_LOCAL_HOSTS IPASS_BS_EXCEPTION_PORT=8089 npx playwright test -c playwright.base-suite.config.mjs` (all four `IPASS_BS_EXCEPTION_*` are required, else every scenario fails NOT_CONFIGURED: `e2e-ui/tests/base-suite/lib/settings.mjs:7-21`; environment list below the table) | BS-10, BS-11.3 and BS-11.3b gave 20/20 at `0b4cbf04`; `--list` shows 30 tests in 11 files at `7b916dd7` (listed, not run; the f3 scenarios 5-9 and 11 need a DB-target seam) |
| Security specs | `cd e2e-ui && npx playwright test -c playwright.security.config.mjs` | 13/13 at `0b4cbf04`; `--list` 13 at `7b916dd7` |
| Release 1 browser journey | `cd e2e-ui && npm run test:release1-journey -- <log>` | PASS, 6 steps |
| e2e-full UI leg | `cd e2e-ui && npm run test:e2e-full-ui` | not verified |
| Mocked UI / OpenAPI contract / perf | `npm run test:mocked`, `test:container`, `test:perf` (in `e2e-ui`) | not verified |
| Local trial stack (backend, console, seeded user) | `cd e2e-ui && npm run trial` | - |

Environment for the base suite and the other Docker-backed Playwright runs (the boot script reads these; `e2e-ui/scripts/boot-backend.mjs`):

- `IPASS_BS_EXCEPTION_PROFILE=showcase`, `IPASS_BS_EXCEPTION_HOST=localhost`,
  `IPASS_BS_EXCEPTION_HOST_VAR=PLATFORM_CONFIG_DESTINATIONS_SHOWCASE_LOCAL_HOSTS`, `IPASS_BS_EXCEPTION_PORT=8089`: the
  dev-only outbound exception (the one local WireMock). WireMock is published on 8089 and the console on 4173 when free;
  an overlapping run claims another port (`boot-backend.mjs:609`).
- `DOCKER_SUITE_LOCK=<path>`: use a numbered lock slot such as `.docker-suite.lock-1` instead of the default
  `.docker-suite.lock` (`boot-backend.mjs:40`).
- `JAVA_TOOL_OPTIONS=-Duser.timezone=Etc/UTC` (R04; the boot adds it to its children too, `boot-backend.mjs:180`).
- A JDK 25: `JAVA_HOME`, else `java` on PATH, else the newest JDK 25 under `~/.jdks` (`boot-backend.mjs:221-244`).
- The base-suite boot also sets `IPASS_DB_TARGET_ENABLED=true` for the app (`e2e-ui/tests/base-suite/lib/boot-options.mjs`).

Every `ui` script name still works and delegates to `e2e-ui`. To pass extra arguments, run the script inside `e2e-ui`
(`e2e-ui/README.md:23`).

### 10.5 Hosted kit tests

Run these from the repository root (full table in [deployment.md](deployment.md) section 4):

- `bash deploy/hostinger/test/test-scripts.sh`: offline, no Docker. Last known 111 passed, 0 failed (2026-10-26).
- `NGINX_TEST_TAG=<tag> bash deploy/hostinger/test/edge-ratelimit.sh` and `.../edge-ops.sh`: nginx behaviour; take
  the Docker lock.
- `IMAGE_TAG=<sc> bash deploy/hostinger/test/smoke.sh`: the whole stack with dummy secrets. Heavy; takes the lock.
  Last known 30/30 on images `3105c3d3` (2026-10-24, planning-workspace report
  `2026-10-24-hosting-smoke-30of30-images-3105c3d3.log`).

### 10.6 The Docker suite lock (R06)

- **One Docker or Testcontainers suite at a time on a build machine.** Two at once have wedged the host
  (`ws/_documents/2026-10-24-demo-runbook.md` section 4).
- The machine-wide lock file is `.docker-suite.lock`, **one level above the repository root** (`AGENTS.md:58-59`).
  Take it with a holder note (who, which suite, when) and release it when the run ends.
- The first holder keeps it. Never remove another holder's lock.
- `build-checks/e2e-full-gate.sh` and the `e2e-ui` boot scripts take the lock themselves. The boot scripts accept
  `DOCKER_SUITE_LOCK=<path>` to use a numbered slot such as `.docker-suite.lock-3` (demo runbook section 4).
- The hosted-kit nginx tests exit 2 without starting when a live owner holds the lock (`deploy/hostinger/README.md:84`).

### 10.7 Windows notes

- Use Git Bash for scripts. Use `cmd` for the Windows-style commands in the demo runbook.
- Set the time zone flag every time (R04).
- Ctrl-C cleanup of the journey runner cannot be proven on Windows, because Windows does not deliver a catchable
  SIGINT to another process (verdict report `:62`). If you kill a run, clean up its containers and its lock by hand.
- Use `127.0.0.1`, not `localhost`, for the console login in the local demo (demo runbook section 7).
- Ports 8089 (WireMock) and 4173 (console) must be free before a demo or gate run
  (`netstat -ano | findstr ":8089 :4173"`).

## 11. How to work on this code

### 11.1 Branching and pull requests

1. Fetch, then branch off a fresh `origin/main`: `feature/<id>-<topic>`, `fix/<id>-<topic>` or `docs/<id>-<topic>`.
   These are the prefixes in use, for example PR #20 `feature/t219-ops-n1` and PR #5 `fix/idp-admin-refuse-unsafe`.
2. Commit on the branch only. **Never commit to `main` directly.** In the current owner's clone a local `pre-push` hook
   refuses any push of `main` unless `ALLOW_MAIN_PUSH=1` is set for that one push
   (`ws/_documents/2026-10-22-release1-security-statement-and-risk-acceptance.md:104`). Set up the same guard in your own
   clones.
3. Open a pull request to `main` on the hosting service. PRs #4 to #20 have used this flow since the `release-1` tag
   (`git log --merges 0b4cbf04..5b9a6c36`).
4. Get a code review, and a security review for anything touching identity, tenancy, outbound calls, credentials or the
   edge. The gates in section 10 must be green on the PR head, with results stated as counts.
5. Merge to `main` only with the project owner's sign-off. R17 requires the owner's word for every push, remote,
   pull request and merge (`documents/rule.md:51`). Under the current practice, routine feature-branch pushes and PRs
   are normal and the merge to `main` is the owner's sign-off.
6. Never use `--no-verify`, never force-push, and never rewrite history.
7. After a merge, a red test closes only when it is re-run green on the merged tip.

### 11.2 Conventions

- **Package layout:** `io.integrationplatform.<module>`. The public surface is that package plus `port` and `event`;
  everything else lives under `.internal`. Hexagonal-style `adapter/in` and `adapter/out`, `application` and `domain`
  inside `platform-config` (for example `config/flow/adapter/in/FlowAdminController.java`).
- **Migrations:** Flyway, one per change, never edited after they reach `origin/main`. Under the versioned name
  pattern `V<major>_<minor>_<n>__<what>.sql` the versions must stay strictly increasing; a migration was renamed for
  strict Flyway ordering before. Closed sets use `text` plus `CHECK` (R10).
  Explicit `?::uuid` casts (R09).
- **No secrets in files:** only variable names; `.example` files list names. Values come from the OS secret store or
  the server's root-only `.env`.
- **Pinned images and dependencies:** never `latest` (R05).
- **Diagrams:** `documents/diagrams/` holds the archify specs and pages. Update and re-render them in the same change
  (R21). No image files are committed.
- **Naming:** English only. No employer, client or product names in any file (R19).
- **UI:** match the existing design system in `ui/src/tokens.css` and `ui/src/ui/base.css` (light and dark token
  pairs, slate plus blue). Keep WCAG AA contrast, landmarks, keyboard support and dialogs with Esc and focus return;
  the token and contrast tests in `ui/tests/ui/` assert these. Do not introduce a second component library.

### 11.3 Definition of done (per story)

1. A failing test existed before the code (R01), and it now passes.
2. The acceptance criteria are met, with evidence as run counts (R02).
3. Unit tests and arch rules are green. Container tests are green under the lock if the change touches persistence,
   Kafka, Camel or HTTP. UI tests are green if the change touches `ui/`.
4. Diagrams and documents are in sync (R21); `interface.md` is updated for any API change.
5. No new secret, unpinned image or extra kafka-clients or networknt version (build checks green).
6. The work has been reviewed. A security review is required for identity, tenancy, outbound, credential and edge
   changes.

The backlog's definition of done adds the diagram rule explicitly (`ws/_specs/backlog.md:39-48`).

### 11.4 ADR process

- ADRs live in `documents/adr/ADR-NNN-<title>.md`, with the index in `documents/adr/README.md`. The next number is
  ADR-019.
- Sections: Context, Options, Decision, Consequences, Revisit if. State what the decision gives up.
- Write the ADR **before** the code it governs. A change to a closed register (states, target types, topics, definer
  functions, roles, job types, exemption tables) goes through its owning ADR (`documents/adr/README.md`, "Closed
  registers").
- Team decisions are marked "Accepted (team) - the owner may reverse". Owner-approved ones say so with a date.
- Two hosting ADRs (ADR-H-IPASS-01 hosting, ADR-H-IPASS-02 operator dashboards) are currently kept in the planning
  workspace, not in `documents/adr/`. Ask for them, or move them in through a PR.

## 12. Guide for AI agents

This section is directive. Follow it together with `AGENTS.md`; where they disagree, `AGENTS.md` and `documents/` win.

### 12.1 Repository map

Top-level directories in `git ls-files` on `5b9a6c36` (1,790 tracked files). That makes 18 directories, plus `.mvn`
and `_memory`:

| Directory | Owns | Tracked files |
|---|---|---|
| `platform-domain` | Ids, value types, delivery states, outcome classes; no Spring | 24 |
| `platform-tenancy` | Tenant context, RLS stamping, bootstrap resolvers, OIDC, console BFF login, roles (`Role.java`, `RolePolicy.java`) | 146 |
| `platform-config` | Control plane: systems, credentials, contracts, flows, routes, mappings, rules, lookups, jobs, the admin API, `DestinationGuard` | 291 |
| `platform-ingest` | The common intake API, validation, bulk, idempotency, the outbox relay | 96 |
| `platform-routing` | The only Camel module: stage consumers, route templates, adapters (api, mq, kafkatopic, db, file), bulkheads, breakers | 311 |
| `platform-transform` | JSONata mapping execution | 11 |
| `platform-delivery` | Delivery and attempt persistence, retry, hold, re-drive, replay, delivery report | 105 |
| `platform-audit` | Append-only audit and the activity view | 8 |
| `platform-tracking` | Tracking and report reads | 10 |
| `platform-app` | The deployable: wiring, security chain, scheduler jobs, message, queue and activity controllers, the e18 suite in `src/test` | 248 |
| `arch-test` | ArchUnit rules (test-only) | 56 |
| `platform-e2e` | Black-box Release 1 scenarios and harness (test-only) | 31 |
| `ui` | React console (product code and product unit tests) | 170 |
| `e2e-ui` | Playwright suites and runner scripts (test-only) | 182 |
| `compose` | Local development stack and Keycloak realm export | 8 |
| `deploy` | Hosted kit (`deploy/hostinger/`) | 30 |
| `documents` | PRD, architecture, rules, design, interface, fetch, ADRs, diagrams, ops runbooks | 43 (before this file) |
| `build-checks` | Dependency, planted-violation, guidance, provenance and e2e-full gate scripts | 11 |

`_memory/` holds one stale open-issues stub ("Fresh baseline - no open items"); use section 13 instead.

### 12.2 Read first, in this order

1. `AGENTS.md`
2. This file, sections 2, 4 and 13
3. `documents/rule.md`
4. `documents/architecture.md` sections 2 to 7
5. `documents/adr/README.md`, then the ADRs for the area you touch (ADR-010 for routing, ADR-011 for contracts,
   targets and ordering, ADR-007 and ADR-009 for identity and tenancy, ADR-012 for jobs)
6. `documents/interface.md` for any API change; `documents/design.md` for any UI change
7. The module's existing tests next to the code you change

### 12.3 Before and after every change

Before you change anything:

```bash
git fetch origin && git switch -c fix/<id>-<topic> origin/main
export JAVA_TOOL_OPTIONS=-Duser.timezone=Etc/UTC MAVEN_OPTS=-Xmx768m   # plus JAVA_HOME (JDK 25)
./mvnw -q test                                                       # the baseline must be green first
```

Then:

1. Find every caller of what you will change (`git grep`, or an IDE call hierarchy) and list the modules you touch.
   Three or more modules, or anything in tenancy, means design first (an ADR or an issue).
2. Write the failing test first and commit it.

After you change something:

```bash
./mvnw test                                   # unit and arch-test, always
./mvnw -pl <module> -am -Pcontainer test      # if persistence, Kafka, Camel or HTTP changed; take the Docker lock first
cd ui && npm test && npm run build            # if ui/ changed
bash build-checks/check-dependency-tree.sh    # if any pom changed
```

Report the counts (run, failed, errors, skipped) and the commit id.

### 12.4 Rules you must not break

1. **Tenant isolation.** The tenant comes only from the authenticated principal through the database. Never take it
   from a body, header, path or Kafka record. Never weaken RLS, never add a `BYPASSRLS` login, never add a fifth definer
   function without an ADR. A new endpoint is added to the forged-tenant matrix.
2. **No secrets in files.** Use variable names only. Never print a `.env`, a token or a password. Never paste a
   credential into an issue, PR or log.
3. **No direct commits to `main`.** Use a branch and a PR (section 11.1). Never force-push or rewrite history.
4. **Never skip hooks.** No `--no-verify`. Fix the cause of a failing hook.
5. **Ask before anything destructive or outward.** That means: deleting files or branches, dropping tables or
   partitions, editing a migration already on `origin/main`, any production or hosted step, rotating a credential,
   or pushing images. Stop and ask the project owner.
6. **Camel only in `platform-routing`; no spring-kafka; one kafka-clients version** (R12).
7. **The five controls on every outbound call**; outbound calls go only through `DestinationGuard` (R13).
8. **The UI matches the existing design system** (tokens and base styles). Accessibility is part of done: WCAG AA
   contrast, labels, landmarks, keyboard support and focus management.
9. **Pinned image tags and dependency versions**; never `latest`.
10. **Test first, counts not ratios.** Never claim "green" without the command and its counts.
11. **One Docker suite at a time**, under the lock.
12. **Do not quote security figures that are not current.** The Release 1 statement is "partly executed" (section 4.6).

### 12.5 Traps found in this project

| Trap | What to do |
|---|---|
| Unit run silently runs container tests | It does not: container tests carry `@Tag("container")` and need `-Pcontainer` (R03). Do not remove the exclusion |
| Postgres container FATAL invalid TimeZone | Set `JAVA_TOOL_OPTIONS=-Duser.timezone=Etc/UTC` on every run (R04) |
| Out-of-memory or commit-charge exhaustion on Windows | Cap `MAVEN_OPTS`; never a bare `-DargLine`; one suite at a time |
| `-rf` resume uses stale jars | Run `install -DskipTests -am` before resuming |
| `ui npm test` fails on a fresh checkout | `npm ci` in both `ui/` and `e2e-ui/` |
| `ui/node_modules` in a fresh git worktree | A worktree has no `node_modules`. Run `npm ci` there. Linking the main checkout's folder with a Windows directory junction is a local convenience that is **not verified** for this repository; never commit `node_modules` |
| `edge.conf` change has no effect on the server | It is a single-file bind mount. Run `docker compose up -d --force-recreate --no-deps edge`; `nginx -s reload` is not enough. The same applies to `kafbat/kafbat-roles.yml` (`deploy/hostinger/README.md`, "Operator address") |
| `ops-setup.sh` re-enables the `ops-kafbat` client | Do not re-run it if you disabled the client on purpose |
| Keycloak admin console over an SSH tunnel does not log in | That is by design (strict hostname). Use `kcadm.sh` in the container; do not set the master realm frontend URL without a decision |
| App healthy but scheduled jobs missing | The app DB login must be granted `ipaas_app` before the first start (outside Flyway); otherwise the job register stays empty (tracker T198, B35) |
| App refuses to start | K1 guard: the DB login is superuser, bypasses RLS or owns the tables. Use the restricted login |
| Release 1 scenarios error under plain `-Pcontainer` | By design; use the gate runner |
| A Kafka topic name that needs URL-encoding is 404 on the ops address | By design (the edge refuses `%`) |
| A drawn mapping drops or cannot repeat a parent field per child | Use the raw JSONata editor for such mappings and do not re-save from the graph view (`NESTED-APPOINTMENT.md:5`) |
| Numbers above 2^53 in JSONata | They lose precision; keep long ids as strings |
| Known flaky tests | `SchedulerIncidentAlertsContainerTest` (harness timing race, one red in four runs, recorded 2026-10-16); `DeliveryRetryTriggerTest` (tracker B24, in review); `DeliveryDueDispatchTest` RecordingSend race (tracker T154). Re-run once, isolated, before you call a red real, and report both runs |
| `npm test` in `ui/` or `e2e-ui/` fails with "Could not find" | Five test files named in `e2e-ui/package.json` are missing on `main` (section 13, item 16); restore them, or run `cd ui && npm run test:product` for the ui-only tests |
| Fixed ports 8089 and 4173 shared by the gate and the demo | Do not run them together |
| Docs say "not wired" | `documents/*` status notes are frozen at 2026-10-16; check the code and section 4 |

### 12.6 Finding an owner or a decision

- **Requirements and acceptance criteria:** `documents/PRD.md`, then the project owner.
- **Design decisions:** the ADR index. A missing decision is a new ADR proposed by the architect role and approved by
  the project owner.
- **Security severity and acceptance:** the security reviewer role. Accepting a risk is the project owner's call.
- **Anything in section 3.5 or 13 marked "owner decision":** ask the project owner. Do not decide it in code.

### 12.7 How to report

Hand back each task in four parts:

- **Done:** what changed, with the branch, commit and PR.
- **Decisions:** what you chose and why.
- **Open:** what is left, and any question in the form "Needs decision: ... (recommend ...)".
- **Limits:** what you could not run or check, with each unchecked claim marked "not verified".

Every test claim states the command, the counts and the commit.

## 13. Open items and risks

### 13.1 Ranked list

The list is ranked by risk to tenants and to an external exposure first, then by risk to Release 1 closure, then by
Release 2 work. "Tnnn" and "Bnnn" are project tracker items (section 14).

| # | Item | Why it matters | Owner role | Next step |
|---|---|---|---|---|
| 1 | **R1/F7 Kafka plaintext** (standing conditional High) and the **per-environment probes P1-P9** | Any external exposure without them reopens the High or leaves deployed config unproven | Project owner / operator, with the security reviewer | Run P1-P9 per environment and record the plaintext switch value before each promotion (`ws/_reports/2026-10-23-security-statement-refresh.md:139-147`) |
| 2 | **Release 1 security statement is not a pass** | No "secure" claim can go outward | Security reviewer | Recompute the case map from filed runs at one release commit; close the audit legs; re-run the gate at the tip |
| 3 | **M2: Kafbat in front of a plaintext, ACL-less broker; the Kafka login layer** | Wide internal reach behind one login | Architect, then security reviewer | Design SASL/SCRAM plus ACL (Describe/Read only for Kafbat) and its own network before real tenant traffic (`ws/_reports/2026-10-25-t219-ops-security.md:116`) |
| 4 | **T220: master realm admin OTP** | The master admin is single-factor (console reachable over SSH only) | Project owner (needs an authenticator at hand) | SSH tunnel plus a temporary frontend URL, pg dump first, about 20 minutes (runbook `:76`) |
| 5 | **Operator users for the ops address** | None exist, so the operator login end to end (and the L5 token check) is untested | Project owner | Create named users in realm `platform` with role `ops-operator` and required action `CONFIGURE_TOTP`; enrol in person |
| 6 | **N4: case-sensitive blocks in `edge.conf`** (Low) | Case-altered paths fall through to the GET-only catch-all | Developer (kit), security reviewer | Case-insensitive matching at the next edge change, bundled with M2; add case variants to `test/edge-ops.sh` (`ws/_reports/2026-10-26-t219-ops-security-recheck2.md:94-96`) |
| 7 | **T207: 405/406/404 raised before a controller is chosen bypass `ConfigApiExceptionHandler`** (`@RestControllerAdvice basePackages=io.integrationplatform.config`) | Inconsistent error bodies app-wide | Architect (design call), then developer | Decide: widen or drop `basePackages` app-wide; test 405/406/type-mismatch (tracker T207, T196) |
| 8 | **Open P1 defects**: B33, B35 (scheduler schema permission in one stack), B36 / T206 (BS-11 probe data), T195 (`CIRCUIT_DEFERRED` not copied back by `LayerOneRouteTemplate`), T199 (approval holds a connection while dialling a partner DB; MQ system saved without a contract), T201, T202, T205 | Release 1 hygiene; T195 and T199 are product defects | Developer, QA | Work them test-first on branches (tracker, 2026-10-26) |
| 9 | **Tracker lag**: many items "in review" whose code is merged; E16 and E18 at 0% while their gates passed | Misleading status for planning | Project owner / delivery lead | Close each against its evidence or reopen it |
| 10 | **Docs status notes frozen at 2026-10-16** (`PRD.md` section 10, `architecture.md` section 9, the ADR index status table) | Agents read "not wired" for wired code | Architect | Refresh against `main` in one docs PR |
| 11 | **Release 2 re-estimate and schedule** | The dates in section 5.4 are stale | Architect with the development lead; project owner approves | Re-plan E16 to E18 closure from current state |
| 12 | **Throughput**: one consumer loop per stage gave about 15-22 records per second; T181 (N loops, 12 partitions) merged; T179 design in review | The plan's 50 msg/s plus a 10x burst is unproven | Architect, developer | Load test after the T181 merge (`ws/_documents/2026-10-22-design-throughput-ceiling.md`) |
| 13 | **Not-covered test gaps accepted for Release 1** (eight items) | Known holes in proof | QA | `ws/_reports/2026-10-24-release1-verdict-and-proof.md:55-64` |
| 14 | **Secrets hygiene on the server**: `.env.kc-admin` in the stack directory; no edge access log | Credential at rest; no forensic trail | Project owner | Move it to the credential store; decide on an access log (runbook `:79-80`) |
| 15 | **Orphan source credential** from the 2026-10-26 live check (secret discarded unseen) | Unused but valid credential | Project owner | Revoke it (hosted deploy record of 2026-10-26) |
| 16 | **Product defect on `main`: the `test` script in `e2e-ui/package.json` names five test files that are not on `main`** (`tests/release1/unit/` `pending-parity`, `journey-runner`, `nested-appointment-seed`, `nested-hosted-driver`, `nested-hosted-guards` `.test.mjs`); they existed through `cf202e17` and were dropped by the merge of PR #12 (`b6206d9c`) | `node --test` exits 1 on a missing file, so `cd e2e-ui && npm test`, and therefore `cd ui && npm test` (which ends with it), very likely fail on `main`. **Not re-run** for this document | Developer | Restore the five files from `cf202e17` on a fix branch, re-run both `npm test` and record the counts (evidence: code review of 2026-10-26, `git ls-tree` and `git log` on `5b9a6c36`) |

### 13.2 Product questions still open with the owner

These are from the planning workspace open-issues list, product items only. Tooling items specific to the planning
workspace are left out.

- OQ-02 and OQ-08 remain as Release 2 volume inputs (ordering is answered; peak and payload figures are to be
  confirmed at load).
- OQ-09 is answered (best-effort); the recommendation stays on record.
- The wording clash on onboarding duration ("under 2 hours" versus "under 1 working day").
- Promises with no owning feature: RTO 1 hour, source-callback timeout, onboarding duration, pipeline runtime.
- OQ-03 (tracking shape), OQ-05 (sandbox) and OQ-07 (lookup immediacy).
- Scheduler inputs: which job types, and how the AI agents connect and get alerted (ADR-012 answered the first part).
- The development compose credentials rule: rotate before any shared environment.
- The queue-manager product, and File versus SFTP (section 3.5).
- The three owner decisions in the operator dashboards ADR (`ws/_documents/2026-10-25-adr-operator-dashboards.md:153`).

### 13.3 Not verified in this document

1. The live hosted image pins (`IPASS_*_IMAGE` in the server `.env`), and whether the `4e0730a6` app image on Docker
   Hub is the one running.
2. The `e2e-ui` unit test count on the `main` tip: 45 (README at tag `release-1`) and 84 (measured at `1b07586d`) are
   historical; the tip was not re-measured, and its `test` script names five missing files (section 13, item 16).
3. The production Kafka partition count after the T181 merge.
4. Node and npm version requirements.
5. The e2e-full UI leg, `test:mocked`, `test:container` and perf suite results.
6. Multi-target fan-out and the Kafka-plane audit step as complete features.
7. Whether the Windows directory-junction convention for `node_modules` in worktrees applies to this repository.
8. Any test result on `5b9a6c36`. No suite was re-run for this document; every count is from the dated report cited.
9. The deployed value of `kafka-allow-plaintext` in any environment.
10. The L5 audience token check on `ops-kafbat` (needs a login).

## 14. Glossary

| Term | Meaning |
|---|---|
| Tenant | A platform account; every tenant-owned row belongs to exactly one |
| Customer | The party behind a registered source system, identified from the calling credential |
| System | A registered source or target system; target types API, MQ-CSV, Kafka topic, DB, File |
| Interface | A contracted exchange from a source; its code is in the intake path and immutable once published |
| Contract / contract version | A JSON Schema (optionally imported from OpenAPI) and its immutable approved versions |
| Route | Interface-to-target wiring, with an optional condition |
| Mapping / mapping version | The JSONata transformation for one interface-target pair; versions are immutable and pinned on deliveries |
| Flow | An interface with its routes and mappings, published as one bundle |
| Message | One accepted record from a source |
| Delivery | One message's journey to one target; a persisted state machine (`PENDING`, `DUE`, `HELD`, `SENDING`, `RETRY_WAIT`, `DELIVERED`, `DEAD_LETTERED`; the full register is in ADR-010) |
| Attempt | One call to a target; append-only |
| Business key | The value that defines ordering and identity of a record |
| Outbox | A table written in the intake transaction; the relay publishes from it to Kafka |
| Stage 1 / stage 2 | Routing consumers: stage 1 creates deliveries, stage 2 claims and sends them |
| Poison topic | `platform.poison`, for unreadable records; never retried |
| Five controls | Timeout, transient-only retry, circuit breaker, fallback, metrics and trace (R13) |
| Bulkhead | A per-target concurrency limit; when it is full, the delivery is deferred without a call |
| RLS | PostgreSQL row-level security, the second tenant wall |
| Definer functions | The four `SECURITY DEFINER` functions allowed to cross the tenant boundary (T5) |
| BFF | Backend-for-frontend: the console's login is held by the backend; the browser has only a `SESSION` cookie |
| `OPERATOR_AGENT` | The no-tenant machine principal used by AI agents for jobs |
| KEK | The key-encryption key for target credentials (`PLATFORM_CREDENTIAL_KEK_*`), kept outside the database |
| Kafbat | The open-source Kafka web UI used read-only on the ops address |
| Edge | The nginx container in the hosted kit that holds the path allow-list and rate limits |
| E0 to E19 | Backlog epics (plan phases); the mapping to tracker features F1 to F21 is in `ws/_specs/backlog.md` "Epic map" |
| T-NNN | A backlog task id |
| Tnnn / Bnnn / Fnn (no hyphen) | Project tracker items, defects and features; they differ from backlog T-NNN ids |
| BS-nn / SC-nn / S1-A1 | Base-suite scenarios, Release 1 scenarios, security gate cases |
| P1 to P9 | The per-environment security probes (section 4.6) |
| R01 to R21 | Engineering rules in `rule.md`. Not to be confused with R1/F7 or R2/R3, which are security finding ids |
| `ws/` | The planning workspace outside this repository (`documents/README.md`) |

## 15. Source index

| Source | What it proves here |
|---|---|
| `AGENTS.md` | Stack versions, module rules, build flags, the lock location, working rules |
| `pom.xml` | Module list (`:22-44`), Java 25, Boot 4.1.1, Camel 4.22.1, the `container` and `e2e-full` profiles (`:315-345`) |
| `documents/PRD.md` | Purpose, worked example, roles, FR-01 to FR-19, out of scope, owner-held facts; build status frozen at 2026-10-16 |
| `documents/architecture.md` | Modules, data model, message path, tenant controls, deployment view, cross-cutting rules |
| `documents/rule.md` | R01 to R21 |
| `documents/adr/README.md` and ADR-001 to ADR-018 | Decisions, closed registers, status frozen at 2026-10-16 |
| `documents/e2e-full.md`, `build-checks/e2e-full-required.txt` | The project-close gate and its 10 required scenarios |
| `documents/e2e-ui-inventory.md`, `e2e-ui/README.md` | The move of Playwright suites into `e2e-ui/`; script names and unit counts |
| `documents/README.md` | The `ws/` reference convention; the frozen status baseline |
| [deployment.md](deployment.md) | Operational deployment detail (written on a parallel branch; linked, not duplicated) |
| `deploy/hostinger/README.md`, `deploy/hostinger/seed/NESTED-APPOINTMENT.md`, `deploy/hostinger/kafka/create-topics.sh` | Hosted kit contents, ops address rules, edge recreate rule, the showcase flow and data, topics |
| `compose/compose.yaml`, `compose/keycloak/platform-realm.json` | Local stack image pins; realm `platform` and its clients |
| `ui/package.json`, `e2e-ui/package.json`, `ui/src/pages/` | Console stack, test scripts, screens |
| Controllers under `platform-*/src/main/java` (cited in section 6) | API endpoints in the walkthrough |
| `platform-tenancy/.../Role.java`, `RolePolicy.java` | Roles and caller kinds |
| `git ls-files`, `git log`, `git ls-remote --tags origin`, `git grep "@Test"` (run 2026-10-26 on `5b9a6c36`) | File counts, PR flow, the `release-1` tag at `0b4cbf04`, test source counts |
| Project tracker (read 2026-10-26) | Epic and item states, open defects, T207, T220 |
| `ws/_specs/project-plan.md` | Approved scope, Release 1 and Release 2 phases, sizes, risks, out of scope |
| `ws/_specs/backlog.md` | Epic map E0 to E19, definition of done |
| `ws/_specs/release-base-plan-20261013.md` | Base-version definition, roadmap waves, dates (stale) |
| `ws/_documents/showcase-features-requirements-20261022.md` | Final showcase timeline (build complete 23 October, ceiling 26 October) |
| `ws/_documents/2026-10-22-release1-security-statement-and-risk-acceptance.md` | Accepted conditions A/B/C, R1/F7 acceptance, known gaps, `main` push guard |
| `ws/_reports/2026-10-23-security-statement-refresh.md` | The "lane runs plus review" wording; probes P1 to P9 |
| `ws/_reports/2026-10-24-release1-verdict-and-proof.md` | Release 1 verdict, proof table, not-covered list, "do not quote" note |
| `ws/_reports/2026-10-24-g5-main-container.md`, `2026-10-24-g7-ui-gates-0b4cbf04.md` | Container, e2e-full and arch-test results; UI gate results |
| `ws/_reports/2026-10-25-t215-nested-live.md` | Nested appointment live proof |
| `ws/_reports/2026-10-25-hosted-queue-counts.md` | No messages processed on the hosted app as of 25 October |
| `ws/_reports/2026-10-25-t219-ops-security.md`, `...-recheck.md`, `2026-10-26-t219-ops-security-recheck2.md`, `2026-10-26-t219-review-recheck2.md` | Ops-address findings H1, M1, M2, N1, N2, N4, L5 |
| `ws/_documents/2026-10-26-hosted-deployment-runbook.md` | Hosted links, stack, identity, backups, security status table |
| `ws/_documents/2026-10-24-adr-hostinger-hosting.md` | Hosting topology; env-only configuration; K1; BFF same-origin facts |
| `ws/_documents/2026-10-25-adr-operator-dashboards.md` | Proposed operator dashboards and the platform-operator principal |
| `ws/_documents/2026-10-24-demo-runbook.md` | Demo story, lock slots, ports, Windows notes |
| `ws/_documents/2026-10-22-design-throughput-ceiling.md` | Throughput ceiling and the N-loops design |
