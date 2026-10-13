package io.integrationplatform.app.scheduler;

import io.integrationplatform.domain.scheduler.JobHandler;
import org.springframework.stereotype.Component;

/**
 * E14 T-056 placeholder body of PAYLOAD_PURGE (ADR-012 section 1): the type is registered and runs; its real
 * partition-drop body arrives in a later wave. ponytail: placeholder no-op body - the run/alert wrapper still
 * records every execution.
 */
@Component
public class PayloadPurgePlaceholderHandler implements JobHandler {

    @Override
    public String type() {
        return SchedulerJobTasks.PAYLOAD_PURGE;
    }

    @Override
    public void run(String jobData) {
        // the real purge body arrives with the retention wave; runs are recorded meanwhile
    }
}