package io.integrationplatform.archtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/**
 * E10-F1-S6 "Every type's host is checked" (T-184): the one destination guard is the only way out. The production
 * scan proves today's adapters (API, MQ) resolve and dial only through config's destination package; the planted
 * fixture proves a new adapter that resolves or dials on its own fails the build and is named.
 */
class OutboundOnlyThroughGuardRuleTest {

    private static final String FIXTURES = "io.integrationplatform.e10.archfixture";

    @Test
    void productionAdaptersResolveAndDialOnlyThroughTheGuard() {
        JavaClasses production = CamelOnlyInRoutingRule.importProductionClasses();

        assertThat(production.containPackage("io.integrationplatform.routing.adapter.api"))
                .as("the API adapter in scope").isTrue();
        assertThat(production.containPackage("io.integrationplatform.routing.adapter.mq"))
                .as("the MQ adapter in scope").isTrue();

        OutboundOnlyThroughGuardRule.OUTBOUND_ONLY_THROUGH_GUARD.check(production);
    }

    @Test
    void anAddressFromBytesPasses() {
        JavaClasses compliant = new ClassFileImporter().importPackages(FIXTURES + ".compliant");

        assertThat(compliant.size()).as("the compliant fixture is in scope").isEqualTo(1);
        OutboundOnlyThroughGuardRule.OUTBOUND_ONLY_THROUGH_GUARD.check(compliant);
    }

    @Test
    void resolvingOrDiallingPastTheGuardFailsAndNamesEveryLeg() {
        JavaClasses planted = new ClassFileImporter().importPackages(FIXTURES + ".planted");

        assertThat(planted.size()).as("the planted violation is in scope").isEqualTo(1);
        assertThatThrownBy(() -> OutboundOnlyThroughGuardRule.OUTBOUND_ONLY_THROUGH_GUARD.check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedDialOutsideGuard")
                .hasMessageContaining("InetAddress.getAllByName")
                .hasMessageContaining("InetAddress.getByName")
                .hasMessageContaining("Socket.connect")
                .hasMessageContaining("Socket.<init>(java.lang.String, int)")
                .hasMessageContaining("InetSocketAddress.<init>(java.lang.String, int)");
    }
}
