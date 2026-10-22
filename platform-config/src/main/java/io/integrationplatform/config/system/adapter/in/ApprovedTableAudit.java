package io.integrationplatform.config.system.adapter.in;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.tenancy.port.LoginBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E16 F3: one structured log line per approved-table call - request, approve, revoke, completed OR refused - in the
 * {@code ContractAudit} style ({@code event, tenant, actor, system, subject, outcome}); never a body or a secret
 * (R14). The ledger record of a completed action is the {@code AuditEvent} the service publishes inside its
 * transaction; a refusal rolls that back, so this line is the only record of a refusal (a self-approval attempt is
 * visible here).
 */
final class ApprovedTableAudit {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovedTableAudit.class);
    private static final int MAX_VALUE = 64;

    private ApprovedTableAudit() {
    }

    static void record(String event, LoginBinding actor, String system, String subject, String outcome) {
        String tenant = String.valueOf(actor.tenantId());
        String user = String.valueOf(actor.userId());
        String systemValue = clean(system);
        String subjectValue = clean(subject);
        LOG.atInfo()
                .addKeyValue("event", event)
                .addKeyValue("tenant", tenant)
                .addKeyValue("actor", user)
                .addKeyValue("system", systemValue)
                .addKeyValue("subject", subjectValue)
                .addKeyValue("outcome", outcome)
                .log("event={} tenant={} actor={} system={} subject={} outcome={}", event, tenant, user,
                        systemValue, subjectValue, outcome);
    }

    /** The named refusal ({@code REQUESTER_IS_APPROVER}, ...) or the status class of an unnamed one. */
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

    /** No control characters from a path or body reach the log (log forging), and the value is capped. */
    private static String clean(String value) {
        String flat = value == null ? "-" : value.replaceAll("\\p{Cntrl}", "_");
        return flat.length() > MAX_VALUE ? flat.substring(0, MAX_VALUE) : flat;
    }
}
