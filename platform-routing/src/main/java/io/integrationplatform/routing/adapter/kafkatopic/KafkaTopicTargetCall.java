package io.integrationplatform.routing.adapter.kafkatopic;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T-148: sends one due delivery to the target's OWN Kafka topic with the five controls (mirrors MqTargetCall):
 * a breaker per endpoint, a bounded timeout with no reconnect, the delivery id as record key, the destination
 * guard, and metrics, trace and log per attempt. A topic that belongs to the platform is refused PERMANENT.
 */
public final class KafkaTopicTargetCall implements TargetCall {

    /** Same breaker shape as the API and MQ target calls. */
    static final CircuitBreakerConfig BREAKER = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30)).permittedNumberOfCallsInHalfOpenState(2).build();

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTopicTargetCall.class);

    private final KafkaOutbound outbound;
    private final KafkaSender sender;
    private final DestinationGuard guard;
    private final Set<String> platformTopics;
    private final CircuitBreakerRegistry breakers;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final long timeoutMillis;

    public KafkaTopicTargetCall(KafkaOutbound outbound, KafkaSender sender, DestinationGuard guard,
            Set<String> platformTopics, CircuitBreakerRegistry breakers, MeterRegistry meters,
            ObservationRegistry observations, Duration timeout) {
        this.outbound = outbound;
        this.sender = sender;
        this.guard = guard;
        this.platformTopics = Set.copyOf(platformTopics);
        this.breakers = breakers;
        this.meters = meters;
        this.observations = observations;
        this.timeoutMillis = timeout.toMillis();
    }

    /** T-148 @security: a target may never be pointed at one of the platform's own work/poison topics. */
    public static void requireNotPlatformTopic(String topic, Set<String> platformTopics) {
        if (platformTopics.contains(topic.strip().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalStateException("the target topic '" + topic + "' is a platform topic");
        }
    }

    @Override
    public void call(Stage2Send send) {
        CircuitBreaker breaker = breakers.circuitBreaker("kafka:" + send.targetEndpointId(), BREAKER);
        if (!breaker.tryAcquirePermission()) {
            throw TargetFaultException.CircuitOpenException.of(breaker, send.targetEndpointId());
        }
        long started = System.nanoTime();
        Observation observation = Observation.createNotStarted("kafka.target.send", observations).start();
        OutcomeClass outcome = OutcomeClass.TRANSIENT;
        KafkaMessage message = null;
        try {
            java.util.Optional<KafkaMessage> resolved;
            try {
                resolved = outbound.resolve(send);
            } catch (RuntimeException badSettings) {
                // review T-148: a thrown resolve must still release the breaker permit and be metered
                // classified - a malformed settings row is a permanent target fault, never a broker fault.
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the Kafka settings of target endpoint "
                        + send.targetEndpointId() + " are unusable: " + badSettings.getMessage());
            }
            if (resolved.isEmpty()) {
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "delivery " + send.deliveryId() + " has no Kafka message");
            }
            message = resolved.get();
            try {
                // shortcut: checkHost has no DNS pinning; the broker host is re-resolved at connect time
                guard.checkHost(message.settings().host());
                requireNotPlatformTopic(message.settings().topic(), platformTopics);
            } catch (DestinationRefusedException | IllegalStateException refused) {
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the Kafka destination of target endpoint "
                        + send.targetEndpointId() + " is refused: " + refused.getMessage());
            }
            try {
                sender.send(message.settings(), message.saslPassword(), send.deliveryId(), message.body(),
                        Duration.ofMillis(timeoutMillis));
                outcome = OutcomeClass.SUCCESS;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            } catch (AuthorizationException | InvalidTopicException refused) {
                // the broker answered: a refusal is not a broker fault, so it does not count against the breaker
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the broker of target endpoint " + send.targetEndpointId()
                        + " refused the send (" + refused.getClass().getSimpleName() + ")");
            } catch (AuthenticationException failed) {
                // E16: a wrong password or an untrusted certificate - a retry cannot fix it and could lock the account
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the broker of target endpoint " + send.targetEndpointId()
                        + " refused the login (" + failed.getClass().getSimpleName() + ")");
            } catch (DestinationRefusedException refused) {
                // E16 F7: a broker the cluster advertised is behind the guard - the address stays out of the text
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "a broker advertised by the cluster of target endpoint "
                        + send.targetEndpointId() + " is refused by the destination guard");
            } catch (KafkaSecurityException misconfigured) {
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the Kafka security of target endpoint "
                        + send.targetEndpointId() + " is unusable: " + misconfigured.getMessage());
            } catch (java.util.concurrent.TimeoutException unacknowledged) {
                // the record was handed to the broker and no answer came: it may be on the topic (ADR-010 s5)
                outcome = OutcomeClass.AMBIGUOUS;
                breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, unacknowledged);
                throw new TargetFaultException(outcome, "the broker of target endpoint " + send.targetEndpointId()
                        + " did not acknowledge the record in time: it may have been written");
            } catch (Exception failed) {
                breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, failed);
                throw new TargetFaultException.TransientFaultException("the send to the broker of target endpoint "
                        + send.targetEndpointId() + " failed (" + failed.getClass().getSimpleName() + ")");
            }
        } finally {
            long elapsed = System.nanoTime() - started;
            String tag = outcome.name();
            Timer.builder("kafka.target.send").tag("target_type", "KAFKA").tag("outcome", tag).register(meters)
                    .record(elapsed, TimeUnit.NANOSECONDS);
            Counter.builder("kafka.target.outcome").tag("target_type", "KAFKA").tag("outcome", tag).register(meters)
                    .increment();
            observation.lowCardinalityKeyValue("outcome", tag).stop();
            LOG.info("kafka send delivery={} endpoint={} outcome={} ms={}", send.deliveryId(), send.targetEndpointId(),
                    tag, TimeUnit.NANOSECONDS.toMillis(elapsed));
            if (message != null) {
                message.wipe(); // the body and the SASL password are this call's copies; the port keeps the source
            }
        }
    }
}
