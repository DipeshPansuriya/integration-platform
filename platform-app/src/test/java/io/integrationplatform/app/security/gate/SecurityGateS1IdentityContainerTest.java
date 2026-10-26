package io.integrationplatform.app.security.gate;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T141 / T-147 Release 1 security gate, suite S1 (Identity), from release-1-security-test-plan.md.
 * Every case is disabled until the tracker item it names is on main; the area roll-up is
 * {@link Release1Gate} and the case list is {@link Release1GateCatalogue}. Container tag: never runs by default.
 */
@Tag("container")
class SecurityGateS1IdentityContainerTest {

    /**
     * S1-A7: Role matrix on every admin operation (E4-F1-S3).
     * Expected: Allowed exactly per the backlog matrix; every out-of-role call 403; approver = author refused (DB CHECK); 401/403 never retried by any layer
     */
    @Test
    @Disabled("OPEN (T201): only the machine leg runs (SecurityGateHttpIdentityContainerTest.s1_a7_s2_a2_machine_leg...); the human role matrix over EVERY admin operation has no body (RoleMatrixContainerTest samples a few operations) (T141 gate case S1-A7)")
    void s1_a7_role_matrix_on_every_admin_operation() {
        fail("S1-A7 body lands with T157");
    }

}
