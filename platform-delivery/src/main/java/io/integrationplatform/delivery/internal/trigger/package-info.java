/**
 * The delivery-retry trigger (T-090, E2-F2): the phase-2 minimal DB-polling re-drive over lane C's
 * {@link io.integrationplatform.delivery.port.DeliveryRetryStore} - per active tenant one transaction that
 * locks due {@code RETRY_WAIT} deliveries, moves them to {@code DUE} and re-drives them through the frozen
 * {@link io.integrationplatform.ingest.port.OutboxWriter} (ADR-010 section 4 "polling trigger, phase 2",
 * ADR-011 Decision 8). No broker client and no own transaction: the relay publishes what this appends.
 */
package io.integrationplatform.delivery.internal.trigger;
