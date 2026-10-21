package io.integrationplatform.routing.adapter.db;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetCall;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T-149: writes one due delivery with the five controls (mirrors KafkaTopicTargetCall): a breaker per endpoint,
 * bounded statement/connect timeouts with no reconnect, the delivery id for the target's idempotent insert
 * (ON CONFLICT (delivery_id) DO NOTHING), the destination guard and approved-table contract, and metrics, trace
 * and log per attempt. An unapproved table is refused PERMANENT (destination refused, nothing written); a channel
 * that dropped after the statement may have been sent is AMBIGUOUS - the layer-2 retry re-inserts the SAME
 * delivery id and the target holds exactly one row for it (E16-C4).
 */
public final class DbTargetCall implements TargetCall {

    /** Same breaker shape as the API, MQ and Kafka target calls. */
    static final CircuitBreakerConfig BREAKER = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30)).permittedNumberOfCallsInHalfOpenState(2).build();

    /** T-149 @security: a DB send never touches a table outside the target's approved list. */
    public static void requireApprovedTable(String table, java.util.List<String> approvedTables) {
        if (approvedTables.stream().noneMatch(approved -> approved.equalsIgnoreCase(table))) {
            throw new IllegalStateException("the table '" + table + "' is not on the target's approved list");
        }
    }

    /** F1: the database ports a target may use; the operator lists them (default 5432). Anything else is refused. */
    public static void requireAllowedPort(int port, java.util.Set<Integer> allowedPorts) {
        if (!allowedPorts.contains(port)) {
            throw new IllegalStateException("port " + port + " is not an allowed database port");
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(DbTargetCall.class);

    private final DbOutbound outbound;
    private final DbSender sender;
    private final DestinationGuard guard;
    private final java.util.Set<Integer> allowedPorts;
    private final CircuitBreakerRegistry breakers;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public DbTargetCall(DbOutbound outbound, DbSender sender, DestinationGuard guard,
            java.util.Collection<Integer> allowedPorts,
            CircuitBreakerRegistry breakers, MeterRegistry meters, ObservationRegistry observations) {
        this.outbound = outbound;
        this.sender = sender;
        this.guard = guard;
        this.allowedPorts = java.util.Set.copyOf(allowedPorts);
        this.breakers = breakers;
        this.meters = meters;
        this.observations = observations;
    }

    @Override
    public void call(Stage2Send send) {
        CircuitBreaker breaker = breakers.circuitBreaker("db:" + send.targetEndpointId(), BREAKER);
        if (!breaker.tryAcquirePermission()) {
            throw TargetFaultException.CircuitOpenException.of(breaker, send.targetEndpointId());
        }
        long started = System.nanoTime();
        Observation observation = Observation.createNotStarted("db.target.send", observations).start();
        OutcomeClass outcome = OutcomeClass.TRANSIENT;
        DbMessage message = null;
        try {
            java.util.Optional<DbMessage> resolved;
            try {
                resolved = outbound.resolve(send);
            } catch (RuntimeException badSettings) {
                // a thrown resolve must release the breaker permit and be metered; a malformed settings row is a
                // permanent target fault, never a target fault of the broker kind
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the DB settings of target endpoint "
                        + send.targetEndpointId() + " are unusable: " + badSettings.getMessage());
            }
            if (resolved.isEmpty()) {
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "delivery " + send.deliveryId() + " has no DB message");
            }
            message = resolved.get();
            CheckedDestination checked;
            try {
                // F1 (CWE-918): the port first (no DNS spent on a refused port), then the approved table, then the
                // FULL guard: allow-list, platform hosts, refused ranges on EVERY resolved address; the host is
                // resolved ONCE here and the checked address is what the sender dials
                requireAllowedPort(message.settings().port(), allowedPorts);
                requireApprovedTable(message.table(), message.settings().approvedTables());
                checked = guard.check(java.net.URI.create("http://" + message.settings().host() + ":"
                        + message.settings().port()));
            } catch (DestinationRefusedException | IllegalStateException | IllegalArgumentException refused) {
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the DB destination of target endpoint "
                        + send.targetEndpointId() + " is refused, destination refused: " + refused.getMessage());
            } catch (java.net.UnknownHostException unresolved) {
                breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, unresolved);
                throw new TargetFaultException.TransientFaultException("the database host of target endpoint "
                        + send.targetEndpointId() + " does not resolve");
            }
            try {
                sender.insert(message, send.deliveryId(), checked.pinnedAddress());
                outcome = OutcomeClass.SUCCESS;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            } catch (SQLException refused) {
                String state = refused.getSQLState();
                if (state != null && (state.startsWith("23") || state.startsWith("42") || state.startsWith("28"))) {
                    // the target answered (constraint, privilege, auth): a refusal is not a target fault
                    outcome = OutcomeClass.PERMANENT;
                    breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                    throw new TargetFaultException(outcome, "the target database of target endpoint "
                            + send.targetEndpointId() + " refused the send (" + state + ")");
                }
                if ("08006".equals(state)) {
                    breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, refused);
                    throw new TargetFaultException(OutcomeClass.AMBIGUOUS,
                            "the channel to the target database of target endpoint " + send.targetEndpointId()
                                    + " dropped after the statement may have been sent: the retry re-inserts the "
                                    + "delivery id idempotently");
                }
                breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, refused);
                throw new TargetFaultException.TransientFaultException("the send to the target database of target "
                        + "endpoint " + send.targetEndpointId() + " failed (" + state + ")");
            }
        } finally {
            long elapsed = System.nanoTime() - started;
            String tag = outcome.name();
            Timer.builder("db.target.send").tag("target_type", "DB").tag("outcome", tag).register(meters)
                    .record(elapsed, TimeUnit.NANOSECONDS);
            Counter.builder("db.target.outcome").tag("target_type", "DB").tag("outcome", tag).register(meters)
                    .increment();
            observation.lowCardinalityKeyValue("outcome", tag).stop();
            LOG.info("db send delivery={} endpoint={} outcome={} ms={}", send.deliveryId(), send.targetEndpointId(),
                    tag, TimeUnit.NANOSECONDS.toMillis(elapsed));
            if (message != null && message.password() != null) {
                Arrays.fill(message.password(), '\0');
            }
        }
    }
}