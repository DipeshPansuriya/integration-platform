---
type: Index
title: "Diagrams - configuration-driven integration platform"
date: 2026-10-17
project: ipass/integration-platform
related:
  - ../architecture.md
  - ../PRD.md
  - ../adr/README.md
---

# Diagrams

Six diagrams, each an archify spec (`*.json`, the source of truth) and its rendered, self-contained page
(`*.archify.html` - open it in a browser; zoom, search, guided views and the light/dark switch are built in).
Edit the spec, then re-render the page in the same change (rule R21 in [rule.md](../rule.md)).

**Baseline: code main `90c1ddd` (2026-10-16).** These diagrams show the code as built at that commit, not the target design. The target design is
in [architecture.md](../architecture.md) and the ADRs. Every path below is relative to the repository root.

## How to read the status words

| Word on a box, arrow or band | Meaning |
|---|---|
| (no word) | In the code and started by `platform-app` today |
| **not wired** | Classes and tests exist in the module, but `platform-app` does not create or call them yet. Connecting them is the E11 composition step; no `app/composition` package exists under `platform-app/src/main` yet |
| **planned** | No running code and no caller yet - at most a port interface, an unused method or a table column |

**The one sentence a new engineer needs:** the running application today accepts a message, stores it with its
outbox row, and stops there (`platform-ingest/src/main/java/io/integrationplatform/ingest/internal/intake/IntakeService.java:38-66`).
The relay, the stage consumers, claim, send and the retry job body are built and tested as separate pieces but are
not connected yet. No main class constructs `PostgresOutboxRelay`, `StageConsumer`, `DeliveryClaimService`,
`LayerOneRouteBuilder`, `DeliveryRetryTrigger` or `OutcomeRecorder`; the `DELIVERY_RETRY` job has no handler, so each
run is skipped (`platform-app/src/main/java/io/integrationplatform/app/scheduler/JobRunWrapper.java:32-34`).

Every label, sublabel, band, lane, card and source-link label in the six specs was checked against this table on
2026-10-17: a label for a piece that `platform-app` does not start carries its word, on the label itself or on the
band or lane around it. Examples: the three Delivery-state lanes are "(not wired)" and replay is "(planned)"; the
architecture arrow from `platform-delivery` to the state register in `platform-domain` is "(not wired)" because only
the unwired delivery code drives it (`platform-domain/src/main/java/io/integrationplatform/domain/delivery/Delivery.java:34`),
and the `platform-domain` box reads "Delivery states, outcomes (not wired)" - its state register and outcome classes are
reached only from unwired code, it holds no id type yet, and only its job-handler and audit-event types are used by
running code (`platform-domain/src/main/java/io/integrationplatform/domain/scheduler/JobHandler.java:19`);
the Prometheus registry is on the classpath (`platform-app/pom.xml:86`) but "not exposed" - actuator exposes health
only (`platform-app/src/main/resources/application.yml:58`). The console UI shell needs no word: every piece runs.

## The diagrams

| Diagram | What it shows | Spec |
|---|---|---|
| [System architecture](architecture.archify.html) | Modules inside the one deployable, PostgreSQL, Kafka, Keycloak, the console, source and target systems, key store and the build-time ArchUnit checks. Every box carries up to three source links | [architecture.architecture.json](architecture.architecture.json) |
| [End to end, part 1 - intake and routing](end-to-end-flow.archify.html) | Token, tenant and customer binding, the intake transaction, the relay, stage 1 and the synchronous variant | [end-to-end-flow.sequence.json](end-to-end-flow.sequence.json) |
| [End to end, part 2 - deliver and recover](end-to-end-delivery.archify.html) | Claim, send, outcome, retry and recovery for one delivery | [end-to-end-delivery.sequence.json](end-to-end-delivery.sequence.json) |
| [Configuration workflow](workflow.archify.html) | What a tenant user does: log in, register systems, contract versions, interface and rules, mapping, submit, approve, publish, queue dashboard | [workflow.workflow.json](workflow.workflow.json) |
| [Delivery states](delivery-state.archify.html) | The Delivery state machine: 7 states, 12 legal moves | [delivery-state.lifecycle.json](delivery-state.lifecycle.json) |
| [Console UI shell](ui-modernized-shell.archify.html) | The React console: shell, its screens, and how it reaches the platform and Keycloak (new in this set) | [ui-modernized-shell.architecture.json](ui-modernized-shell.architecture.json) |

## What changed against the previous set (2026-10-02 to 2026-10-13)

The previous set drew the target design with no status words. This set was redrawn against the code.

### System architecture

