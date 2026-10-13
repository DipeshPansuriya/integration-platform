package io.integrationplatform.app.scheduler;

import io.integrationplatform.domain.scheduler.JobHandler;
import org.springframework.stereotype.Component;

/**
 * E14 T-056 placeholder body of CREDENTIAL_CLEANUP (ADR-012 section 1): the type is registered and runs; its real
 * expire-and-sweep body arrives in a later wave. ponytail: placeholder no-op body - the run/alert wrapper still
 * records every execution.
 */
@Component
public class CredentialCleanupPlaceholderHandler implements JobHandler {

    @Override
    public String type() {
        return SchedulerJobTasks.CREDENTIAL_CLEANUP;
    }

    @Override
    public void run(String jobData) {
        // the real cleanup body arrives with the credential wave; runs are recorded meanwhile
    }
}