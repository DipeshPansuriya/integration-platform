package io.integrationplatform.routing.adapter.mq;

import java.util.Set;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

/** Keeps Boot from wiring its own Artemis/JMS beans: the MQ adapter owns every broker connection (ADR-018, T98). */
public final class MqAutoConfigurationFilter implements AutoConfigurationImportFilter {

    static final Set<String> EXCLUDED = Set.of(
            "org.springframework.boot.artemis.autoconfigure.ArtemisAutoConfiguration",
            "org.springframework.boot.jms.autoconfigure.JmsAutoConfiguration");

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
        boolean[] matches = new boolean[autoConfigurationClasses.length];
        for (int i = 0; i < matches.length; i++) {
            matches[i] = autoConfigurationClasses[i] == null || !EXCLUDED.contains(autoConfigurationClasses[i]);
        }
        return matches;
    }
}
