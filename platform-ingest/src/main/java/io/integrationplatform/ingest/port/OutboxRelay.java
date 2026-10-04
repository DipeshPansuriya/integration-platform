package io.integrationplatform.ingest.port;

/**
 * The outbox relay (architecture.md 4.1 step 6; ADR-005 Decisions 11 and 12): publishes unpublished outbox rows and
 * marks them published.
 *
 * <p>Contract: one call is one relay cycle over every active tenant, one tenant per transaction (ADR-011 "scanning
 * jobs"); a row is marked published only after {@link PublishPort#acknowledge} returned {@code true}; a crash at any
 * point leaves every unmarked row for the next cycle - at least once, never lost (RPO zero, E2-C1).
 *
 * <p>Implemented by lane B (T-008); driven by the scheduler and by lane D's kill-the-relay test (T-009).
 */
public interface OutboxRelay {

    /** Runs one cycle and returns the number of rows marked published in it. */
    int runOnce();
}
