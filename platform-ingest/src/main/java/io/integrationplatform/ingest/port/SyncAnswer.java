package io.integrationplatform.ingest.port;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.JsonNode;

/**
 * T-129 part 1 (architecture.md 4.2, ADR-011 Decision 5): the inline answer for a SYNC interface. Intake calls it
 * after the intake transaction committed, on the request thread (tenant context still bound). The future completes
 * with the body already mapped to the source's response contract; an exceptional completion, or none within the
 * 10 s budget, answers 202 {trackingId, status: PENDING} and the deferred outbox row carries the delivery on.
 * Optional: with no bean, intake answers 202 as for ASYNC.
 */
@FunctionalInterface
public interface SyncAnswer {

    CompletableFuture<JsonNode> answer(UUID trackingId);
}
