/**
 * Ports of platform-ingest that other lanes and modules call or implement (ADR-001 item 5).
 *
 * <p>E2 seam (arjun-fullstack), fixed before the lanes start because three lanes build on them in one epic: lane A
 * implements {@link io.integrationplatform.ingest.port.OutboxWriter} (T-007) and detects duplicates on the receiving
 * side by {@link io.integrationplatform.ingest.port.OutboundRecord#idempotencyKey()} (T-010); lane B implements
 * {@link io.integrationplatform.ingest.port.OutboxRelay} through {@link io.integrationplatform.ingest.port.PublishPort}
 * only, plus the in-memory adapter (T-008, T-041, T-042); lane D drives both from the kill-the-relay test (T-009). The
 * outbox table they share is {@code V2_0_1__outbox.sql}. A lane that needs a change to a type here reports it to
 * arjun-fullstack instead of editing it.
 */
package io.integrationplatform.ingest.port;
