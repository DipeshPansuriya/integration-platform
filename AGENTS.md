# AGENTS.md - guidance for AI agents working in this repository

This repository is a **configuration-driven integration platform**: tenants register systems,
contracts, routes, mappings and rules as configuration, and the platform accepts, routes,
transforms and delivers their messages. It builds **one deployable, a modular monolith** with
enforced module boundaries ([ADR-001](documents/adr/ADR-001-modular-monolith.md)).

This file is the repository's one agent guidance file. It is a pointer, not a source of truth.

## Where the truth lives

| Read | For |
|---|---|
| [documents/PRD.md](documents/PRD.md) | What the platform must do: requirements and acceptance criteria |
| [documents/architecture.md](documents/architecture.md) | Modules, data model, message path, tenant isolation, deployment |
| [documents/rule.md](documents/rule.md) | The engineering rules (R01-R21) - every one is a MUST |
| [documents/adr/](documents/adr/) | Architecture decisions; start at the [index](documents/adr/README.md) |
| [documents/diagrams/](documents/diagrams/) | Five detail-level diagrams (archify specs and pages); see the [index](documents/diagrams/README.md) |

When this file and a document disagree, the document wins.

## Stack

- **Java 25**, **Spring Boot** 4.1.1, Maven (use the wrapper `./mvnw`).
- **Apache Camel** 4.22.1 - only in `platform-routing` (R12, ADR-010).
- **Apache Kafka** client 4.3.1 - exactly one kafka-clients version, no spring-kafka (R12, ADR-005).
- **PostgreSQL 17**, schema owned by Flyway (ADR-002).
- A **React** console in `ui/`, served as static files and calling only the admin API - not built yet.

## Modules

Built today: `platform-app` (the deployable: wiring only, no business logic), `platform-routing`
(the only Camel module), `platform-ingest` (the common intake API, no Camel), `platform-tenancy`
(tenant context, bootstrap resolvers, row-level security) and the test-only `arch-test` (ArchUnit
rules over every module's package). The full module
list, with what each module owns and must not do, is
[architecture.md section 2](documents/architecture.md).

Root package `io.integrationplatform`. Each Maven module holds one application module with base
package `io.integrationplatform.<module>` (for example `io.integrationplatform.routing`); its
public surface is that package plus `port` and `event` sub-packages, and everything else lives
under `io.integrationplatform.<module>.internal` (ADR-001, Decision, item 5).

## Build and test

Set the time zone for every Maven run (R04):

```bash
export JAVA_TOOL_OPTIONS=-Duser.timezone=Etc/UTC
./mvnw test              # unit tests only - runs no container test (R03)
./mvnw -Pcontainer test  # only the tests tagged @Tag("container"); needs Docker
```

- Container tests carry `@Tag("container")`; the parent pom excludes that group by default.
- Run **one Docker suite at a time** on the build machine, with the capped JVM flags in R06.
- Dependency rules: `build-checks/check-dependency-tree.sh` and
  `build-checks/check-planted-violations.sh`; this file is checked by
  `build-checks/check-agent-guidance.sh`; every section of the recreated documents names its
  source, checked by `build-checks/check-provenance.sh`.

## Working rules (full text in [rule.md](documents/rule.md))

- **Test first** - a failing test before the code that satisfies it (R01).
- **Pinned image tags** - every container image tag is pinned, in tests and compose (R05).
- **No secret values** - only environment variable names in any file; values stay in the OS secret store (R07).
- **No remote, push, pull request or merge** without the project owner's typed word (R17).
- **Diagrams stay in sync** - a change that alters what a diagram shows updates its spec and re-renders its page in the same change (R21).
- **No `.claude/` folder and no second guidance file** - this file is the one (R18).
- **No employer, client or product names**; English only (R19).
