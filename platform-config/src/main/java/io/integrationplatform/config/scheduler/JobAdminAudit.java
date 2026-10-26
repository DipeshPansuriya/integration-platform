package io.integrationplatform.config.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * E14 T-060 (ADR-012 section 5): the audit of every agent admin call - one structured event {@code event=job.admin}
 * naming the operation, the agent, the job and the outcome, in the TestConnectionAudit style. The platform audit
 * store (platform-audit) does not exist yet, so the log line is the record.
 */
@Component
public class JobAdminAudit {

    private static final Logger LOG = LoggerFactory.getLogger(JobAdminAudit.class);
    static final String EVENT = "job.admin";

    /** @param outcome OK, NOT_FOUND or NO_PENDING_EXECUTION; {@code jobType} is null for LIST_JOBS */
    public void record(String operation, String agent, String jobType, String outcome) {
        ("OK".equals(outcome) ? LOG.atInfo() : LOG.atWarn())
                .addKeyValue("event", EVENT)
                .addKeyValue("operation", operation)
                .addKeyValue("agent", agent)
                .addKeyValue("job", jobType == null ? "-" : jobType)
                .addKeyValue("outcome", outcome)
                .log("event={} operation={} agent={} job={} outcome={}", EVENT, operation, agent,
                        jobType == null ? "-" : jobType, outcome);
    }
}
