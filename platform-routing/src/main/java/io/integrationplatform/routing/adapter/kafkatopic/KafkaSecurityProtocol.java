package io.integrationplatform.routing.adapter.kafkatopic;


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

    /** An unset securityProtocol is TLS; PLAINTEXT only when written explicitly (mirrors the save-time rule in config). */
    static final String DEFAULT_PROTOCOL = "SSL";
    static final java.util.List<String> PROTOCOLS = java.util.List.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");

    /** The configured protocol; {@code null} (not set) is {@link #DEFAULT_PROTOCOL}. */
    static KafkaSecurityProtocol resolve(String configured) {
        String name = configured == null ? DEFAULT_PROTOCOL : configured;
        if (!PROTOCOLS.contains(name)) {
            throw new IllegalStateException("Kafka securityProtocol must be one of "
                    + String.join(", ", PROTOCOLS));
        }
        return valueOf(name);
    }
}
