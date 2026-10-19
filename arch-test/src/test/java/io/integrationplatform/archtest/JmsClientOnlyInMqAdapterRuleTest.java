package io.integrationplatform.archtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

/** T98: the JMS client stays in routing.adapter.mq; one planted import outside it fails the build. */
class JmsClientOnlyInMqAdapterRuleTest {

    static final String PLANTED = "io.integrationplatform.t98.archfixture.planted";

    @Test
    void productionClassesPassAndTheMqAdapterIsInScope() {
        JavaClasses production = JmsClientOnlyInMqAdapterRule.importProductionClasses();

        assertThat(production.contain("io.integrationplatform.routing.adapter.mq.MqTargetCall"))
                .as("the rule is not vacuous: the JMS user is scanned").isTrue();
        JmsClientOnlyInMqAdapterRule.JMS_CLIENT_ONLY_IN_MQ_ADAPTER.check(production);
    }

    @Test
    void jmsOutsideTheMqAdapterFailsAndNamesTheClass() {
        JavaClasses planted = new ClassFileImporter().importPackages(PLANTED);

        assertThat(planted.size()).as("the planted violation is in scope").isEqualTo(1);
        assertThatThrownBy(() -> JmsClientOnlyInMqAdapterRule.JMS_CLIENT_ONLY_IN_MQ_ADAPTER.check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PlantedJmsOutsideMqAdapter")
                .hasMessageContaining("jakarta.jms");
    }
}
