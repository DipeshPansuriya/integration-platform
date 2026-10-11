package io.integrationplatform.config.system.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * The audit of every test-connection attempt and refusal (T-104): one structured event {@code event=system.test_connection}
 * per attempt, in the RefusedTokenAuditor style - tenant, actor, system, type, outcome and, when there is one, the
 * reason and the answer's status. Never the address, a header, a body or a secret (R07, R14); the platform audit
 * store (platform-audit) does not exist yet, so the log line is the record.
 */
public class TestConnectionAudit {

    private static final Logger LOG = LoggerFactory.getLogger(TestConnectionAudit.class);
    static final String EVENT = "system.test_connection";

    /**
     * @param outcome SUCCESS, REFUSED, FAILED, NOT_AVAILABLE_YET, NOT_CONFIGURED or RATE_LIMITED
     * @param reason  the refusal or failure reason (closed set), or {@code null}
     */
    public void record(UUID tenant, UUID actor, String systemId, String type, String outcome, String reason,
            Integer httpStatus) {
        boolean success = "SUCCESS".equals(outcome) || "NOT_AVAILABLE_YET".equals(outcome);
        LoggingEventBuilder event = (success ? LOG.atInfo() : LOG.atWarn())
                .addKeyValue("event", EVENT)
                .addKeyValue("tenant", tenant)
                .addKeyValue("actor", actor)
                .addKeyValue("system", systemId)
                .addKeyValue("type", type == null ? "-" : type)
                .addKeyValue("outcome", outcome);
        if (reason != null) {
            event = event.addKeyValue("reason", reason);
        }
        if (httpStatus != null) {
            event = event.addKeyValue("httpStatus", httpStatus);
        }
        event.log("event={} tenant={} system={} outcome={} reason={}", EVENT, tenant, systemId, outcome,
                reason == null ? "-" : reason);
    }
}
