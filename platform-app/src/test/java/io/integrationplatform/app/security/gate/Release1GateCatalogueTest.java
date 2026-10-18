package io.integrationplatform.app.security.gate;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.app.security.gate.Release1Gate.Area;
import io.integrationplatform.app.security.gate.Release1Gate.GateCase;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The catalogue matches release-1-security-test-plan.md: 54 cases, suites S1-S8, all four areas covered. */
class Release1GateCatalogueTest {

    @Test
    void holdsThe54PlanCasesOnceEach() {
        List<GateCase> cases = Release1GateCatalogue.cases();

        assertThat(cases).hasSize(54);
        assertThat(cases).extracting(GateCase::id).doesNotHaveDuplicates();
        Map<String, Long> perSuite = cases.stream()
                .collect(Collectors.groupingBy(c -> c.id().substring(0, 2), Collectors.counting()));
        assertThat(perSuite).containsExactlyInAnyOrderEntriesOf(Map.of(
                "S1", 8L, "S2", 8L, "S3", 6L, "S4", 8L, "S5", 6L, "S6", 6L, "S7", 6L, "S8", 6L));
    }

    @Test
    void everyAreaHasCases_andEachSuiteRollsUpToThePlanArea() {
        List<GateCase> cases = Release1GateCatalogue.cases();

        assertThat(cases.stream().map(GateCase::area).collect(Collectors.toSet()))
                .isEqualTo(EnumSet.allOf(Area.class));
        Map<String, Area> planArea = Map.of("S1", Area.IDENTITY, "S2", Area.TENANT_ISOLATION, "S3", Area.IDENTITY,
                "S4", Area.IDENTITY, "S5", Area.TENANT_ISOLATION, "S6", Area.OUTBOUND_DESTINATION,
                "S7", Area.TENANT_ISOLATION, "S8", Area.CREDENTIAL_STORAGE);
        assertThat(cases).allSatisfy(c ->
                assertThat(c.area()).as(c.id()).isEqualTo(planArea.get(c.id().substring(0, 2))));
    }

    @Test
    void everyCaseNamesTheTrackerItemItWaitsOn() {
        assertThat(Release1GateCatalogue.cases())
                .allSatisfy(c -> assertThat(c.waitingOn()).as(c.id()).matches("T\\d+"));
    }
}
