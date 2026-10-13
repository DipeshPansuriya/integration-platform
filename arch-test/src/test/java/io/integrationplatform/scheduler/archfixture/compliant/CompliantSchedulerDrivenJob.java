package io.integrationplatform.scheduler.archfixture.compliant;

import io.integrationplatform.domain.scheduler.JobHandler;

/**
 * The compliant shape of E14 T-056 (ADR-012 section 2/3): a job body that carries no timing of its own - it sits
 * behind the plain JobHandler SPI and db-scheduler owns every schedule from the {@code job_definition} row. No
 * {@code @Scheduled}, no {@code @EnableScheduling}, no in-memory {@code ScheduledExecutorService}.
 */
public class CompliantSchedulerDrivenJob implements JobHandler {

    @Override
    public String type() {
        return "DAILY_REPORT";
    }

    @Override
    public void run(String jobData) {
        // the body; db-scheduler started it
    }
}