package io.integrationplatform.app.security.gate;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T141 / T-147 Release 1 security gate, suite S4 (Identity), from release-1-security-test-plan.md.
 * Every case is disabled until the tracker item it names is on main; the area roll-up is
 * {@link Release1Gate} and the case list is {@link Release1GateCatalogue}. Container tag: never runs by default.
 */
@Tag("container")
class SecurityGateS4IdentityContainerTest {

    /**
     * S4-A1: The exactly-6 operations succeed from the agent credential.
     * Expected: Each of the 6 succeeds; responses contain job/run/alert rows only - **no payload, no configuration, no tenant id** (ADR-012 §5: job_run holds no payload, no tenant id, no per-tenant count)
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T127 operator-agent job API is on main (f6d1460c, 155bd64a) but the case needs Keycloak + the agent token via the platform-e2e harness; move it there (T141 gate case S4-A1)")
    void s4_a1_the_exactly_6_operations_succeed_from() {
        fail("S4-A1 body lands with T127");
    }

    /**
     * S4-A2: Scope-limiting sweep (the "every other operation" rule).
     * Expected: Every call 403 + audit-stream entry; nothing resolves, nothing half-written; the sweep output is a per-family table
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T142 harness is on main (platform-e2e E2eContainers/Release1Client) but unreachable from platform-app (separate module); move the case to platform-e2e, needs Keycloak + tenant B (T141 gate case S4-A2)")
    void s4_a2_scope_limiting_sweep() {
        fail("S4-A2 body lands with T142");
    }

    /**
     * S4-A3: Replay beyond the 5-minute expiry (ADR-007 D4/D3).
     * Expected: All 401 (final); no job state change, no double acknowledge; a second ack of the same alert id changes nothing (ack idempotent per job_alert uniqueness)
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T127 operator-agent job API is on main (f6d1460c, 155bd64a) but the case needs Keycloak + the agent token via the platform-e2e harness; move it there (T141 gate case S4-A3)")
    void s4_a3_replay_beyond_the_5_minute_expiry() {
        fail("S4-A3 body lands with T127");
    }

    /**
     * S4-A4: Confused deputy - tenant scoping attempts on the agent (ADR-012 §5 "never reads tenant data by construction").
     * Expected: No tenant may be bound: the bootstrap resolves nothing, every attempt 403; **nothing resolves to A - not even read-only**; where the endpoint family is source-only, the agent token is refused there too
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T142 harness is on main (platform-e2e E2eContainers/Release1Client) but unreachable from platform-app (separate module); move the case to platform-e2e, needs Keycloak + tenant B (T141 gate case S4-A4)")
    void s4_a4_confused_deputy_tenant_scoping_attempts_on() {
        fail("S4-A4 body lands with T142");
    }

    /**
     * S4-A5: Cross-kind token confusion (ADR-007 D5 register).
     * Expected: All (i)-(v) 403 + audit; the register is closed: ROLE, ANY_HUMAN, SOURCE_SYSTEM, OPERATOR_AGENT only, and OPERATOR_AGENT bindings are per-client
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T127 operator-agent job API is on main (f6d1460c, 155bd64a) but the case needs Keycloak + the agent token via the platform-e2e harness; move it there (T141 gate case S4-A5)")
    void s4_a5_cross_kind_token_confusion() {
        fail("S4-A5 body lands with T127");
    }

    /**
     * S4-A6: Direct API access without the console (the agent has no console session).
     * Expected: Agent works direct on the API (expected); **gains no console route, no BFF session endpoint, no state change beyond its 6 ops**; console routes 401/403
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T142 harness is on main (platform-e2e E2eContainers/Release1Client) but unreachable from platform-app (separate module); move the case to platform-e2e, needs Keycloak + tenant B (T141 gate case S4-A6)")
    void s4_a6_direct_api_access_without_the_console() {
        fail("S4-A6 body lands with T142");
    }

    /**
     * S4-A7: Tenant-table read errors leak nothing (ADR-012 §5; ADR-007 D5 error path).
     * Expected: No tenant data, tenant id, tenant name or payload fragment in any error body or log; errors are opaque (403/404, named error classes); nothing writes a tenant-scoped row for the agent (fails RLS by design; audit goes to the platform stream)
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T142 harness is on main (platform-e2e E2eContainers/Release1Client) but unreachable from platform-app (separate module); move the case to platform-e2e, needs Keycloak + tenant B (T141 gate case S4-A7)")
    void s4_a7_tenant_table_read_errors_leak_nothing() {
        fail("S4-A7 body lands with T142");
    }

    /**
     * S4-A8: Keycloak-side configuration of the agent client.
     * Expected: Agent client holds no role and no mapper that emits a tenant claim (one realm, tenants not in Keycloak - ADR-007 D1); a forged tenant claim on an agent token is only **cross-checked** - mismatch denies and audits (ADR-007 D2), never binds
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T162 is on main (ffede093); body not yet written - Keycloak realm check on the operator-agent client, reuse platform-tenancy KeycloakRealmContainerTest (T141 gate case S4-A8)")
    void s4_a8_keycloak_side_configuration_of_the_agent() {
        fail("S4-A8 body lands with T162");
    }
}
