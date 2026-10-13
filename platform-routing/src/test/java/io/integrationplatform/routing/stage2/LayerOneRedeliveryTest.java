package io.integrationplatform.routing.stage2;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.domain.delivery.OutcomeClass;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E9-F1-S2 / T-119 (amit-developer, lane A1): Camel's error handler redelivers only transient failures -
 * at most 2 redeliveries, 500 ms initial delay, doubling, with jitter, the 5 s cap is delay time only - and
 * every try (the first and each redelivery) is a call that left the process and writes one
 * {@code delivery_attempt} row with {@code layer = 1} (ADR-010 section 4, E9-C5). When layer 1 ends without
 * success, the exchange is handed to the outcome path through the classifier's direct endpoint (Camel's
 * dead-letter channel is never pointed at a queue or topic); ambiguous and permanent failures never re-deliver.
 */
@Tag("e9-t119")
class LayerOneRedeliveryTest {

    private static final UUID DELIVERY = UUID.nameUUIDFromBytes("delivery-1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID CLAIM = UUID.nameUUIDFromBytes("claim-1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID ENDPOINT = UUID.nameUUIDFromBytes("endpoint-a".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private DefaultCamelContext context;
    private FakeAttempts attempts;
    private FakeOutcomePath outcomes;
    private TransientTarget target;

    @BeforeEach
    void aCamelContextWithTheLayerOneSendRoute() throws Exception {
        context = new DefaultCamelContext();
        attempts = new FakeAttempts();
        outcomes = new FakeOutcomePath();
        target = new TransientTarget();
    }

    @AfterEach
    void stopTheContext() throws Exception {
        if (context != null) {
            context.stop();
        }
    }

    private void startTheRoute() throws Exception {
        LayerOneRouteBuilder builder = new LayerOneRouteBuilder(target, attempts, outcomes, LayerOneWaits.defaults());
        context.addRoutes(builder);
        context.start();
    }

    private Stage2Send send(boolean targetDeclaresIdempotentReceive) {
        return new Stage2Send(DELIVERY, CLAIM, "key-1", ENDPOINT, targetDeclaresIdempotentReceive);
    }

    @Test
    void allRedeliveriesHappenInsideOneAttemptOfAFewSecondsAndEachCallWritesOneAttemptRow() throws Exception {
        target.failNext(2, OutcomeClass.TRANSIENT); // two transient failures, then success
        startTheRoute();
        long started = System.nanoTime();

        context.createProducerTemplate().sendBody("direct:stage2-send", send(false));

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(target.calls()).as("the first try + 2 redeliveries: three calls to the target").isEqualTo(3);
        assertThat(attempts.rows()).as("each call is exactly one delivery_attempt row, layer 1").hasSize(3);
        assertThat(attempts.layers()).containsOnly(1);
        assertThat(attempts.outcomes()).containsExactly(
                OutcomeClass.TRANSIENT, OutcomeClass.TRANSIENT, OutcomeClass.SUCCESS);
        assertThat(elapsedMillis)
                .as("layer 1 stays inside its few-seconds window: waits 500 ms + jitter, then 2 x that, capped")
                .isLessThan(5_000L).isGreaterThanOrEqualTo(500L);
        assertThat(outcomes.handedOff).as("success inside layer 1 never reaches the outcome hand-off").isZero();

        Duration firstWait = Duration.between(attempts.rows().get(0).finished(), attempts.rows().get(1).started());
        assertThat(firstWait.toMillis()).as("there is an initial wait between the tries; Camel's collision "
                + "avoidance may shorten it by the jitter (default factor 0.15)")
                .isGreaterThanOrEqualTo((long) (500 * 0.85));
        assertThat(Duration.between(attempts.rows().get(0).started(), attempts.rows().get(2).finished()))
                .as("the whole redelivery series is one in-attempt send of a few seconds").isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void theFirstTrySucceedingWritesOneAttemptRowAndNeverRedelivers() throws Exception {
        target.succeed();
        startTheRoute();

        context.createProducerTemplate().sendBody("direct:stage2-send", send(false));

        assertThat(target.calls()).isEqualTo(1);
        assertThat(attempts.rows()).hasSize(1);
        assertThat(attempts.outcomes()).containsExactly(OutcomeClass.SUCCESS);
        assertThat(outcomes.handedOff).isZero();
    }

    @Test
    void aTransientFailureRedeliveredToExhaustionHandsTheExchangeToTheOutcomePathOnce() throws Exception {
        target.alwaysFail(OutcomeClass.TRANSIENT);
        LayerOneWaits policy = new LayerOneWaits(2, java.time.Duration.ofMillis(1),
                java.time.Duration.ofSeconds(5)); // the doubling stays Camel's; the 1 ms initial delay keeps the test fast
        LayerOneRouteBuilder builder = new LayerOneRouteBuilder(target, attempts, outcomes, policy);
        context.addRoutes(builder);
        context.start();

        context.createProducerTemplate().sendBody("direct:stage2-send", send(false));

        assertThat(target.calls()).as("first try + 2 redeliveries, then layer 1 gives up").isEqualTo(3);
        assertThat(attempts.rows()).hasSize(3);
        assertThat(attempts.outcomes()).containsOnly(OutcomeClass.TRANSIENT);
        assertThat(outcomes.handedOff).as("exhausted layer 1 hands the exchange to the outcome path exactly once")
                .isEqualTo(1);
        assertThat(outcomes.lastOutcome()).isEqualTo(OutcomeClass.TRANSIENT);
        assertThat(outcomes.lastDeliveryId()).isEqualTo(DELIVERY);
        assertThat(outcomes.lastClaimToken()).isEqualTo(CLAIM);
        assertThat(outcomes.lastAttemptsSoFar()).isEqualTo(3);
    }

    @Test
    void anAmbiguousTimeoutNeverRedeliversButStillWritesItsOneAttemptRowAndHandsOff() throws Exception {
        target.alwaysFail(OutcomeClass.AMBIGUOUS); // a timeout after the request was sent
        startTheRoute();

        context.createProducerTemplate().sendBody("direct:stage2-send", send(false));

        assertThat(target.calls()).as("ambiguous failures are never redelivered").isEqualTo(1);
        assertThat(attempts.rows()).hasSize(1);
        assertThat(attempts.outcomes()).containsExactly(OutcomeClass.AMBIGUOUS);
        assertThat(outcomes.handoffOutcomeForLastTry()).isEqualTo(OutcomeClass.AMBIGUOUS);
    }

    @Test
    void anAmbiguousOutcomeAgainstAnIdempotentTargetHandsOffForARetryNotADeadLetter() throws Exception {
        target.alwaysFail(OutcomeClass.AMBIGUOUS);
        startTheRoute();

        context.createProducerTemplate().sendBody("direct:stage2-send", send(true));

        assertThat(target.calls()).isEqualTo(1);
        assertThat(attempts.rows()).hasSize(1);
        assertThat(outcomes.lastTargetDeclaresIdempotentReceive()).isTrue();
    }

    @Test
    void aPermanentFailureNeverRedeliversAndHandsOffForTheDeadLetter() throws Exception {
        target.alwaysFail(OutcomeClass.PERMANENT);
        startTheRoute();

        context.createProducerTemplate().sendBody("direct:stage2-send", send(false));

        assertThat(target.calls()).isEqualTo(1);
        assertThat(attempts.rows()).hasSize(1);
        assertThat(outcomes.lastOutcome()).isEqualTo(OutcomeClass.PERMANENT);
    }

    /** The fake port the attempt rows are recorded through; a real adapter binds to the delivery ledger. */
    static final class FakeAttempts implements Stage2AttemptWriter {

        private record Row(UUID deliveryId, int layer, OutcomeClass outcome, Instant started, Instant finished,
                String detail) {
        }

        private final List<Row> rows = new ArrayList<>();

        List<Row> rows() {
            return rows;
        }

        List<OutcomeClass> outcomes() {
            return rows.stream().map(Row::outcome).toList();
        }

        List<Integer> layers() {
            return rows.stream().map(Row::layer).toList();
        }

        @Override
        public void record(UUID deliveryId, int layer, OutcomeClass outcome, Instant startedAt, Instant finishedAt,
                String detail) {
            rows.add(new Row(deliveryId, layer, outcome, startedAt, finishedAt, detail));
        }
    }

    /** The fake outcome path: the direct: hand-off the recorder implements at integration. */
    static final class FakeOutcomePath implements Stage2OutcomeHandoff {

        int handedOff;
        private UUID deliveryId;
        private UUID claimToken;
        private int attemptsSoFar;
        private OutcomeClass outcome;
        private boolean targetDeclaresIdempotentReceive;

        OutcomeClass lastOutcome() {
            return outcome;
        }

        UUID lastDeliveryId() {
            return deliveryId;
        }

        UUID lastClaimToken() {
            return claimToken;
        }

        int lastAttemptsSoFar() {
            return attemptsSoFar;
        }

        boolean lastTargetDeclaresIdempotentReceive() {
            return targetDeclaresIdempotentReceive;
        }

        OutcomeClass handoffOutcomeForLastTry() {
            return outcome;
        }

        @Override
        public void handOffAfterLayerOne(UUID deliveryId, UUID claimToken, int attemptsSoFar, OutcomeClass outcome,
                boolean targetDeclaresIdempotentReceive) {
            handedOff++;
            this.deliveryId = deliveryId;
            this.claimToken = claimToken;
            this.attemptsSoFar = attemptsSoFar;
            this.outcome = outcome;
            this.targetDeclaresIdempotentReceive = targetDeclaresIdempotentReceive;
        }
    }

    /** A fake target call: fails a fixed number of times with a given class, then (or always) behaves accordingly. */
    static final class TransientTarget implements TargetCall {

        private int remainingFailures;
        private OutcomeClass failureClass;
        private boolean always;
        private int calls;

        void failNext(int count, OutcomeClass outcomeClass) {
            this.remainingFailures = count;
            this.failureClass = outcomeClass;
        }

        void alwaysFail(OutcomeClass outcomeClass) {
            this.always = true;
            this.failureClass = outcomeClass;
        }

        void succeed() {
        }

        int calls() {
            return calls;
        }

        @Override
        public void call(Stage2Send send) {
            calls++;
            boolean fail = always || remainingFailures > 0;
            if (remainingFailures > 0) {
                remainingFailures--;
            }
            if (fail) {
                // the throwing adapter classifies into the subclass: TRANSIENT is the redelivering one
                TargetFaultException fault = failureClass == OutcomeClass.TRANSIENT
                        ? new TargetFaultException.TransientFaultException("fake transient failure on try " + calls)
                        : new TargetFaultException(failureClass, "fake " + failureClass + " failure on try " + calls);
                throw fault;
            }
        }
    }
}