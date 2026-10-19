package io.integrationplatform.routing.stage2;

import io.integrationplatform.domain.delivery.OutcomeClass;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

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

    /**
     * Row 327: the call for the send's target type ("MQ", "API" - the stored {@code system.type}); a missing or
     * unknown type is refused PERMANENT, so no send is ever silently dropped or dialled through the wrong adapter.
     */
    static TargetCall byType(Function<UUID, Optional<String>> typeOfEndpoint, Map<String, TargetCall> calls) {
        Map<String, TargetCall> byType = Map.copyOf(calls);
        return send -> {
            String type = typeOfEndpoint.apply(send.targetEndpointId()).orElse(null);
            TargetCall call = type == null ? null : byType.get(type);
            if (call == null) {
                throw new TargetFaultException(OutcomeClass.PERMANENT, "no target call for type " + type);
            }
            call.call(send);
        };
    }
}
