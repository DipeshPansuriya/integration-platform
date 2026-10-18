package io.integrationplatform.config.contract.adapter.in;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.tenancy.port.LoginBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T-105: one structured audit event per contract import and approval, in the {@code RefusedTokenAuditor} style
 * ({@code event=..., tenant, actor, version, outcome}) - never a body, a schema or a secret (R14). The ledger record
 * of a completed import or approval is the {@code AuditEvent} {@code ContractAdminService} publishes inside its
 * transaction (E12-F2-S1, T-134); a refusal rolls that back, so this log line is its only record.
 */
final class ContractAudit {

    private static final Logger LOG = LoggerFactory.getLogger(ContractAudit.class);
    private static final int MAX_VALUE = 64;

    private ContractAudit() {
    }

    static void record(String event, LoginBinding actor, String version, String outcome) {
        String tenant = String.valueOf(actor.tenantId());
        String user = String.valueOf(actor.userId());
        String target = clean(version);
        LOG.atInfo()
                .addKeyValue("event", event)
                .addKeyValue("tenant", tenant)
                .addKeyValue("actor", user)
                .addKeyValue("version", target)
                .addKeyValue("outcome", outcome)
                .log("event={} tenant={} actor={} version={} outcome={}", event, tenant, user, target, outcome);
    }

    /** The named refusal ({@code AUTHOR_IS_APPROVER}, ...) or the status class of an unnamed one. */
    static String outcomeOf(ConfigApiException refused) {
        Object refusal = refused.body().get("refusal");
        if (refusal != null) {
            return String.valueOf(refusal);
        }
        return switch (refused.httpStatus()) {
            case 400 -> "INVALID";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            default -> "REFUSED_" + refused.httpStatus();
        };
    }

    /** No control characters from a path reach the log (log forging), and the value is capped. */
    private static String clean(String value) {
        String flat = value == null ? "-" : value.replaceAll("\\p{Cntrl}", "_");
        return flat.length() > MAX_VALUE ? flat.substring(0, MAX_VALUE) : flat;
    }
}
