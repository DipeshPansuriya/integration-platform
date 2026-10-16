package io.integrationplatform.ingest.port;

import tools.jackson.databind.JsonNode;

/**
 * The one common intake transaction (E11 A1 narrow, T-048; ADR-011 Decision 4): authenticate (the tenancy
 * interceptor, outside) -> resolve the interface's published version -> size and header checks (the controller)
 * -> business key -> <b>one transaction</b> that commits message, payload, outbox and the INTAKE stage timing
 * together (ADR-011 Decision 5's transactional outbox; E11-F1-S1 "no tracking id without the commit").
 *
 * <p>{@link #accept} runs in the implementer's own transaction (method-level {@code @Transactional}, R11, per the
 * f9d6de0 production fix); every persistence goes through {@code TenantScopedRepository} (R08/R11), so the tenant
 * is the bound one, never a parameter.
 */
public interface IntakeTransaction {

    /**
     * Accepts one source record into the platform.
     *
     * @return the tracking id (the message id) and the outbox row id, both existing only after the commit
     * @throws IntakeRefusedException a known refusal (unknown/unpublished interface, off-pin contract version,
     *         unresolved business key) - nothing is persisted
     */
    AcceptedMessage accept(IntakeRequest request);

    /**
     * @param interfaceCode the interface code from the intake path (per-tenant unique, V2_2_3)
     * @param sourceContractVersion the source contract version the caller sent ({@code X-Source-Contract-Version}),
     *        checked against the interface's pin (ADR-013)
     * @param payload the record as received (JSON, already parsed by the adapter)
     * @param idempotencyKey the caller's {@code Idempotency-Key} (ADR-011 Decision 4; its store/replay is E11 B1)
     */
    record IntakeRequest(String interfaceCode, String sourceContractVersion, JsonNode payload,
            String idempotencyKey) {
    }
}