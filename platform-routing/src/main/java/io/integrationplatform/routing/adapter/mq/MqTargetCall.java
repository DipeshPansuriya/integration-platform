package io.integrationplatform.routing.adapter.mq;

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
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.InvalidDestinationException;
import jakarta.jms.JMSException;
import jakarta.jms.JMSSecurityException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends one due delivery to its MQ queue with the five controls (ADR-018, T98): a breaker per endpoint, bounded
 * timeouts with no reconnect, duplicate-id idempotency, the destination guard, and metrics, trace and log per attempt.
 */
public final class MqTargetCall implements TargetCall {

    static final String DUPLICATE_ID = "_AMQ_DUPL_ID";

    /** Same breaker shape as the API target call. */
    static final CircuitBreakerConfig BREAKER = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30)).permittedNumberOfCallsInHalfOpenState(2).build();

    private static final Logger LOG = LoggerFactory.getLogger(MqTargetCall.class);

    private final MqOutbound outbound;
    private final DestinationGuard guard;
    private final CircuitBreakerRegistry breakers;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final long timeoutMillis;

    public MqTargetCall(MqOutbound outbound, DestinationGuard guard, CircuitBreakerRegistry breakers,
            MeterRegistry meters, ObservationRegistry observations, Duration timeout) {
        this.outbound = outbound;
        this.guard = guard;
        this.breakers = breakers;
        this.meters = meters;
        this.observations = observations;
        this.timeoutMillis = timeout.toMillis();
    }

    @Override
    public void call(Stage2Send send) {
        CircuitBreaker breaker = breakers.circuitBreaker("mq:" + send.targetEndpointId(), BREAKER);
        if (!breaker.tryAcquirePermission()) {
            throw TargetFaultException.CircuitOpenException.of(breaker, send.targetEndpointId());
        }
        long started = System.nanoTime();
        Observation observation = Observation.createNotStarted("mq.target.send", observations).start();
        OutcomeClass outcome = OutcomeClass.TRANSIENT;
        MqMessage message = null;
        try {
            Optional<MqMessage> resolved;
            try {
                resolved = outbound.resolve(send);
            } catch (CsvContractException contract) {
                // a row the contract cannot express is permanent: the broker was never contacted, so it never counts
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the CSV contract of target endpoint "
                        + send.targetEndpointId() + " cannot express delivery " + send.deliveryId()
                        + " (" + contract.getMessage() + ")");
            }
            if (resolved.isEmpty()) {
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "delivery " + send.deliveryId() + " has no MQ message");
            }
            message = resolved.get();
            try {
                // shortcut: checkHost has no DNS pinning; the broker host is re-resolved at connect time
                guard.checkHost(message.settings().host());
            } catch (DestinationRefusedException refused) {
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the MQ host of target endpoint " + send.targetEndpointId()
                        + " is refused by the destination guard");
            }
            try {
                produce(message, send);
                outcome = OutcomeClass.SUCCESS;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            } catch (JMSSecurityException | InvalidDestinationException refused) {
                // the broker answered: a refusal is not a broker fault, so it does not count against the breaker
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the broker of target endpoint " + send.targetEndpointId()
                        + " refused the send (" + refused.getClass().getSimpleName() + ")");
            } catch (JMSException | RuntimeException failed) {
                breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, failed);
                throw new TargetFaultException.TransientFaultException("the send to the broker of target endpoint "
                        + send.targetEndpointId() + " failed (" + failed.getClass().getSimpleName() + ")");
            }
        } finally {
            long elapsed = System.nanoTime() - started;
            String tag = outcome.name();
            Timer.builder("mq.target.send").tag("target_type", "MQ").tag("outcome", tag).register(meters)
                    .record(elapsed, TimeUnit.NANOSECONDS);
            Counter.builder("mq.target.outcome").tag("target_type", "MQ").tag("outcome", tag).register(meters)
                    .increment();
            observation.lowCardinalityKeyValue("outcome", tag).stop();
            LOG.info("mq send delivery={} endpoint={} outcome={} ms={}", send.deliveryId(), send.targetEndpointId(),
                    tag, TimeUnit.NANOSECONDS.toMillis(elapsed));
            if (message != null && message.password() != null) {
                Arrays.fill(message.password(), '\0');
            }
        }
    }

    private void produce(MqMessage message, Stage2Send send) throws JMSException {
        MqSettings settings = message.settings();
        // no reconnect: a failed attempt goes back to layer-1 redelivery and the retry wait, never retried here
        String url = "tcp://" + settings.host() + ":" + settings.port()
                + "?reconnectAttempts=0&initialConnectAttempts=1&connectTimeoutMillis=" + timeoutMillis
                + "&callTimeout=" + timeoutMillis + "&callFailoverTimeout=" + timeoutMillis;
        String password = message.password() == null ? null : new String(message.password());
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url);
                Connection connection = factory.createConnection(message.user(), password);
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                MessageProducer producer = session.createProducer(session.createQueue(settings.queue()))) {
            BytesMessage body = session.createBytesMessage();
            body.writeBytes(message.body());
            body.setStringProperty(DUPLICATE_ID, send.deliveryId().toString());
            producer.send(body);
        }
    }
}
