package io.integrationplatform.routing.adapter.kafkatopic;

import java.io.IOException;
import java.time.Duration;

/** Checks one Kafka bootstrap server is dialled and answers within the timeout. */
@FunctionalInterface
public interface KafkaBrokerProbe {

    void probe(String host, int port, Duration timeout) throws IOException;
}