- Relay, stage consumers, claim, send and the delivery rows they write are **not wired**: the classes exist
  (`platform-ingest/src/main/java/io/integrationplatform/ingest/internal/relay/PostgresOutboxRelay.java:45`,
  `platform-routing/src/main/java/io/integrationplatform/routing/stage/StageConsumer.java:45`) but nothing constructs
  them. The routing beans that do exist have no caller: the Kafka publish port, created only when
  `IPASS_KAFKA_BOOTSTRAP_SERVERS` is set (`platform-routing/src/main/java/io/integrationplatform/routing/publish/KafkaPublishPortConfiguration.java:29`, `:61-63`),
  the API adapter (`platform-routing/src/main/java/io/integrationplatform/routing/adapter/api/ApiAdapterConfiguration.java:43-45`)
  and the send-time credential beans (`platform-routing/src/main/java/io/integrationplatform/routing/credential/CredentialRoutingConfiguration.java:23-33`).
- Routing to transform is **planned**: `platform-routing/pom.xml` has no `platform-transform` dependency and the API
  body port is a stub (`ApiAdapterConfiguration.java:38-39`).
- Prometheus, Grafana and Tempo are **planned**: the compose file starts only PostgreSQL, Keycloak, Kafka and Kafbat
  (`compose/compose.yaml:13`, `:41`, `:72`, `:104`); actuator exposes health only
  (`platform-app/src/main/resources/application.yml:57-58`); alert rules exist
  (`platform-routing/src/main/resources/config/messaging-alert-rules.rules:10-39`).
- Circuit breakers already exist in the relay, the stage consumer, the test-connection check and the
  identity-provider client; only the **per-target send breaker** is planned. The per-target bulkhead is **not wired**
  (card "Status at code main").
- Target systems: only the API adapter sends (`platform-routing/src/main/java/io/integrationplatform/routing/adapter/api/ApiTargetCall.java:48`);
  MQ, DB and File can be registered but have no adapter
  (`platform-config/src/main/resources/db/migration/V2_2_7__config_system_type_settings.sql:13`); Kafka is not a
  registrable target type yet - it is **planned** ([ADR-015](../adr/ADR-015-target-type-register-and-adapter-placement.md)
  decision 3 adds `KAFKA_TOPIC` in E10), so the targets box reads "API; MQ, DB, File, Kafka planned"
  ([architecture.md](../architecture.md) section 9, Kafka note).
- `platform-app` runs the db-scheduler shell (4 recurring jobs,
  `platform-app/src/main/java/io/integrationplatform/app/scheduler/SchedulerJobTasks.java:37-53`) whose job bodies are
  no-ops or skipped today, and the queue summary API
  (`platform-app/src/main/java/io/integrationplatform/app/queue/QueueSummaryController.java:51`).
- `arch-test` is ArchUnit only - 6 rule classes in `arch-test/src/test/java/io/integrationplatform/archtest/`; no
  Spring Modulith dependency in any `pom.xml`.
- `platform-ledger`, `platform-audit` and `platform-e2e` are not modules (`pom.xml:23-38`). Tenant-crossing definer
  functions are 3 today (`V1_3_3__ipaas_login_bootstrap.sql:7`, `V2_3_5__ipaas_client_bootstrap.sql:8`,
  `V2_3_811__ipaas_work_tenant.sql:13`); a 4th (`ipaas_active_tenants`) is referenced in Java but no migration creates it.

### End to end, part 1 - intake and routing

- Bands say what runs: section 1 **runs today**, section 2 **built, not wired**, section 3 (SYNC) **planned** - no
  SYNC code in any module.
- One endpoint only, `POST /api/v1/interfaces/{interfaceCode}/messages`; `:bulk` is planned
  (`platform-ingest/src/main/java/io/integrationplatform/ingest/adapter/in/IntakeController.java:53`).
- Intake checks today: published interface, the `X-Source-Contract-Version` pin and the business key
  (`IntakeService.java:41-56`). Schema and rule validation exist (`PayloadValidationService`) but intake does not call them yet.
- `Idempotency-Key` is required (`IntakeController.java:68-69`) but not stored or checked yet.
- Answers: 202 with a `trackingId` (`IntakeController.java:85-87`); refusals 400 (missing header, malformed JSON),
  404, 413 (1 MB), 415, 422, 503 (`IntakeController.java:30-32`, `:59-81`, `:103-106`).
- Stage-1 uniqueness is `(message_id, route_id, created_at)`
  (`platform-delivery/src/main/java/io/integrationplatform/delivery/internal/stage1/JdbcStage1Deliveries.java:11-13`).

### End to end, part 2 - deliver and recover

- Claim and send bands say **built, not wired**; the recovery band says **partly planned**.
- Per-target bulkhead **not wired**: the bulkhead (1-32 concurrent sends, non-blocking permit) and the `delivery.due` dispatch that takes the permit before the claim exist
  (`platform-routing/src/main/java/io/integrationplatform/routing/stage2/bulkhead/TargetBulkhead.java:23-36`, `platform-routing/src/main/java/io/integrationplatform/routing/stage2/bulkhead/DeliveryDueDispatch.java:41`),
  as does the bulkhead-full deferral (`platform-delivery/src/main/java/io/integrationplatform/delivery/internal/deferral/JdbcBulkheadDeferrals.java:31`, `:68-75`;
  1 s doubling to 30 s with full jitter, `BulkheadBackoff.java:5-9`), but nothing registers or constructs them. The participant is "Send route (not wired)"
  (`platform-routing/src/main/java/io/integrationplatform/routing/stage2/LayerOneRouteBuilder.java:28-31`). The per-target send breaker is **planned**.
