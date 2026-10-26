package io.integrationplatform.routing.adapter.kafkatopic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * E16: the producer's security properties from a KAFKA system's settings and the vault's SASL password.
 *
 * <ul>
 *   <li>TLS (SSL, SASL_SSL) always verifies the broker's hostname ({@code ssl.endpoint.identification.algorithm=https})
 *       and offers TLS 1.3 and 1.2 only; an optional truststore is a FILE NAME under the operator's truststore
 *       directory (it can never name another path).</li>
 *   <li>SASL (PLAIN, SCRAM-SHA-256/512) builds the JAAS line from the user name and the password the vault opened; the
 *       password is escaped so no character of it can end its value, and a missing password is refused.</li>
 * </ul>
 *
 * Nothing is read from anywhere else: settings hold no secret and this class logs nothing.
 */
final class KafkaSecurity {

    private static final String PLAIN_MODULE = "org.apache.kafka.common.security.plain.PlainLoginModule";
    private static final String SCRAM_MODULE = "org.apache.kafka.common.security.scram.ScramLoginModule";

    private KafkaSecurity() {
    }

    static void apply(Properties props, KafkaTopicSettings settings, char[] saslPassword, Path truststoreDir) {
        KafkaSecurityProtocol protocol = settings.protocol();
        props.put("security.protocol", protocol.name());
        if (protocol.tls()) {
            props.put("ssl.endpoint.identification.algorithm", "https");
            props.put("ssl.enabled.protocols", "TLSv1.3,TLSv1.2");
            if (settings.truststore() != null) {
                Path store = truststore(settings.truststore(), truststoreDir);
                props.put("ssl.truststore.location", store.toString());
                String name = settings.truststore().toLowerCase(Locale.ROOT);
                props.put("ssl.truststore.type", name.endsWith(".p12") || name.endsWith(".pfx") ? "PKCS12" : "JKS");
            }
        }
        if (protocol.sasl()) {
            if (saslPassword == null || saslPassword.length == 0) {
                throw new KafkaSecurityException("the SASL credential of the Kafka target is not stored");
            }
            String mechanism = settings.saslMechanism();
            props.put("sasl.mechanism", mechanism);
            props.put("sasl.jaas.config", (mechanism.equals("PLAIN") ? PLAIN_MODULE : SCRAM_MODULE)
                    + " required username=\"" + escape(settings.username()) + "\" password=\""
                    + escape(new String(saslPassword)) + "\";");
        }
    }

    private static Path truststore(String name, Path directory) {
        if (directory == null) {
            throw new KafkaSecurityException("a truststore is set but the truststore directory is not configured");
        }
        Path root = directory.toAbsolutePath().normalize();
        Path store = root.resolve(name).normalize();
        if (!store.startsWith(root) || !Files.isRegularFile(store)) {
            throw new KafkaSecurityException("the truststore of the Kafka target was not found");
        }
        return store;
    }

    /**
     * JAAS quoted-string escaping (Kafka reads it with a StreamTokenizer): a backslash or a double quote in a value
     * cannot end it or add an option, and a control character (a line break ends a quoted token) is written as its
     * octal escape.
     */
    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c == 0x7f) {
                out.append('\\').append(String.format("%03o", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
