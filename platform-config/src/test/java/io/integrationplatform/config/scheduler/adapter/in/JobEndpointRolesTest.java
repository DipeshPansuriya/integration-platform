package io.integrationplatform.config.scheduler.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.Role;
import io.integrationplatform.tenancy.RolePolicy;
import io.integrationplatform.tenancy.RolePolicy.Caller;
import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules.Rule;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * E14 T-060 (ADR-012 section 5): every agent admin route is an OPERATOR_AGENT operation, so the role interceptor
 * refuses a source credential, a read-only user and a tenant admin with 403 before the handler runs.
 */
class JobEndpointRolesTest {

    static final String JOBS = "/api/v1/admin/jobs";

    @Test
    void everyRouteMapsToItsOperatorAgentOperation() {
        assertThat(new JobEndpointRoles().rules()).containsExactlyInAnyOrder(
                new Rule("GET", JOBS, Operation.LIST_JOBS),
                new Rule("GET", JOBS + "/{jobType}", Operation.INSPECT_JOB),
                new Rule("POST", JOBS + "/{jobType}/run", Operation.RUN_JOB_NOW),
                new Rule("POST", JOBS + "/{jobType}/pause", Operation.PAUSE_JOB),
                new Rule("POST", JOBS + "/{jobType}/resume", Operation.RESUME_JOB));
        assertThat(JobAdminController.PATH).isEqualTo(JOBS);
    }

    @Test
    void noHumanRoleAndNoSourceCredentialMayCallAnyRoute() {
        for (Rule rule : new JobEndpointRoles().rules()) {
            assertThat(rule.operation().caller()).as(rule.pattern()).isEqualTo(Caller.OPERATOR_AGENT);
            assertThat(RolePolicy.allows(rule.operation(), EnumSet.allOf(Role.class)))
                    .as("READ_ONLY, TENANT_ADMIN and every other human role are refused: " + rule.pattern())
                    .isFalse();
        }
    }
}
