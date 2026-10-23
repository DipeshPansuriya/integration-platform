package io.integrationplatform.routing.stage2;

import io.integrationplatform.domain.delivery.OutcomeClass;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.OnExceptionDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The layer-1 send route (T-119; ADR-010 section 4): one Camel route whose error handler redelivers ONLY
 * transient failures - at most 2 redeliveries, 500 ms initial delay, doubling with jitter, wait cap 5 s (delay
 * time only) - so every re-try stays inside ONE attempt of a few seconds (E9-C5). The try processor re-runs for
 * the first try and every redelivery, and performs exactly one target call, so the writer records exactly one
 * {@code delivery_attempt} row with {@code layer = 1} per call - "the report equals the rows".
 *
 * <p>When layer 1 ends without success, the onException clauses hand the exchange to the outcome path exactly
 * once, through {@link Stage2OutcomeHandoff} - the direct-endpoint hand-off the existing recorder path
 * implements; Camel's dead-letter channel is never pointed at a queue or topic (it is not used here at all -
 * the hand-off decides, per the claim token, RETRY_WAIT or the dead letter).
 *
 * <p>The claim is NOT this route's concern: the caller claims through the existing {@code DeliveryClaimHandler}
 * before invoking {@code direct:stage2-send}; nothing here changes the claim. Target calls go through
 * {@link TargetCall} - the template's route-instance behaviour - never a component URI chosen by configuration
 * (ADR-010 section 3).
 */
public final class LayerOneRouteBuilder extends RouteBuilder {

    /** The direct endpoint the stage-2 dispatch (toD, direct-only schemes) sends the claimed send to. */
    public static final String LAYER_ONE_SEND = "direct:stage2-send";

    /** Exchange property: the try number of the last performed call (1 on the first try, one more per redelivery). */
    static final String LAST_TRY = "stage2.lastTry";

    /** Exchange property: the classified outcome of the last performed call. */
    static final String LAST_OUTCOME = "stage2.lastOutcome";

    /**
     * Exchange property (T188): layer 1's explicit final outcome - {@code Boolean.TRUE} only when a try succeeded and its
     * SUCCESS attempt row was written, {@code Boolean.FALSE} on every other exit (transient-exhausted, permanent,
     * ambiguous, circuit-open). The dispatch marks DELIVERED on this flag, not on the absence of Camel's caught exception.
     */
    public static final String LAYER_ONE_SUCCEEDED = "stage2.layerOneSucceeded";

    /** Exchange property: how long the open circuit refuses calls (set only by a breaker refusal). */
    public static final String CIRCUIT_RETRY_AFTER = "stage2.circuitRetryAfter";
    public static final String CIRCUIT_DEFERRED = "stage2.circuitDeferred";

    private static final Logger log = LoggerFactory.getLogger(LayerOneRouteBuilder.class);

    private final TargetCall targetCall;
    private final Stage2AttemptWriter attempts;
    private final Stage2OutcomeHandoff outcomeHandoff;
    private final LayerOneWaits waits;
    private final Supplier<Instant> clock;

    public LayerOneRouteBuilder(TargetCall targetCall, Stage2AttemptWriter attempts,
            Stage2OutcomeHandoff outcomeHandoff, LayerOneWaits waits) {
        this(targetCall, attempts, outcomeHandoff, waits, () -> Instant.now());
    }

