package io.integrationplatform.tenancy.port;

/**
 * Records a denied tenant attempt (E1-F1-S1). E1 seam: implemented by lane B (T-001); the forged-tenant denial suite of
 * lane D (T-002) observes it. The audit writer of a later phase may implement it again; callers never depend on how it
 * is stored.
 */
public interface TenantDenialRecorder {

    void record(DeniedTenantAttempt attempt);
}
