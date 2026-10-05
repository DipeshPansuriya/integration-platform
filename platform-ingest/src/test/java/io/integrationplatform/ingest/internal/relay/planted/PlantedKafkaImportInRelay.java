package io.integrationplatform.ingest.internal.relay.planted;

import org.apache.kafka.clients.producer.KafkaProducerStub;

/**
 * Planted violation for T-041, scenario "No vendor client in the relay" (@negative): a class in the relay's package
 * importing a Kafka client class must fail the build. Test source only - the production scan of
 * {@code RelayVendorClientArchitectureTest} excludes test classes; the fixture run asserts the rule fires on this.
 */
public final class PlantedKafkaImportInRelay {

    @SuppressWarnings("unused")
    private final KafkaProducerStub producer = new KafkaProducerStub();
}