    public LayerOneRouteBuilder(TargetCall targetCall, Stage2AttemptWriter attempts,
            Stage2OutcomeHandoff outcomeHandoff, LayerOneWaits waits, Supplier<Instant> clock) {
        this.targetCall = Objects.requireNonNull(targetCall, "targetCall");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.outcomeHandoff = Objects.requireNonNull(outcomeHandoff, "outcomeHandoff");
        this.waits = Objects.requireNonNull(waits, "waits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void configure() {
        // E10 T96: a call the breaker refused is not a call - no redelivery, no attempt row, one deferral
        onException(TargetFaultException.CircuitOpenException.class)
                .maximumRedeliveries(0)
                .handled(true)
                .process(this::deferCircuitOpen);
        // only a TRANSIENT call failure redelivers (ADR-010 section 5); the wait cap is delay time only
        onException(TargetFaultException.TransientFaultException.class)
                .maximumRedeliveries(waits.maxRedeliveries())
                .redeliveryDelay(waits.initialDelay().toMillis())
                .useExponentialBackOff()
                .backOffMultiplier(2.0)
                .maximumRedeliveryDelay(waits.delayCap().toMillis())
                .useCollisionAvoidance() // the jitter: each doubled wait carries a random share, capped
                .handled(true)
                .process(this::handOver);
        // AMBIGUOUS and PERMANENT never redeliver; the one attempt row is already written - hand over once
        onException(TargetFaultException.class)
                .maximumRedeliveries(0)
                .handled(true)
                .process(this::handOver);

        from(LAYER_ONE_SEND)
                .id("stage2-layer-one-try")
                .process(this::oneTry);
    }

    /** One try: one call, exactly one attempt row, then the failure back to the error handler for redelivery. */
    private void oneTry(Exchange exchange) {
        Stage2Send send = exchange.getIn().getBody(Stage2Send.class);
        Objects.requireNonNull(send, "a stage-2 send body is required");
        int tryNumber = tryNumber(exchange);
        exchange.setProperty(LAST_TRY, tryNumber);
        exchange.setProperty(LAYER_ONE_SUCCEEDED, Boolean.FALSE); // a try is a failure until it records its SUCCESS
        Instant started = clock.get();
        try {
            targetCall.call(send);
            attempts.record(send.deliveryId(), 1, OutcomeClass.SUCCESS, started, clock.get(),
                    "layer-1 try " + tryNumber + ": success");
            log.info("stage 2: delivery {} - layer-1 try {}: SUCCESS", send.deliveryId(), tryNumber);
            // B32: Camel leaves the earlier try's exception on the exchange after a redelivery; the dispatch reads that
            // property as "layer 1 failed", so a success after TRANSIENT tries must clear it or the delivery stays SENDING
            exchange.removeProperty(Exchange.EXCEPTION_CAUGHT);
            // T188: say it explicitly - the dispatch decides on this flag, not on the absence of the caught exception
            exchange.setProperty(LAYER_ONE_SUCCEEDED, Boolean.TRUE);
        } catch (TargetFaultException.CircuitOpenException open) {
            exchange.setProperty(CIRCUIT_RETRY_AFTER, open.retryAfter());
            log.info("stage 2: delivery {} - layer-1 try {}: refused by the open circuit, nothing sent",
                    send.deliveryId(), tryNumber);
            throw open; // no attempt row: the call never left the process
        } catch (TargetFaultException fault) {
            exchange.setProperty(LAST_OUTCOME, fault.outcomeClass());
            attempts.record(send.deliveryId(), 1, fault.outcomeClass(), started, clock.get(),
                    "layer-1 try " + tryNumber + ": " + fault.getMessage());
            log.info("stage 2: delivery {} - layer-1 try {}: {}", send.deliveryId(), tryNumber, fault.outcomeClass());
            throw fault; // redelivery follows the class: TRANSIENT redelivers, AMBIGUOUS/PERMANENT never
        }
    }

    /** The breaker refused the call: the delivery waits until the breaker may let a trial call through. */
    private void deferCircuitOpen(Exchange exchange) {
        deferCircuitOpenFor(exchange, outcomeHandoff, clock);
    }

    static void deferCircuitOpenFor(Exchange exchange, Stage2OutcomeHandoff handoff, Supplier<Instant> clock) {
        Stage2Send send = exchange.getIn().getBody(Stage2Send.class);
        exchange.setProperty(LAYER_ONE_SUCCEEDED, Boolean.FALSE);
        java.time.Duration retryAfter = exchange.getProperty(CIRCUIT_RETRY_AFTER, java.time.Duration.class);
        if (retryAfter == null) { // T191: never fail the deferral for a missing wait - the floor of CircuitOpenException
            retryAfter = java.time.Duration.ofSeconds(1);
        }
        handoff.deferCircuitOpen(send.deliveryId(), send.claimToken(), clock.get().plus(retryAfter));
        exchange.setProperty(CIRCUIT_DEFERRED, Boolean.TRUE); // the explicit signal the dispatch reads
    }

    /** Layer 1 ended without success: the exchange goes to the outcome path, exactly once. */
    private void handOver(Exchange exchange) {
        exchange.setProperty(LAYER_ONE_SUCCEEDED, Boolean.FALSE);
        Stage2Send send = exchange.getIn().getBody(Stage2Send.class);
        Integer lastTry = exchange.getProperty(LAST_TRY, Integer.class);
        OutcomeClass lastOutcome = exchange.getProperty(LAST_OUTCOME, OutcomeClass.class);
        outcomeHandoff.handOffAfterLayerOne(send.deliveryId(), send.claimToken(),
                lastTry == null ? 1 : lastTry, lastOutcome, send.targetDeclaresIdempotentReceive());
    }

    /**
     * The try this processor invocation performs: 1 on the first try, one more on every redelivery. Counted from
     * the exchange's own {@link #LAST_TRY} - the count of performed tries - because that is what the hand-off
     * reports to layer 2 as {@code attemptsSoFar}, and it must equal the number of recorded attempt rows (the
     * report equals the rows); Camel's internal redelivery counter is not this processor's register to read. That
     * {@code attemptsSoFar} is advisory only: the attempt budget is counted from the attempt rows since the latest
     * {@code REPLAY} (see {@code SendOutcomes#recordFailure} and {@code JdbcSendOutcomes}).
     */
    private int tryNumber(Exchange exchange) {
        Integer previous = exchange.getProperty(LAST_TRY, Integer.class);
        return (previous == null ? 0 : previous) + 1;
    }
}