package io.integrationplatform.app.security.gate;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T141 / T-147 Release 1 security gate, suite S5 (TenantIsolation), from release-1-security-test-plan.md.
 * Every case is disabled until the tracker item it names is on main; the area roll-up is
 * {@link Release1Gate} and the case list is {@link Release1GateCatalogue}. Container tag: never runs by default.
 */
@Tag("container")
class SecurityGateS5TenantIsolationContainerTest {

    /**
     * S5-A3: Payload JSON editing (E7 payload view/edit).
     * Expected: Accepted/4xx cleanly; no JSONB error surfaces raw SQL; oversized bodies rejected before persistence
     */
    @Test
    @Disabled("BLOCKED: no payload edit surface exists on main (T68 merged, but no tracker item for an edit endpoint); case stays until that surface is specified (T141 gate case S5-A3)")
    void s5_a3_payload_json_editing() {
        fail("S5-A3 body lands with T68");
    }
}
