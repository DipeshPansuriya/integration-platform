---
type: Rule
title: "Engineering rules - configuration-driven integration platform"
status: "Recreated 2026-10-02 (phase 0) from surviving sources and the measured lessons of the deleted build"
date: 2026-10-02
author: "mahesh-architect (ledger row 278, phase 0)"
project: ipass/integration-platform
provenance: recreation
related:
  - PRD.md
  - architecture.md
  - adr/README.md
---

# Engineering rules - configuration-driven integration platform

> **Recreation.** The deleted engineering-standards document survives only in two quotes; most
> rules here come from the approved plan and from **lessons measured on the deleted build**
> (2026-09-30 to 2026-10-01), so they are not paid for twice (`ws/_specs/project-plan.md:160`,
> `:211-216`). Each rule names its source. A rule marked **New** comes from the ADRs of
> 2026-10-02.

Source paths starting `ws/` are in the hub repository, project workspace
`_personal/_project_ipass/integration-platform/`. Memory lines drift; the id (`m-xxxxxx`) is the
stable key.

## Every rule is a MUST; each names how it is checked

| # | Rule | Checked by | Source |
|---|---|---|---|
| R01 | **Test first.** A failing test is written before the code that satisfies it; a task is done only when that test passes. Design and ADR tasks are doc-first instead | Review: the test's first commit precedes the code | `ws/_specs/project-plan.md:44`; `ws/_specs/work-breakdown.md:22-25` |
| R02 | **Gates in counts, not ratios.** Every gate states run / failed counts; every claimed control has a test that fails on a planted violation | Review | Architect playbook; ADR-009 gate table |
| R03 | **A bare `mvn test` never runs container tests.** Container tests carry the `container` tag; the parent pom excludes that group by default and runs it in its own execution | A Docker-less `mvn test` passes | Lesson: m-4fa7a2 (`ws/_memory/shared.md:55`), m-d87df7 (`:45`) |
| R04 | **Every Maven run sets `JAVA_TOOL_OPTIONS=-Duser.timezone=Etc/UTC`.** The PostgreSQL test container rejected the build PC's default zone (`Asia/Calcutta`, FATAL invalid TimeZone) | Container suites start | Lesson: m-c28d3c (`ws/_memory/shared.md:121`), m-af0e71 (`:57`) |
| R05 | **Every container image tag is pinned** (test and compose). An unpinned Debian `postgres:17` changed behaviour under the tests; the same legacy-zone issue is a production deployment finding for the release track | Review of compose and test code | Lesson: m-309bce (`ws/_memory/shared.md:48`), m-af0e71 (`:57`) |
| R06 | **One Docker/Testcontainers suite at a time on the build PC**, with capped JVM flags: `MAVEN_OPTS="-Xmx384m -Xms64m -XX:MaxMetaspaceSize=256m -Xshare:off"` and `-DargLine="-Xmx256m -XX:MaxMetaspaceSize=192m"`; add `-Djdk.attach.allowAttachSelf=true` where Mockito needs it | Run discipline | `ws/_specs/project-plan.md:98`; `ws/_specs/f3-plan-brief.md:546` |
| R07 | **No literal password or secret in any file.** Every password and key is read from an environment variable *name*; values live in the OS secret store on the build PC. gitleaks runs before commit | gitleaks; review | `ws/_specs/project-plan.md:102`, `:209` (the deleted build had a literal dev password in its compose file) |
| R08 | **Tenant filter on every query, raw JDBC included, in every module's package.** The deleted build's ArchUnit rule missed a raw-JDBC class in a package outside its scope | ArchUnit rule with a planted-violation test | Lesson: m-d87df7 (`ws/_memory/shared.md:45`); `ws/_specs/project-plan.md:103` |
| R09 | **Explicit casts on uuid parameters in hand-written SQL** (`?::uuid`); the driver refuses string-to-uuid binding. The deleted build needed this for `approver_id` | Container test of each statement | Lesson: m-ecd793 (`ws/_memory/shared.md:56`), m-af0e71 (`:57`); `ws/_specs/f2-tenant-directory-rls-migration-draft.md:104-106` |
| R10 | **Configuration additions never need a migration.** Flyway owns the schema; JPA validates only; closed sets are `text` + `CHECK`, never `ENUM` | Rows-only onboarding test | ADR-002 |
| R11 | **The tenant setting is stamped per transaction only; every tenant-scoped access runs in a transaction; SQL is always parameterised** | ADR-007 Decision 6 tests | **New** - ADR-007 |
| R12 | **Camel only in `platform-routing`; spring-kafka never; exactly 1 kafka-clients version (4.3.1) and exactly 1 networknt version on the classpath** | ArchUnit; dependency-convergence check that fails the build | `ws/_specs/project-plan.md:78-81`, `:102`; ADR-005, ADR-010 |
| R13 | **Five controls on every call that leaves the process**: timeout, transient-only retry with backoff, jitter and cap, one circuit breaker per dependency, a fallback, metrics and a trace span. Never retry a timeout of work already sent (unless the target declares idempotent receive), a non-idempotent call, 401/403 or a validation error | A fault test per control (WireMock, broker down) | Hub rule `resilient-integration`; ADR-010 sections 4-5, 8 |
| R14 | **Logs are structured JSON with tenant and message ids; fields declared masked never appear in logs or on screen; secrets are write-only after creation** | Masking test | `ws/_specs/project-plan.md:110`, `:117` |
| R15 | **Audit, delivery attempts and published versions are append-only** - never updated or deleted | A test tries both and fails | `ws/_specs/project-plan.md:104`, `:117` |
| R16 | **Pipeline runtime target: under 15 minutes to the DEV gate** (a proposal, verified at deployment). Beyond that, people stop running it locally and start batching commits | Pipeline timing | Deleted engineering-standards.md:547, quoted at `ws/_specs/f3-plan-brief.md:348` |
| R17 | **No push, no remote, no pull request and no merge without Dipesh's typed word.** This repository has no remote; none is added without him | Git hooks and review | `ws/_specs/project-plan.md:98`, `:188` |
| R18 | **No AI-agent folder (`.claude/`) in this repository.** Any agent guidance file that the build adds points at `documents/` and describes the Java/React stack from its first commit | Review | `ws/_memory/conventions.md:9-10`; `ws/_specs/project-plan.md:191` |
| R19 | **No employer, client or product names in any file**; English only | Review; a grep in the release check | Hub rules `no-project-name-references`, `english-only-replies` |
| R20 | **UI work uses the design skill named on the project's Skills line in the hub**; the look-and-feel redesign comes after all functionality | Review | `ws/_memory/overview.md:12`; `ws/_specs/project-plan.md:129` |

## Changing a rule

A rule changes by a commit to this file that names the reason and the source; a rule that
comes from an ADR changes only with that ADR.
