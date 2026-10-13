package io.integrationplatform.app.scheduler;

import io.integrationplatform.domain.scheduler.JobHandler;
import org.springframework.stereotype.Component;

/**
 * E14 T-056 placeholder body of DAILY_REPORT (ADR-012 section 1): the type is registered and runs; its real
 * dashboard day roll-up arrives in a later wave. ponytail: placeholder no-op body - the run/alert wrapper still
 * records every execution.
 */
@Component
public class DailyReportPlaceholderHandler implements JobHandler {

    @Override
    public String type() {
        return SchedulerJobTasks.DAILY_REPORT;
    }

    @Override
    public void run(String jobData) {
        // the real body arrives with the dashboard wave; runs are recorded meanwhile
    }
}