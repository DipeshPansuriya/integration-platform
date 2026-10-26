package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.core.io.support.SpringFactoriesLoader;

/** T98: Boot's own Artemis/JMS connection factory never starts; the MQ adapter builds one per target. */
class MqAutoConfigurationFilterTest {

    @Test
    void rejectsBootArtemisAndJmsAutoConfigurationOnly() {
        boolean[] match = new MqAutoConfigurationFilter().match(new String[] {
            "org.springframework.boot.artemis.autoconfigure.ArtemisAutoConfiguration",
            "org.springframework.boot.jms.autoconfigure.JmsAutoConfiguration",
            "org.apache.camel.spring.boot.CamelAutoConfiguration"}, null);

        assertThat(match).containsExactly(false, false, true);
    }

    @Test
    void isRegisteredAsAnImportFilter() {
        assertThat(SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
                .load(AutoConfigurationImportFilter.class)).hasAtLeastOneElementOfType(MqAutoConfigurationFilter.class);
    }
}
