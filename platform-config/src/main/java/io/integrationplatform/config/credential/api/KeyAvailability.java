package io.integrationplatform.config.credential.api;

/**
 * The readiness signal of a missing key-encryption key (ADR-007 Decision 7 rule 5; ADR-010 section 9 item 4): the send
 * step reports a key id this instance does not hold, and the instance fails its readiness check until it has it.
 */
@FunctionalInterface
public interface KeyAvailability {

    /** A delivery needed {@code kekId} and this instance does not hold it. */
    void reportMissing(String kekId);
}
