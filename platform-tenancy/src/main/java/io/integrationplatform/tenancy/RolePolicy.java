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
 * APPROVE_VERSION           role           TENANT_ADMIN, APPROVER     (never the author: the record's CHECK)
 * PUBLISH_VERSION           role           TENANT_ADMIN, APPROVER     (silent -> least privilege)
 * MANAGE_USERS_AND_ROLES    role           TENANT_ADMIN
 * ISSUE_SOURCE_CREDENTIAL   role           TENANT_ADMIN               (E5 T-103 binds its endpoint)
 * REVOKE_SOURCE_CREDENTIAL  role           TENANT_ADMIN               (E5 binds its endpoint)
 * MANAGE_TARGET_CREDENTIAL  role           TENANT_ADMIN               (E5 T-103: save, rotate, remove a target secret)
 * READ_MESSAGE_TIMELINE     role           TENANT_ADMIN, SUPPORT, READ_ONLY   (E13 binds its endpoint)
 * REPLAY_DEAD_LETTER        role           TENANT_ADMIN, SUPPORT      (replay or discard; E13)
 * SUBMIT_MESSAGE            source system  (no human role: the common API, E5 binds it)
 * </pre>
 *
 * A machine (source-bound) principal holds no human role, so every role-gated operation refuses it.
 */
public final class RolePolicy {

    /** Who may call an operation at all. */
    public enum Caller {
        /** A bound human holding one of the operation's roles. */
        ROLE,
        /** Any bound human, whatever their roles. */
        ANY_HUMAN,
        /** A source system's machine credential only; no human. */
        SOURCE_SYSTEM
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
        READ_MESSAGE_TIMELINE(Caller.ROLE, TENANT_ADMIN, SUPPORT, READ_ONLY),
        REPLAY_DEAD_LETTER(Caller.ROLE, TENANT_ADMIN, SUPPORT),
        SUBMIT_MESSAGE(Caller.SOURCE_SYSTEM);

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
            case SOURCE_SYSTEM -> false;
            case ROLE -> !Collections.disjoint(operation.roles, held);
        };
    }
}
