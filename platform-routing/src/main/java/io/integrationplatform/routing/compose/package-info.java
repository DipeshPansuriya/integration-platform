/**
 * The running pipeline (E11 T-049, row 278 T106): the composition that turns the parts the epics built - the intake
 * transaction (E11), the outbox relay and the stage consumer base (E8), stage 1's fan-out and the claim (E9), the route
 * templates (T-116), the layer-1 route (T-119), the API adapter (E10) and the transform port (E7) - into one flow:
 * intake -> outbox -> Kafka -> stage 1 -> {@code platform.delivery.due} -> claim -> the target's route instance ->
 * layer 1 -> transform -> API adapter -> attempt rows and the delivery's final state. Active only when the deployment
 * runs a Kafka backbone ({@code IPASS_KAFKA_BOOTSTRAP_SERVERS}), the same condition as the relay's publish adapter.
 *
 * <p>Owned by E11 lane C (T-049); the parts it composes stay their owners' - this package adds glue only.
 */
package io.integrationplatform.routing.compose;
