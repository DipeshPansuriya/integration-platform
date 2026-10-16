package io.integrationplatform.ingest.internal.intake;

import io.integrationplatform.ingest.port.IntakeRefusedException;
import tools.jackson.databind.JsonNode;

/**
 * The narrow business-key extractor (E11 A1 narrow): the flow version's {@code business_key_rule} as a dot path
 * into the payload ("customer_code", "order.customer"). The full rule language (operators, functions, several
 * fields) is the transform lane's B1 work - this is the placeholder it replaces, and until then the dot path is
 * the only accepted rule shape; anything else refuses the record 422 (never a silent misroute).
 */
final class BusinessKeyRule {

    private BusinessKeyRule() {
    }

    /**
     * Resolves the record's business key from the rule.
     *
     * @throws IntakeRefusedException a blank rule, an unsupported rule shape, or a payload that does not resolve
     */
    static String resolve(String rule, JsonNode payload) {
        if (rule == null || rule.isBlank()) {
            throw new IntakeRefusedException(IntakeRefusedException.Reason.BUSINESS_KEY_UNRESOLVED,
                    "the interface version carries no business key rule");
        }
        JsonNode current = payload;
        for (String part : rule.split("\\.")) {
            current = current == null ? null : current.get(part);
        }
        String key = current == null || current.isNull() ? null : current.asText(null);
        if (key == null || key.isBlank()) {
            throw new IntakeRefusedException(IntakeRefusedException.Reason.BUSINESS_KEY_UNRESOLVED,
                    "the business key rule '" + rule + "' does not resolve on this record");
        }
        return key;
    }
}