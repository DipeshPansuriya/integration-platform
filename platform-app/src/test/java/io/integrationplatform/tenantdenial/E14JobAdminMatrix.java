package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.WriteProbe;
import java.util.List;

/**
 * Forged-tenant matrix part for the operator agent's job admin API ({@code JobAdminController}, E14 T-060, ADR-012;
 * every route is an OPERATOR_AGENT operation in {@code JobEndpointRoles}).
 *
 * <p>The jobs are platform-scoped: {@code scheduler.job_definition} has no tenant column and no row-level security
 * (V2_2_1400), so there is no tenant row to leak. What the suite proves here is the other half of tenant isolation: a
 * tenant user (TENANT_ADMIN, so a denial is never a missing role) and a source credential of either tenant are refused
 * by caller kind (403, no data, no tenant read), a token whose tenant claim names the other tenant is refused, an
 * unauthenticated call is 401, and a refused pause leaves the platform job exactly as seeded (the write probe). The
 * routes with a {@code {jobType}} have no tenant record to name, so the PATH_ID forgery reuses the same job type.
 * The statuses are those of the operator agent's legitimate call (never reached by these callers).
 */
final class E14JobAdminMatrix implements LaneMatrix {

    private static final String JOBS = "/api/v1/admin/jobs";
    private static final String READ_JOB = "DELIVERY_RETRY";
    private static final String PAUSE_JOB = "PAYLOAD_PURGE";
    private static final String RESUME_JOB = "DAILY_REPORT";

    @Override
    public List<Entry> entries() {
        return List.of(
                new Entry("GET", JOBS, false, s -> JOBS, null, null, s -> List.of()),
                new Entry("GET", JOBS + "/summaries", false, s -> JOBS + "/summaries", null, null, s -> List.of()),
                new Entry("GET", JOBS + "/{jobType}", false, s -> JOBS + "/" + READ_JOB, s -> JOBS + "/" + READ_JOB,
                        null, s -> List.of()),
                new Entry("POST", JOBS + "/{jobType}/run", false, s -> JOBS + "/" + READ_JOB + "/run",
                        s -> JOBS + "/" + READ_JOB + "/run", null, s -> List.of(), 202),
                new Entry("POST", JOBS + "/{jobType}/pause", false, s -> JOBS + "/" + PAUSE_JOB + "/pause",
                        s -> JOBS + "/" + PAUSE_JOB + "/pause", null, s -> List.of(), 204,
                        new WriteProbe(s -> pausedOf(PAUSE_JOB), "false", "true")),
                new Entry("POST", JOBS + "/{jobType}/resume", false, s -> JOBS + "/" + RESUME_JOB + "/resume",
                        s -> JOBS + "/" + RESUME_JOB + "/resume", null, s -> List.of(), 204,
                        new WriteProbe(s -> pausedOf(RESUME_JOB), "false", "false")));
    }

    private static String pausedOf(String jobType) {
        return "SELECT paused::text FROM scheduler.job_definition WHERE job_type = '" + jobType + "'";
    }
}
