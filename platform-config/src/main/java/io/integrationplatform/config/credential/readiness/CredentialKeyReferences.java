package io.integrationplatform.config.credential.readiness;

import java.util.Set;

/**
 * The start-up half of ADR-007 Decision 7 rule 5: every key-encryption-key id a target credential row references, across
 * tenants (ids only - never a tenant, a row or a value). Reading it needs a cross-tenant read: a new definer function
 * under architecture.md section 5 control T5 (today exactly four) and a reviewed {@code @TenantBoundaryCrossing} adapter
 * in the tenancy module - an architecture decision. Until it is made no production bean exists and the readiness check
 * relies on the send-time reports alone (T44 hand-back, Needs decision).
 */
@FunctionalInterface
public interface CredentialKeyReferences {

    Set<String> referencedKeyIds();
}
