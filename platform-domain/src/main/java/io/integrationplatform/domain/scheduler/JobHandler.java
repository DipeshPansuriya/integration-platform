package io.integrationplatform.domain.scheduler;

/**
 * E14 seam (arjun-fullstack, F8; ADR-012 Accepted, Dipesh 2026-10-12): one job type's body, plain Java - no Spring
 * type, so no module depends on {@code platform-app} (ADR-012 section 2). ADR-012 Decision 2 fixes the closed register
 * of job types: <b>DELIVERY_RETRY</b> (with the reconciler sweep inside it), <b>PAYLOAD_PURGE</b>, <b>DAILY_REPORT</b>,
 * <b>CREDENTIAL_CLEANUP</b> - adding a type is code plus an amendment of that register. A handler's tenant never comes
 * from job data: scanning handlers loop {@code ipaas_active_tenants()} one transaction per tenant, row work resolves
 * its tenant with {@code ipaas_work_tenant} (ADR-012 section 4); the {@code jobData} string a handler receives never
 * carries a tenant id (the forged-tenant proof is T-144). Schedules live only in the {@code job_definition} row
 * (fixed delay or cron, ADR-012 Decision 2); the runtime wrapper, which T-056 owns, reads the row, claims and drives
 * the handler, and writes {@code job_run}.
 *
 * <p>Placement by module ownership (ADR-012 section 2): the retry and purge bodies in {@code platform-delivery}, the
 * daily report in {@code platform-ledger}, the credential clean-up in {@code platform-config}; the db-scheduler wiring
 * and the run/alert wrapper live in {@code platform-app}, the rows and the admin API in {@code platform-config}.
 * Changes go through arjun-fullstack.
 */
public interface JobHandler {

    /** The handler's type from ADR-012's closed register (also the db-scheduler task name). */
    String type();

    /**
     * The job's body. {@code jobData} carries only type-specific parameters - never a tenant id
     * (ADR-012 section 4). A thrown exception fails the run ({@code job_run} FAILED and one FAILED
     * alert, ADR-012 section 6).
     */
    void run(String jobData);
}