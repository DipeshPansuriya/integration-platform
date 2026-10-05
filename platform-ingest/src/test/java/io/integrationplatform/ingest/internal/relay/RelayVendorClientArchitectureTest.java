package io.integrationplatform.ingest.internal.relay;

import static com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * T-041, scenario E2-F1-S2 "No vendor client in the relay" (@negative): any import of a Kafka or JMS client class by
 * the relay's package fails the build, so E8 can plug Kafka into the publish port without the relay ever seeing it
 * (ADR-005; R12 keeps Camel and Kafka out of this module).
 *
 * <p>The production run applies the rule to every compiled class of {@code ingest.internal.relay}. The fixture run
 * applies the SAME rule object to the planted violation ({@code relay.planted.PlantedKafkaImportInRelay}, which
 * imports a stub Kafka class) and asserts it fires - R02: a claimed control has a test that fails on a planted
 * violation.
 */
class RelayVendorClientArchitectureTest {

    private static final ArchRule NO_VENDOR_CLIENT_IN_RELAY = noClasses()
            .that().resideInAPackage("..ingest.internal.relay..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.apache.kafka..", "org.apache.camel..", "javax.jms..", "jakarta.jms..");

    @Test
    void relayProductionClassesExistForTheRuleToGuard() {
        assertThat(relayProductionClasses().sizeOfAllClasses())
                .as("the rule is not vacuous: the relay package holds production classes")
                .isPositive();
    }

    @Test
    void theRelayPackageDependsOnNoVendorClient() {
        NO_VENDOR_CLIENT_IN_RELAY.check(relayProductionClasses());
    }

    @Test
    void theRuleFiresOnAPlantedKafkaImport() {
        JavaClasses planted = new ClassFileImporter()
                .importPackages("io.integrationplatform.ingest.internal.relay.planted");

        assertThatThrownBy(() -> NO_VENDOR_CLIENT_IN_RELAY.check(planted))
                .as("the planted Kafka import in the relay package must violate the rule")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedKafkaImportInRelay")
                .hasMessageContaining("org.apache.kafka.clients.producer.KafkaProducerStub");
    }

    private static JavaClasses relayProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(DO_NOT_INCLUDE_TESTS)
                .importPackages("io.integrationplatform.ingest.internal.relay");
    }
}
