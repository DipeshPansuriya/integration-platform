package io.integrationplatform.tenancy;

import static io.integrationplatform.tenancy.RolePolicy.Operation.ACKNOWLEDGE_JOB_ALERT;
import static io.integrationplatform.tenancy.RolePolicy.Operation.APPROVE_VERSION;
import static io.integrationplatform.tenancy.RolePolicy.Operation.EDIT_CONFIGURATION;
import static io.integrationplatform.tenancy.RolePolicy.Operation.INSPECT_JOB;
import static io.integrationplatform.tenancy.RolePolicy.Operation.ISSUE_SOURCE_CREDENTIAL;
import static io.integrationplatform.tenancy.RolePolicy.Operation.LIST_JOBS;
import static io.integrationplatform.tenancy.RolePolicy.Operation.MANAGE_TARGET_CREDENTIAL;
import static io.integrationplatform.tenancy.RolePolicy.Operation.MANAGE_USERS_AND_ROLES;
import static io.integrationplatform.tenancy.RolePolicy.Operation.PAUSE_JOB;
import static io.integrationplatform.tenancy.RolePolicy.Operation.PUBLISH_VERSION;
import static io.integrationplatform.tenancy.RolePolicy.Operation.READ_CONFIGURATION;
import static io.integrationplatform.tenancy.RolePolicy.Operation.READ_JOB_ALERTS;
import static io.integrationplatform.tenancy.RolePolicy.Operation.READ_MESSAGE_TIMELINE;
import static io.integrationplatform.tenancy.RolePolicy.Operation.READ_OWN_IDENTITY;
import static io.integrationplatform.tenancy.RolePolicy.Operation.REPLAY_DEAD_LETTER;
import static io.integrationplatform.tenancy.RolePolicy.Operation.RESUME_JOB;
import static io.integrationplatform.tenancy.RolePolicy.Operation.REVOKE_SOURCE_CREDENTIAL;
import static io.integrationplatform.tenancy.RolePolicy.Operation.RUN_JOB_NOW;
import static io.integrationplatform.tenancy.RolePolicy.Operation.SUBMIT_FOR_APPROVAL;
import static io.integrationplatform.tenancy.RolePolicy.Operation.SUBMIT_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * E4-F1-S3 (T-099, ADR-007 Decision 5, PRD section 3) at the policy level: the whole operation -> allowed-roles matrix.
 * One failing test per role was written first (R01). The HTTP half - the operations whose endpoints exist today - runs
 * in platform-app's {@code RoleMatrixContainerTest}; issuing a credential (E5 T-103), reading a message timeline and
 * replaying a dead letter (E13) are proven here until their endpoints bind these operations.
 */
class RolePolicyTest {

    // Scenario Outline: The role matrix - one row per role

    @ParameterizedTest(name = "{0} may {1}")
    @CsvSource({
        "TENANT_ADMIN, ISSUE_SOURCE_CREDENTIAL",
        "CONFIGURATOR, EDIT_CONFIGURATION",
        "APPROVER,     APPROVE_VERSION",
        "SUPPORT,      READ_MESSAGE_TIMELINE",
        "READ_ONLY,    READ_CONFIGURATION"
    })
    @DisplayName("Scenario Outline: The role matrix - allowed")
    void eachRoleMayDoItsJob(Role role, Operation operation) {
        assertThat(RolePolicy.allows(operation, Set.of(role))).isTrue();
    }

    // @negative @security Scenario Outline: Each role is refused outside its job

    @ParameterizedTest(name = "{0} may not {1}")
    @CsvSource({
        "CONFIGURATOR, ISSUE_SOURCE_CREDENTIAL",
        "CONFIGURATOR, MANAGE_TARGET_CREDENTIAL",
        // E16 F3 (D1): a configurator requests a DB table but never approves one; read-only and support cannot either
        "CONFIGURATOR, APPROVE_DB_TABLE",
        "READ_ONLY,    APPROVE_DB_TABLE",
        "SUPPORT,      APPROVE_DB_TABLE",
        "SUPPORT,      EDIT_CONFIGURATION",
        "READ_ONLY,    REPLAY_DEAD_LETTER",
        // single-user workflow: no author-is-not-approver rule any more; the role alone decides who approves
        "APPROVER,     EDIT_CONFIGURATION",
        "TENANT_ADMIN, SUBMIT_MESSAGE"
    })
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario Outline: Each role is refused outside its job")
    void eachRoleIsRefusedOutsideItsJob(Role role, Operation operation) {
        assertThat(RolePolicy.allows(operation, Set.of(role))).isFalse();
    }

