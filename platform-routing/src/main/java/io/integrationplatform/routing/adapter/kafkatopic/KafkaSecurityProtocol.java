package io.integrationplatform.routing.adapter.kafkatopic;

import io.integrationplatform.config.system.application.SystemSettings;

/**
 * E16: a KAFKA system's {@code securityProtocol}. A system that does not set one is dialled with
 * {@link #SSL} - clear text is only ever what the operator wrote down explicitly.
 */
enum KafkaSecurityProtocol {
    PLAINTEXT(false, false), SSL(true, false), SASL_PLAINTEXT(false, true), SASL_SSL(true, true);

    private final boolean tls;
    private final boolean sasl;

    KafkaSecurityProtocol(boolean tls, boolean sasl) {
        this.tls = tls;
        this.sasl = sasl;
    }

    boolean tls() {
        return tls;
    }

    boolean sasl() {
        return sasl;
    }

    /** The configured protocol; {@code null} (not set) is {@link SystemSettings#KAFKA_DEFAULT_PROTOCOL}. */
    static KafkaSecurityProtocol resolve(String configured) {
        String name = configured == null ? SystemSettings.KAFKA_DEFAULT_PROTOCOL : configured;
        if (!SystemSettings.KAFKA_PROTOCOLS.contains(name)) {
            throw new IllegalStateException("Kafka securityProtocol must be one of "
                    + String.join(", ", SystemSettings.KAFKA_PROTOCOLS));
        }
        return valueOf(name);
    }
}
