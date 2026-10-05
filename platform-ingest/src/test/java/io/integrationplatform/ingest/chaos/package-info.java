/**
 * Lane D (T-009, E2-F1-S1): the kill-the-relay chaos suite that proves RPO zero for accepted messages
 * (E2-C1, E2-C2, E2-C8). Test-only - no production code lives in this package; every class here is a fixture,
 * a fault-injecting decorator of the frozen {@code PublishPort} seam, or the container test itself.
 *
 * <p>Integration contract with lanes A and B (via arjun-fullstack): the suite takes
 * {@code io.integrationplatform.ingest.port.OutboxWriter} (lane A, T-007) and {@code OutboxRelay}
 * (lane B, T-008) as Spring beans <em>by interface type</em> from a context that scans
 * {@code io.integrationplatform.ingest}. No class name of either lane is referenced, so this suite compiles
 * against the frozen seam alone and turns green the moment the lanes are integrated; until then every test
 * fails with a named "not integrated" message. The relay receives THIS package's fault-injecting
 * {@link io.integrationplatform.ingest.chaos.FaultInjectingPublishPort} as its {@code PublishPort} (a
 * {@code @Primary} bean - if lane B's adapter is also {@code @Primary}, the collision is resolved at
 * integration by arjun-fullstack, not here).
 */
package io.integrationplatform.ingest.chaos;
