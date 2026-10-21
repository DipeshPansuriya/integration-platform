package io.integrationplatform.config.scheduler.adapter.in;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import java.util.List;
import org.springframework.stereotype.Component;

/** E14 T-060 (ADR-012 section 5): every agent admin route is an OPERATOR_AGENT operation - no human role, no source. */
@Component
public class JobEndpointRoles implements ApiOperationRules {

    private static final String JOBS = JobAdminController.PATH;

    @Override
    public List<Rule> rules() {
        return List.of(
                new Rule("GET", JOBS, Operation.LIST_JOBS),
                new Rule("GET", JOBS + "/summaries", Operation.LIST_JOBS),
                new Rule("GET", JOBS + "/{jobType}", Operation.INSPECT_JOB),
                new Rule("POST", JOBS + "/{jobType}/run", Operation.RUN_JOB_NOW),
                new Rule("POST", JOBS + "/{jobType}/pause", Operation.PAUSE_JOB),
                new Rule("POST", JOBS + "/{jobType}/resume", Operation.RESUME_JOB));
    }
}
