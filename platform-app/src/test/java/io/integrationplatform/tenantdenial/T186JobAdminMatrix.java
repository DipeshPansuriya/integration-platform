package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import java.util.List;

/**
 * T186 (stale-test fix, arjun-fullstack): the forged-tenant matrix part for the operator agent's job admin API
 * ({@code JobAdminController}, JobEndpointRoles: every route is an OPERATOR_AGENT operation). Jobs are platform-wide
 * scheduling definitions (ADR-012: summaries carry identity and scheduling state, no payload, no tenant id), so there
 * is no tenant row to leak; the matrix proves the other half of the claim - that no human session and no source
 * credential of either tenant reaches a job route, with or without a tenant forgery (403 by caller kind, nothing read
 * or written). The suite has no operator-agent principal, so the agent's own legitimate call is covered by the
 * scheduler's tests, not here. No rows are seeded.
 */
final class T186JobAdminMatrix implements LaneMatrix {

    private static final String JOBS = "/api/v1/admin/jobs";

    @Override
    public List<Entry> entries() {
        return List.of(
                new Entry("GET", JOBS, false, s -> JOBS, null, null, s -> List.of()),
                new Entry("GET", JOBS + "/summaries", false, s -> JOBS + "/summaries", null, null, s -> List.of()),
                new Entry("GET", JOBS + "/{jobType}", false,
                        s -> JOBS + "/t186-job-a", s -> JOBS + "/t186-job-b", null, s -> List.of()),
                new Entry("POST", JOBS + "/{jobType}/run", false,
                        s -> JOBS + "/t186-job-a/run", s -> JOBS + "/t186-job-b/run", null, s -> List.of(), 202),
                new Entry("POST", JOBS + "/{jobType}/pause", false,
                        s -> JOBS + "/t186-job-a/pause", s -> JOBS + "/t186-job-b/pause", null, s -> List.of(), 204),
                new Entry("POST", JOBS + "/{jobType}/resume", false,
                        s -> JOBS + "/t186-job-a/resume", s -> JOBS + "/t186-job-b/resume", null, s -> List.of(),
                        204));
    }
}
