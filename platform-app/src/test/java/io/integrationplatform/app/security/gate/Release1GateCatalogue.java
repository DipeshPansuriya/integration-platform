package io.integrationplatform.app.security.gate;

import io.integrationplatform.app.security.gate.Release1Gate.Area;
import io.integrationplatform.app.security.gate.Release1Gate.GateCase;
import java.util.ArrayList;
import java.util.List;

/**
 * The 54 cases of release-1-security-test-plan.md (S1-S8), each with its area and the item it waits on.
 * Area roll-up from the plan: S2/S5/S7 tenant isolation, S1/S3/S4 identity, S6 outbound destination controls,
 * S8 credential storage. The waiting item is the first tracker item whose code must be on main before the case
 * can run; the matching {@code SecurityGateS<n>...ContainerTest} method is {@code @Disabled} with the same item.
 */
public final class Release1GateCatalogue {

    private static final List<GateCase> CASES = build();

    private Release1GateCatalogue() {}

    public static List<GateCase> cases() {
        return CASES;
    }

    private static List<GateCase> build() {
        List<GateCase> c = new ArrayList<>();
        suite(c, "S1", Area.IDENTITY, "T139", "T139", "T139", "T142", "T142", "T157", "T157", "T142");
        suite(c, "S2", Area.TENANT_ISOLATION, "T157", "T157", "T157", "T142", "T142", "T157", "T157", "T142");
        suite(c, "S3", Area.IDENTITY, "T139", "T139", "T139", "T139", "T139", "T139");
        suite(c, "S4", Area.IDENTITY, "T127", "T142", "T127", "T142", "T127", "T142", "T142", "T162");
        // S5-A3: no tracker item for the payload edit surface yet; T68 (payload view audit hooks) is the nearest.
        suite(c, "S5", Area.TENANT_ISOLATION, "T142", "T142", "T68", "T142", "T157", "T142");
        suite(c, "S6", Area.OUTBOUND_DESTINATION, "T68", "T68", "T68", "T68", "T136", "T68");
        suite(c, "S7", Area.TENANT_ISOLATION, "T68", "T111", "T115", "T75", "T68", "T68");
        suite(c, "S8", Area.CREDENTIAL_STORAGE, "T100", "T142", "T142", "T142", "T100", "T142");
        return List.copyOf(c);
    }

    private static void suite(List<GateCase> into, String suite, Area area, String... waitingOn) {
        for (int i = 0; i < waitingOn.length; i++) {
            into.add(new GateCase(suite + "-A" + (i + 1), area, waitingOn[i]));
        }
    }
}
