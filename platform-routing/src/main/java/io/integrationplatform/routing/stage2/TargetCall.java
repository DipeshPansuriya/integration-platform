package io.integrationplatform.routing.stage2;

/**
 * One target call of a route instance (T-116/T-119 seam): the template's send behaviour - what E10's adapters
 * (ADR-015) implement per target type and stage 2's layer-1 route invokes for every try. The call is everything
 * that leaves the process; the caller (the layer-1 route) does nothing but record it - one
 * {@code delivery_attempt} row per call, classified by the fault the adapter raises.
 */
public interface TargetCall {

    /**
     * Performs one call for this send. Success returns normally; a failure throws
     * {@link TargetFaultException} with the call's classified {@code OutcomeClass} - never a status code or a
     * raw exception, so redelivery follows one register everywhere (ADR-010 section 5).
     *
     * @throws TargetFaultException the classified failure of this call
     */
    void call(Stage2Send send);
}