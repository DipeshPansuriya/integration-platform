package io.integrationplatform.ingest.internal.intake;

import io.integrationplatform.config.flow.api.MaskedFields;
import io.integrationplatform.ingest.port.IntakeRefusedException;
import io.integrationplatform.ingest.port.IntakeTransaction.RecordResult;
import io.integrationplatform.ingest.port.IntakeTransaction.RecordViolation;
import io.integrationplatform.ingest.validation.PayloadValidationService;
import io.integrationplatform.ingest.validation.RejectionEnvelope;
import io.integrationplatform.ingest.validation.Violation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * B37 (ADR-011 Decision 4, E6-F2-S1): the intake's validation step - per record, the published source contract's
 * JSON Schema first, then the interface's VALIDATION rule sets, every violation in one answer (never first-error-only).
 * A rejected record becomes a REJECTED {@link RecordResult} with no tracking id; the caller writes nothing for it.
 *
 * <p>No submitted value leaves here (T-066): the answer carries the field path, the code and the validator's or the
 * rule row's message, and a message is scrubbed of any registered masked value as a last guard; the log line carries
 * only counts and {@code path:code} pairs.
 */
final class IntakeValidation {

    private static final Logger log = LoggerFactory.getLogger(IntakeValidation.class);
    private static final int LOGGED_PAIRS = 10;

    private final PayloadValidationService validation;

    IntakeValidation(PayloadValidationService validation) {
        this.validation = Objects.requireNonNull(validation, "validation");
    }

    /**
     * @param contractKey the immutable id of the contract version (with the tenant): the compiled schema is cached under it
     * @param contractSchema the contract's JSON Schema, read only on a cache miss
     * @return one entry per record: {@code null} for a valid record, the REJECTED result otherwise
     */
    RecordResult[] check(Object contractKey, Supplier<JsonNode> contractSchema, List<UUID> validationRuleSetIds,
            List<JsonNode> records, String interfaceCode) {
        RecordResult[] out = new RecordResult[records.size()];
        int rejected = 0;
        int violationCount = 0;
        Set<String> pairs = new LinkedHashSet<>();
        for (int i = 0; i < out.length; i++) {
            RejectionEnvelope envelope = validation.validate(contractKey, contractSchema, validationRuleSetIds,
                    records.get(i));
            if (!envelope.rejected()) {
                continue;
            }
            List<RecordViolation> violations = new ArrayList<>(envelope.violations().size());
            for (Violation violation : envelope.violations()) {
                violations.add(new RecordViolation(violation.field(), violation.code(),
                        MaskedFields.scrub(violation.message()), violation.source().name()));
                if (pairs.size() < LOGGED_PAIRS) {
                    pairs.add(violation.field() + ":" + violation.code());
                }
            }
            rejected++;
            violationCount += violations.size();
            String rules = envelope.rulesEvaluated() ? ""
                    : " (not a JSON object of the contract's type: rules not evaluated)";
            out[i] = RecordResult.rejected(i, IntakeRefusedException.Reason.PAYLOAD_VALIDATION_FAILED.name(),
                    "the record breaks the source contract or its validation rules: " + violations.size()
                            + " violation(s)" + rules, violations);
        }
        if (rejected > 0) {
            // paths and rule/schema codes only - never a value (T-066); the pairs are capped
            log.info("intake validation: {} of {} records rejected with {} violations on interface {} (first: {})",
                    rejected, out.length, violationCount, interfaceCode, pairs);
        }
        return out;
    }
}
