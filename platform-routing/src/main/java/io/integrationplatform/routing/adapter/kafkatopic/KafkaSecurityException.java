package io.integrationplatform.routing.adapter.kafkatopic;

/**
 * E16: the Kafka client security of a target cannot be built (no SASL credential stored, a truststore that is not
 * there). A configuration fault - PERMANENT, never retried. The message is fixed text: it never carries a credential.
 */
final class KafkaSecurityException extends RuntimeException {

    KafkaSecurityException(String message) {
        super(message);
    }
}
