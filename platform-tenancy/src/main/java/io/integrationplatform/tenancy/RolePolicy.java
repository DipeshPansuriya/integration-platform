package io.integrationplatform.tenancy;

import static io.integrationplatform.tenancy.Role.APPROVER;
import static io.integrationplatform.tenancy.Role.CONFIGURATOR;
import static io.integrationplatform.tenancy.Role.READ_ONLY;
import static io.integrationplatform.tenancy.Role.SUPPORT;
import static io.integrationplatform.tenancy.Role.TENANT_ADMIN;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The whole operation -> allowed-roles matrix, enforced server-side on every {@code /api} endpoint (E4-F1-S3, T-099;
 * ADR-007 Decision 5). Derived from PRD section 3; where the PRD is silent the narrowest set is chosen (least
 * privilege) - those rows are marked and listed for review:
 *
 * <pre>
 * Operation                 Caller         Allowed roles
 * READ_OWN_IDENTITY         any human      (no role needed: /api/v1/me)
 * READ_CONFIGURATION        role           TENANT_ADMIN, CONFIGURATOR, APPROVER, READ_ONLY   (approver: to review; support: silent -> no)
 * EDIT_CONFIGURATION        role           TENANT_ADMIN, CONFIGURATOR
 * SUBMIT_FOR_APPROVAL       role           TENANT_ADMIN, CONFIGURATOR
 * APPROVE_VERSION           role           TENANT_ADMIN, APPROVER     (the author may approve: single-user workflow)
 * PUBLISH_VERSION           role           TENANT_ADMIN, APPROVER     (silent -> least privilege)
 * MANAGE_USERS_AND_ROLES    role           TENANT_ADMIN
 * ISSUE_SOURCE_CREDENTIAL   role           TENANT_ADMIN               (E5 T-103 binds its endpoint)
 * REVOKE_SOURCE_CREDENTIAL  role           TENANT_ADMIN               (E5 binds its endpoint)
 * MANAGE_TARGET_CREDENTIAL  role           TENANT_ADMIN               (E5 T-103: save, rotate, remove a target secret)
 * APPROVE_DB_TABLE          role           TENANT_ADMIN, APPROVER     (E16 F3: approve or revoke a DB target's table; the requester may approve their own)
 * READ_MESSAGE_TIMELINE     role           TENANT_ADMIN, SUPPORT, READ_ONLY   (E13 binds its endpoint)
 * REPLAY_DEAD_LETTER        role           TENANT_ADMIN, SUPPORT      (replay or discard; E13)
 * SUBMIT_MESSAGE            source system  (no human role: the common API, E5 binds it)
 * LIST_JOBS                 operator agent (no human, no tenant: the agent admin API, E14 T-060 - ADR-012 Decision 5)
 * INSPECT_JOB               operator agent (job and run rows only - no payload, no configuration: ADR-012 section 5)
 * RUN_JOB_NOW               operator agent (E14 T-060)
 * PAUSE_JOB                 operator agent (paused with who/when on the job_definition row; E14 T-060)
 * RESUME_JOB                operator agent (E14 T-060)
 * READ_JOB_ALERTS           operator agent (open job_alert rows; the agent polls every 5 min - ADR-012 section 6)
 * ACKNOWLEDGE_JOB_ALERT     operator agent (E14 T-062)
 * </pre>
 *
 * A machine (source-bound or operator-agent) principal holds no human role, so every role-gated operation refuses it;
 * the operator agent is also refused by every operation above - the platform's first no-tenant principal reaches only
 * the job-admin and alert operations, and tenant admins hold no job operation in Release 1 (ADR-012 Decision 5, as
 * answered by Dipesh 2026-10-12). The agent's attack suite is T-147 (E15).
 */
public final class RolePolicy {

    /** Who may call an operation at all. */
    public enum Caller {
        /** A bound human holding one of the operation's roles. */
        ROLE,
        /** Any bound human, whatever their roles. */
        ANY_HUMAN,
        /** A source system's machine credential only; no human. */
        SOURCE_SYSTEM,
        /** The platform's no-tenant operator-agent credential (E14, ADR-012 section 5); no human, no role, no tenant. */
        OPERATOR_AGENT
    }

    /** Every operation the API offers, with who may call it. */
    public enum Operation {
        READ_OWN_IDENTITY(Caller.ANY_HUMAN),
        READ_CONFIGURATION(Caller.ROLE, TENANT_ADMIN, CONFIGURATOR, APPROVER, READ_ONLY),
        EDIT_CONFIGURATION(Caller.ROLE, TENANT_ADMIN, CONFIGURATOR),
        SUBMIT_FOR_APPROVAL(Caller.ROLE, TENANT_ADMIN, CONFIGURATOR),
        APPROVE_VERSION(Caller.ROLE, TENANT_ADMIN, APPROVER),
        PUBLISH_VERSION(Caller.ROLE, TENANT_ADMIN, APPROVER),
        MANAGE_USERS_AND_ROLES(Caller.ROLE, TENANT_ADMIN),
        ISSUE_SOURCE_CREDENTIAL(Caller.ROLE, TENANT_ADMIN),
        REVOKE_SOURCE_CREDENTIAL(Caller.ROLE, TENANT_ADMIN),
        // E5 seam (arjun-fullstack, F13): a target secret is a tenant-admin action like a source one
        // (E5-F1-S3 "Only a tenant admin manages credentials"); rotating a SOURCE one is issuing a second one
        // (ADR-007 Decision 3), so that stays ISSUE_SOURCE_CREDENTIAL.
        MANAGE_TARGET_CREDENTIAL(Caller.ROLE, TENANT_ADMIN),
        // E16 F3 (Dipesh D1, 2026-10-22): the tenant's approver approves or revokes a DB target's table; since V2_4_1420
        // (single-user workflow) the requester may approve their own request, the definer function checks only tenant
        // and state; requesting one is EDIT_CONFIGURATION
        APPROVE_DB_TABLE(Caller.ROLE, TENANT_ADMIN, APPROVER),
        READ_MESSAGE_TIMELINE(Caller.ROLE, TENANT_ADMIN, SUPPORT, READ_ONLY),
        REPLAY_DEAD_LETTER(Caller.ROLE, TENANT_ADMIN, SUPPORT),
        SUBMIT_MESSAGE(Caller.SOURCE_SYSTEM),
        // E14 seam (arjun-fullstack, F8; ADR-012 Decision 5): five job-admin operations + the alert read/acknowledge.
        // Closed pair of caller-kinds: OPERATOR_AGENT only - a source credential, a read-only user or a tenant admin
        // all get 403; the agent on every tenant operation above is refused by kind (the interceptor's switch).
        LIST_JOBS(Caller.OPERATOR_AGENT),
        INSPECT_JOB(Caller.OPERATOR_AGENT),
        RUN_JOB_NOW(Caller.OPERATOR_AGENT),
        PAUSE_JOB(Caller.OPERATOR_AGENT),
        RESUME_JOB(Caller.OPERATOR_AGENT),
        READ_JOB_ALERTS(Caller.OPERATOR_AGENT),
        ACKNOWLEDGE_JOB_ALERT(Caller.OPERATOR_AGENT);

        private final Caller caller;
        private final Set<Role> roles;

        Operation(Caller caller, Role... roles) {
            this.caller = caller;
            EnumSet<Role> allowed = EnumSet.noneOf(Role.class);
            Collections.addAll(allowed, roles);
            this.roles = Collections.unmodifiableSet(allowed);
        }

        public Caller caller() {
            return caller;
        }
    }

    private RolePolicy() {
    }

    /** The roles that may perform a role-gated operation; empty for the other caller kinds. */
    public static Set<Role> allowedRoles(Operation operation) {
        return operation.roles;
    }

    /** Whether a bound HUMAN holding {@code held} may perform the operation. */
    public static boolean allows(Operation operation, Set<Role> held) {
        return switch (operation.caller) {
            case ANY_HUMAN -> true;
            // no human role reaches an operator-agent operation; the agent itself is checked by
            // authentication kind (OperatorAgentAuthentication), never by role
            case SOURCE_SYSTEM, OPERATOR_AGENT -> false;
            case ROLE -> !Collections.disjoint(operation.roles, held);
        };
    }
}
