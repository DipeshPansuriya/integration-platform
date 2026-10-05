package org.apache.kafka.clients.producer;

/**
 * Test-only stub standing in for a real Kafka client class. R12 keeps kafka-clients off this module's classpath,
 * so the "No vendor client in the relay" ArchUnit rule (T-041) could otherwise never be seen firing: this stub is
 * what {@code PlantedKafkaImportInRelayTest} references to prove the rule bites a planted violation.
 */
public final class KafkaProducerStub {

    public KafkaProducerStub() {
    }
}
