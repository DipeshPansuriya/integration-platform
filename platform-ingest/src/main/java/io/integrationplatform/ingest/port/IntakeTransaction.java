package io.integrationplatform.ingest.port;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The one common intake transaction (E11 A1 narrow, T-048; ADR-011 Decision 4): authenticate (the tenancy
 * interceptor, outside) -> resolve the interface's published version -> size and header checks (the controller)
 * -> business key -> <b>one transaction</b> that commits message, payload, outbox and the INTAKE stage timing
 * together (ADR-011 Decision 5's transactional outbox; E11-F1-S1 "no tracking id without the commit").
 *
 * <p>{@link #accept} runs in the implementer's own transaction (method-level {@code @Transactional}, R11, per the
 * f9d6de0 production fix); every persistence goes through {@code TenantScopedRepository} (R08/R11), so the tenant
 * is the bound one, never a parameter. T115 adds the bulk call, Idempotency-Key replay (T-187) and per-key
 * ordering (T-196).
 */
public interface IntakeTransaction {

    /**
     * Accepts one source record into the platform.
     *
     * @return the tracking id (the message id) and the outbox row id, both existing only after the commit
     * @throws IntakeRefusedException a known refusal (unknown/unpublished interface, off-pin contract version,
     *         unresolved business key, reused Idempotency-Key) - nothing is persisted
     */
    AcceptedMessage accept(IntakeRequest request);

    /**
     * Accepts N records in one transaction (T-128): one result per record, in order; invalid records are REJECTED
     * and write nothing. A repeat of the same key and body replays the original results and writes nothing.
     */
    default List<RecordResult> acceptBulk(BulkRequest request) {
        throw new UnsupportedOperationException("bulk intake is not supported by this implementation");
    }

    /**
     * @param interfaceCode the interface code from the intake path (per-tenant unique, V2_2_3)
     * @param sourceContractVersion the source contract version the caller sent ({@code X-Source-Contract-Version}),
     *        checked against the interface's pin (ADR-013)
     * @param payload the record as received (JSON, already parsed by the adapter)
     * @param idempotencyKey the caller's {@code Idempotency-Key} (ADR-011 Decision 4; T-187 replay)
     * @param credentialRef the authenticated source credential the key is scoped to (T-187)
     */
    record IntakeRequest(String interfaceCode, String sourceContractVersion, JsonNode payload,
            String idempotencyKey, String credentialRef) {

        public IntakeRequest(String interfaceCode, String sourceContractVersion, JsonNode payload,
                String idempotencyKey) {
            this(interfaceCode, sourceContractVersion, payload, idempotencyKey, "");
        }
    }

    /** The bulk call (T-128): the records in arrival order under one Idempotency-Key. */
    record BulkRequest(String interfaceCode, String sourceContractVersion, List<JsonNode> records,
            String idempotencyKey, String credentialRef) {
    }

    /**
     * One violation of a rejected record (B37, E6-F2-S1): where it is, a stable code, the message, and which step
     * found it ({@code SCHEMA} or {@code RULE}). Never a submitted value (T-066).
     */
    record RecordViolation(String field, String code, String message, String source) {
    }

    /**
     * One record's answer: ACCEPTED with its tracking id, or REJECTED with the refusal code and - for a record that
     * broke its contract or rules (B37) - every violation (path, code, message), never a tracking id.
     */
    record RecordResult(int index, String status, UUID trackingId, Long outboxId, String code, String message,
            List<RecordViolation> violations) {

        public static final String ACCEPTED = "ACCEPTED";
        public static final String REJECTED = "REJECTED";

        public RecordResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        public RecordResult(int index, String status, UUID trackingId, Long outboxId, String code, String message) {
            this(index, status, trackingId, outboxId, code, message, List.of());
        }

        public static RecordResult rejected(int index, String code, String message, List<RecordViolation> violations) {
            return new RecordResult(index, REJECTED, null, null, code, message, violations);
        }

        public static RecordResult accepted(int index, UUID trackingId, long outboxId) {
            return new RecordResult(index, ACCEPTED, trackingId, outboxId, null, null);
        }

        public static RecordResult rejected(int index, String code, String message) {
            return new RecordResult(index, REJECTED, null, null, code, message);
        }
    }
}
