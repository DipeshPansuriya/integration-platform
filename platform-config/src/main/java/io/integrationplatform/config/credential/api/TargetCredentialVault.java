package io.integrationplatform.config.credential.api;

import java.util.List;
import java.util.UUID;

/**
 * The read side of the target credentials for the send step (ADR-007 Decision 7 rule 6): the sealed rows of one target
 * endpoint of the bound tenant, read in the caller's open, tenant-bound transaction (R08, R11). The caller opens them
 * at send time and holds the values for the call only; nothing here decrypts or caches.
 */
@FunctionalInterface
public interface TargetCredentialVault {

    /** Every sealed field of the endpoint, for the bound tenant; empty when the endpoint has none or is not visible. */
    List<SealedCredential> sealedFor(UUID targetEndpointId);
}