- Outcome classes: 400/401/403/422 permanent, 429/503 transient, 408 and other 5xx ambiguous unless the target declares
  idempotent receive (`platform-domain/src/main/java/io/integrationplatform/domain/delivery/OutcomeClassifier.java:29-31`, `:101-117`).
- Retry job: the re-drive code exists
  (`platform-delivery/src/main/java/io/integrationplatform/delivery/internal/trigger/DeliveryRetryTrigger.java:84-90`)
  but the `DELIVERY_RETRY` job has no handler (`JobRunWrapper.java:32-34`).
- Reconciler and stale-claim sweeper **planned**: no reconciler class; the stale-claim limit is computed
  (`platform-delivery/src/main/java/io/integrationplatform/delivery/internal/retry/RetryPolicy.java:46-49`) but nothing calls it.
- The final state releases the next held delivery in the same transaction
  (`platform-delivery/src/main/java/io/integrationplatform/delivery/internal/JdbcDeliveryRepository.java:41-44`);
  retry defaults 8 attempts, 30 s base, 1 h cap (`RetryPolicy.java:40-43`).

### Configuration workflow

- "Monitor" is now "Queue dashboard": counts per flow and day
  (`platform-app/src/main/java/io/integrationplatform/app/queue/QueueSummaryService.java:31-44`; menu entry `ui/src/App.tsx:70`).
- Publish is refused (409) on a pin mismatch or when the pinned mapping content has no passing sample run
  (`platform-config/src/main/java/io/integrationplatform/config/mapping/application/MappingPublishPinCheck.java:28-30`, `:76-80`).
- Reject and redraft are **planned**: the flow API has submit, approve and publish only
  (`platform-config/src/main/java/io/integrationplatform/config/flow/adapter/in/FlowAdminController.java:143-172`).
- Replay is **planned**: the SQL exists (`JdbcDeliveryRepository.java:202`) but nothing calls it.
- The author may approve their own version (single-user workflow, `V2_4_1420`); a version not pending is 409 `NOT_PENDING_APPROVAL`
  (`platform-config/src/main/java/io/integrationplatform/config/flow/application/FlowAdminService.java:182`);
  publish is one transaction (`FlowAdminService.java:195-233`).

### Delivery states

- States and arrows match the register
  (`platform-domain/src/main/java/io/integrationplatform/domain/delivery/DeliveryTransitions.java:39-47`).
- Both "bulkhead full" moves are **not wired** (`JdbcBulkheadDeferrals.java:68-75` makes the conditional `PENDING`/`DUE` to `RETRY_WAIT` move; nothing calls it yet); the stale-claim sweeper is planned
  (`RetryPolicy.java:46-49` has no caller); replay has no API yet (`JdbcDeliveryRepository.java:202`).
- A successful claim resets the bulkhead deferral count to 0 (`JdbcDeliveryRepository.java:102`, commit `35de8df`).

### Console UI shell (new)

- Overview group: Dashboard `#/` (tiles) and Queue `#/queue` (counts per flow and day) (`ui/src/App.tsx:65-72`;
  `ui/src/pages/Dashboard.tsx:28-68`; `ui/src/pages/QueueDashboard.tsx:14`).
- In-app sign-in card `#/login` (`ui/src/App.tsx:93`; `ui/src/pages/Login.tsx:14`) that redirects the browser to the
  identity provider's sign-in page (`ui/src/pages/Login.tsx:42`, `:46`).
- The shell checks the session with `/api/v1/me` and signs out with `/logout` (`ui/src/App.tsx:215`, `:272`).
- Platform to Keycloak is "OIDC login (BFF)": the browser holds only a session
  (`platform-tenancy/src/main/java/io/integrationplatform/tenancy/internal/web/ConsoleSecurityConfiguration.java:84-88`).

## Checks run

Each page was produced by archify `deliver` (9 of 9 checks, 0 errors, 0 warnings, showcase profile) and checked in a
real browser with `archify visual-check` at 1440x900, 1600x1000, 1920x1080 and 2048x1320: no page scrolls at any of
those sizes. Run visual checks on a copy **outside** this repository - they write screenshots beside the page, and
images are never committed here.

Re-render (architecture pages need `--repo-root` so their source links are checked):

```bash
node <archify>/bin/archify.mjs deliver <type> documents/diagrams/<spec>.json documents/diagrams/<name>.archify.html --quality showcase --json [--repo-root .]
```