    @Test
    @DisplayName("The derived matrix (PRD section 3; least privilege where it is silent) - listed for review")
    void theWholeMatrix() {
        Map<Operation, Set<Role>> expected = Map.ofEntries(
                Map.entry(READ_CONFIGURATION, EnumSet.of(Role.TENANT_ADMIN, Role.CONFIGURATOR, Role.APPROVER,
                        Role.READ_ONLY)),
                Map.entry(EDIT_CONFIGURATION, EnumSet.of(Role.TENANT_ADMIN, Role.CONFIGURATOR)),
                Map.entry(SUBMIT_FOR_APPROVAL, EnumSet.of(Role.TENANT_ADMIN, Role.CONFIGURATOR)),
                Map.entry(APPROVE_VERSION, EnumSet.of(Role.TENANT_ADMIN, Role.APPROVER)),
                Map.entry(PUBLISH_VERSION, EnumSet.of(Role.TENANT_ADMIN, Role.APPROVER)),
                Map.entry(MANAGE_USERS_AND_ROLES, EnumSet.of(Role.TENANT_ADMIN)),
                Map.entry(ISSUE_SOURCE_CREDENTIAL, EnumSet.of(Role.TENANT_ADMIN)),
                Map.entry(REVOKE_SOURCE_CREDENTIAL, EnumSet.of(Role.TENANT_ADMIN)),
                Map.entry(MANAGE_TARGET_CREDENTIAL, EnumSet.of(Role.TENANT_ADMIN)),
                Map.entry(Operation.APPROVE_DB_TABLE, EnumSet.of(Role.TENANT_ADMIN, Role.APPROVER)),
                Map.entry(READ_MESSAGE_TIMELINE, EnumSet.of(Role.TENANT_ADMIN, Role.SUPPORT, Role.READ_ONLY)),
                Map.entry(REPLAY_DEAD_LETTER, EnumSet.of(Role.TENANT_ADMIN, Role.SUPPORT)),
                Map.entry(READ_OWN_IDENTITY, EnumSet.noneOf(Role.class)),
                Map.entry(SUBMIT_MESSAGE, EnumSet.noneOf(Role.class)),
                // E14 (ADR-012 Decision 5): the agent operations are caller-kind gated, never role gated
                Map.entry(LIST_JOBS, EnumSet.noneOf(Role.class)),
                Map.entry(INSPECT_JOB, EnumSet.noneOf(Role.class)),
                Map.entry(RUN_JOB_NOW, EnumSet.noneOf(Role.class)),
                Map.entry(PAUSE_JOB, EnumSet.noneOf(Role.class)),
                Map.entry(RESUME_JOB, EnumSet.noneOf(Role.class)),
                Map.entry(READ_JOB_ALERTS, EnumSet.noneOf(Role.class)),
                Map.entry(ACKNOWLEDGE_JOB_ALERT, EnumSet.noneOf(Role.class)),
                // row 578 (CWE-862): the one GLOBAL SSRF allow-list is operator-owned; no tenant role edits or reads it
                Map.entry(Operation.READ_ALLOWED_HOSTS, EnumSet.noneOf(Role.class)),
                Map.entry(Operation.EDIT_ALLOWED_HOSTS, EnumSet.noneOf(Role.class)),
                // the tenant-scoped Destinations screen (TenantAllowedHostController): tenant admin only
                Map.entry(Operation.READ_DESTINATIONS, EnumSet.of(Role.TENANT_ADMIN)),
                Map.entry(Operation.EDIT_DESTINATIONS, EnumSet.of(Role.TENANT_ADMIN)));

        assertThat(EnumSet.allOf(Operation.class)).as("every operation has a row").isEqualTo(expected.keySet());
        expected.forEach((operation, roles) -> assertThat(RolePolicy.allowedRoles(operation))
                .as("roles allowed to %s", operation).isEqualTo(roles));
        assertThat(Role.values()).extracting(Role::name).containsExactly(
                "TENANT_ADMIN", "CONFIGURATOR", "APPROVER", "SUPPORT", "READ_ONLY");
    }

    @Test
    @DisplayName("A tenant admin may do everything a human role may do (PRD: everything in the tenant)")
    void aTenantAdminMayDoEveryHumanOperation() {
        for (Operation operation : Operation.values()) {
            if (operation.caller() == RolePolicy.Caller.ROLE) {
                assertThat(RolePolicy.allows(operation, Set.of(Role.TENANT_ADMIN))).as("%s", operation).isTrue();
            }
        }
    }

    @Test
    @Tag("negative")
    @DisplayName("No role, no role-gated operation; /me needs only a bound human; the common API only a source system")
    void callerKinds() {
        for (Operation operation : Operation.values()) {
            if (operation.caller() == RolePolicy.Caller.ROLE) {
                assertThat(RolePolicy.allows(operation, Set.of())).as("no role: %s", operation).isFalse();
            }
        }
        assertThat(READ_OWN_IDENTITY.caller()).isEqualTo(RolePolicy.Caller.ANY_HUMAN);
        assertThat(RolePolicy.allows(READ_OWN_IDENTITY, Set.of())).isTrue();
        assertThat(SUBMIT_MESSAGE.caller()).isEqualTo(RolePolicy.Caller.SOURCE_SYSTEM);
        assertThat(RolePolicy.allows(SUBMIT_MESSAGE, EnumSet.allOf(Role.class)))
                .as("no human role reaches the source-system API").isFalse();
    }

    @Test
    @DisplayName("E14 (ADR-012 Decision 5): only the operator agent reaches the job-admin and alert operations")
    void operatorAgentOperations() {
        Set<Operation> agentOps = Set.of(LIST_JOBS, INSPECT_JOB, RUN_JOB_NOW, PAUSE_JOB, RESUME_JOB,
                READ_JOB_ALERTS, ACKNOWLEDGE_JOB_ALERT, Operation.READ_ALLOWED_HOSTS, Operation.EDIT_ALLOWED_HOSTS);
        agentOps.forEach(op -> assertThat(op.caller()).isEqualTo(RolePolicy.Caller.OPERATOR_AGENT));
        // no human role reaches them, not even tenant admin (Release 1 - ADR-012 Decision 5, Dipesh 2026-10-12)
        agentOps.forEach(op -> assertThat(RolePolicy.allows(op, EnumSet.allOf(Role.class)))
                .as("no human role: %s", op).isFalse());
        // every existing operation refuses the agent kind by default: nothing outside the nine carries
        // Caller.OPERATOR_AGENT, and the interceptor's kind switch turns the agent away from those
        for (Operation operation : Operation.values()) {
            if (!agentOps.contains(operation)) {
                assertThat(operation.caller()).as("refused for the agent: %s", operation)
                        .isNotEqualTo(RolePolicy.Caller.OPERATOR_AGENT);
            }
        }
    }
}
