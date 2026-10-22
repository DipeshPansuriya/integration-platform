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
     * S1-A1: Console login happy path (humans).
     * Expected: Console shows only demo-tenant configuration; browser stores no access token (verified in devtools: cookies + storage)
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T139 browser harness is on main (871889bd) as a Playwright suite; a Java ContainerTest cannot drive a browser - rewrite as a Playwright security spec or retire this stub (T141 gate case S1-A1)")
    void s1_a1_console_login_happy_path() {
        fail("S1-A1 body lands with T139");
    }

    /**
     * S1-A2: PKCE/state enforcement (pack: offensive-oauth, "PKCE everywhere", "state unguessable, tied to session").
     * Expected: Keycloak/backend refuses: missing state rejected, PKCE mismatch invalid_grant, code single-use
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T139 browser harness is on main (871889bd) as a Playwright suite; a Java ContainerTest cannot drive a browser - rewrite as a Playwright security spec or retire this stub (T141 gate case S1-A2)")
    void s1_a2_pkce_state_enforcement() {
        fail("S1-A2 body lands with T139");
    }

    /**
     * S1-A3: redirect_uri exact match (pack: "no wildcards/path traversal", OAuth 2.1).
     * Expected: Every variation refused at the authorization endpoint; no wildcard matching
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T139 browser harness is on main (871889bd) as a Playwright suite; a Java ContainerTest cannot drive a browser - rewrite as a Playwright security spec or retire this stub (T141 gate case S1-A3)")
    void s1_a3_redirect_uri_exact_match() {
        fail("S1-A3 body lands with T139");
    }

    /**
     * S1-A6: Forged-tenant matrix, E4-F1-S1/S2 patterns (ADR-007 D2).
     * Expected: Each changes nothing: request acts on A or is 403 + audit (the bootstrap lookup (issuer,subject)/(issuer,azp) decides; the claim/header/body value is only cross-checked, never trusted)
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T157 tenant B seed is on main (platform-e2e TenantBSeed) but unreachable from platform-app; move the case to platform-e2e (T141 gate case S1-A6)")
    void s1_a6_forged_tenant_matrix_e4_f1_s1() {
        fail("S1-A6 body lands with T157");
    }

    /**
     * S1-A7: Role matrix on every admin operation (E4-F1-S3).
     * Expected: Allowed exactly per the backlog matrix; every out-of-role call 403; approver = author refused (DB CHECK); 401/403 never retried by any layer
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T157 tenant B seed is on main (platform-e2e TenantBSeed) but unreachable from platform-app; move the case to platform-e2e (T141 gate case S1-A7)")
    void s1_a7_role_matrix_on_every_admin_operation() {
        fail("S1-A7 body lands with T157");
    }

}
