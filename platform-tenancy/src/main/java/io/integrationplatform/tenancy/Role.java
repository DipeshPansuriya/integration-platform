package io.integrationplatform.tenancy;

/**
 * The human roles of a tenant (ADR-007 Decision 5, PRD section 3): rows of the tenant-scoped, row-level-secured
 * {@code public.role_grant} table (V2_2_6) - never identity-provider roles, so a misconfigured mapper can never grant
 * one. The names are the table's closed {@code CHECK} set (R10). {@code APPROVER} is the team addition of PRD section 9
 * (team default: added; tenant admins may also approve).
 */
public enum Role {
    /** Everything in the tenant, including users, roles and credentials; may approve. */
    TENANT_ADMIN,
    /** Creates and edits systems, contracts, flows, rules and mappings as drafts; submits them for approval. */
    CONFIGURATOR,
    /** Approves or rejects submitted versions - never their own (the record's CHECK, not the role). */
    APPROVER,
    /** Reads messages, deliveries and timelines; replays or discards dead letters (audited). */
    SUPPORT,
    /** Reads configuration and tracking. */
    READ_ONLY
}
