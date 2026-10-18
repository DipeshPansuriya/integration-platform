package io.integrationplatform.routing.adapter.mq;

import java.io.IOException;
import java.time.Duration;

/** Opens and closes one connection to the broker queue within the timeout; the JMS-backed one is pending (T98). */
@FunctionalInterface
public interface BrokerProbe {

    void probe(String host, int port, String queue, Duration timeout) throws IOException;
}
