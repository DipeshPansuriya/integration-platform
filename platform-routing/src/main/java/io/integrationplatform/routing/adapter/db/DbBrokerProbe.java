package io.integrationplatform.routing.adapter.db;

import java.io.IOException;
import java.time.Duration;

/** The DB target's reachability probe for test-connection (the BrokerProbe/KafkaBrokerProbe precedent). */
@FunctionalInterface
public interface DbBrokerProbe {

    void probe(String host, int port, Duration timeout) throws IOException;
}