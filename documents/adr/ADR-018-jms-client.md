---
type: ADR
title: "ADR-018 - The MQ-CSV adapter uses Boot's Artemis starter plus camel-jms, confined to routing.adapter.mq"
status: "Accepted (team, 2026-10-19) - Dipesh may reverse; extends ADR-010's starter register by one Boot starter"
date: 2026-10-19
author: "mahesh-architect (ledger row 327, T98)"
project: ipass/integration-platform
provenance: new-decision
related:
  - ADR-005-messaging.md
  - ADR-010-camel-routing-core.md
  - ADR-011-targets-contracts-bulk-ordering.md
  - ADR-015-target-type-register-and-adapter-placement.md
---

# ADR-018 - JMS client for the MQ-CSV target

Conventions and grades as in [ADR-013](ADR-013-mapping-versions.md): **[Verified]** read in the
cited file this pass (`feat/e9-e16` at `1463b36`), **[Inferred]** reasoned from cited sources,
**[TBD]** needs a later step. Not a cross-project decision.

## Context

- T98 (E10, MQ-CSV send) is blocked: `MqCsvBrokerSendContainerTest` is red because no JMS client is
  on the classpath. No pom declares a `jms` or `artemis` dependency; `platform-routing/pom.xml`
  holds `camel-spring-boot-starter` (`:20`) and `camel-kafka-starter` (`:25`) only. [Verified]
- The broker under test is the `activemq-artemis` 2.55.0 image (`ArtemisContainers`). Spring Boot
  4.1.1 manages `artemis.version` 2.53.0 and `jakarta.jms` 3.1.0. [Verified - BOM]
- ADR-010 already lists `camel-jms` among the Camel starters the platform may use; Camel lives only
  in `platform-routing` (`arch-test/.../CamelOnlyInRoutingRule.java`) and adapters live under
  `routing/adapter/**` (ADR-015). [Verified]
- Today's MQ test-connection is a socket probe (`routing/adapter/mq/MqAdapterConfiguration.java`,
  5-second timeout), wired by T-126 (`1463b36`). [Verified]

## Options

| | A. Boot `spring-boot-starter-artemis` + `camel-jms-starter`, versions from the BOMs | B. `artemis-jakarta-client` pinned to 2.55.0 + `camel-jms-starter` | C. No client: keep the socket probe, defer the send |
|---|---|---|---|
| Complexity | Lowest: two lines, no version | One pin to keep in step with Boot | None now; T98 cannot pass |
| Risk | Client 2.53 vs broker 2.55: same 2.x wire protocol, compatible both ways [Inferred - Artemis 2.x compatibility policy]; proven by the container test | Pin drifts from Boot's managed `jakarta.jms` and Netty | Release 1 MQ target unshipped (ADR-011) |
| Cost | Nothing new to license (Apache-2.0) | Same licence; upgrade toil | A whole lane idle |
| Reversibility | One `git revert`; or add the pin (B) | One `git revert` | n/a |

## Decision

**Option A.** Add `spring-boot-starter-artemis` and `camel-jms-starter` to
`platform-routing/pom.xml` only, with **no version** (Boot and the Camel BOM at root `pom.xml:100`
manage them). Pin `artemis.version` to the broker line **only if** the conformance test below fails
on the managed client. [Decided]

**Where it may be used.** `org.apache.activemq..` and `jakarta.jms..` are referenced only from
`io.integrationplatform.routing.adapter.mq..`. A new ArchUnit rule in `arch-test` enforces it, proven
by one planted violation that must fail the rule (precedent:
`platform-ingest/src/test/.../relay/RelayVendorClientArchitectureTest.java`). Spring's JMS
auto-configuration is **off** (`ArtemisAutoConfiguration` and `JmsAutoConfiguration` excluded, `spring.artemis.*` unused); the adapter builds one connection factory
per target from the target's own settings, never a platform-wide broker. [Decided]

**The five controls** (RULES `resilient-integration`) on the JMS send:

| Control | How | Conformance test (written failing first) |
|---|---|---|
| Timeout | `callTimeout`, `connectionTTL` and the connect timeout set per target from its settings; default 5 s like the probe | broker accepts TCP but never answers: send fails in <= 6 s, 1 of 1 |
| Retry, transient only | Camel layer-1 redelivery (ADR-010's two layers). Client `reconnectAttempts=0` - no third retry layer. `JMSSecurityException` and an unknown queue are PERMANENT; a connection or I/O `JMSException` is TRANSIENT | 1 auth failure -> 1 attempt, 0 redeliveries; 1 broker drop -> redelivered, attempts = configured count |
| Circuit breaker | The per-target resilience4j breaker ADR-010 already wraps every `TargetCall` | 5 consecutive broker-down sends open the breaker; the 6th is not attempted |
| Fallback | Delivery goes to `RETRY_WAIT` (ADR-010 state register); no message is dropped | broker down -> delivery row `RETRY_WAIT`, 1 of 1, 0 rows lost |
| Observability | Micrometer timer and outcome counter per target type, OTel span around the send, one structured log line per attempt without payload or credential | the span and counter exist after one send (assert in the container test) |

**Destination check.** The Artemis client resolves the broker host itself, so it cannot use the
pinned address of `DestinationGuard.check(URI)`. The host is checked at save time with
`DestinationGuard.checkHost` (`platform-config/.../destination/DestinationGuard.java:113-126`,
platform hosts refused at `:173-177`) and the connection is bounded by the egress network policy
(ADR-010 section 8, `ADR-010-camel-routing-core.md:349-355`). [Verified]

**Licence.** Apache ActiveMQ Artemis and Apache Camel: Apache-2.0. No new licence class. [Verified -
project licences]

## Conformance (the gate for T98)

1. `MqCsvBrokerSendContainerTest` green: 1 message sent, 1 received on the queue, 0 duplicates.
2. The five tests in the table above green.
3. The ArchUnit rule green on the tree and red on its planted violation (1 of 1).
4. Unit suite exit 0 and `-Pcontainer` exit 0 on the lane's head.

Container runs respect the Tuesday 20 Oct 12:00-20:00 no-container window.

## Consequences

- T98 unblocks with two dependency lines; the JMS surface sits in one package.
- The register "Camel starters in use" (README) gains `camel-jms` as **in use** and one non-Camel
  starter, `spring-boot-starter-artemis`, recorded here.
- **What we give up:** control of the client version (Boot's 2.53 against a 2.55 broker) until the
  test says otherwise; Spring's JMS auto-configuration (every target builds its own factory, a few
  more lines in the adapter); a second MQ vendor (IBM MQ) would need its own client and a new ADR.

## Revisit if

- The container test fails on the managed client - take Option B (pin).
- A target needs a non-Artemis broker (IBM MQ, RabbitMQ over AMQP).
- Boot drops `spring-boot-starter-artemis` or Artemis leaves Apache-2.0.
