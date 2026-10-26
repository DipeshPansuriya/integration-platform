package io.integrationplatform.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/** T98 (ADR-018): the Artemis client and the JMS API are used only by the MQ target adapter. */
final class JmsClientOnlyInMqAdapterRule {

    static final String ROOT_PACKAGE = "io.integrationplatform";

    static final String MQ_ADAPTER_PACKAGE = "io.integrationplatform.routing.adapter.mq..";

    static final ArchRule JMS_CLIENT_ONLY_IN_MQ_ADAPTER = noClasses()
            .that().resideInAPackage(ROOT_PACKAGE + "..")
            .and().resideOutsideOfPackages(MQ_ADAPTER_PACKAGE)
            .should().dependOnClassesThat().resideInAnyPackage("org.apache.activemq..", "jakarta.jms..")
            .because("the MQ adapter owns the broker connection and its five controls (ADR-018, T98):"
                    + " org.apache.activemq and jakarta.jms may be used only under " + MQ_ADAPTER_PACKAGE);

    private JmsClientOnlyInMqAdapterRule() {
    }

    static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT_PACKAGE);
    }
}
