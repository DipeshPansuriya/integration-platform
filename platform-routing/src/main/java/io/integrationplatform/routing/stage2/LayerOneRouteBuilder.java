package io.integrationplatform.routing.stage2;

import io.integrationplatform.domain.delivery.OutcomeClass;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.OnExceptionDefinition;

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
        Instant started = clock.get();
        try {
            targetCall.call(send);
            attempts.record(send.deliveryId(), 1, OutcomeClass.SUCCESS, started, clock.get(),
                    "layer-1 try " + tryNumber + ": success");
        } catch (TargetFaultException fault) {
            exchange.setProperty(LAST_OUTCOME, fault.outcomeClass());
            attempts.record(send.deliveryId(), 1, fault.outcomeClass(), started, clock.get(),
                    "layer-1 try " + tryNumber + ": " + fault.getMessage());
            throw fault; // redelivery follows the class: TRANSIENT redelivers, AMBIGUOUS/PERMANENT never
        }
    }

    /** Layer 1 ended without success: the exchange goes to the outcome path, exactly once. */
    private void handOver(Exchange exchange) {
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
     * report equals the rows); Camel's internal redelivery counter is not this processor's register to read.
     */
    private int tryNumber(Exchange exchange) {
        Integer previous = exchange.getProperty(LAST_TRY, Integer.class);
        return (previous == null ? 0 : previous) + 1;
    }
}