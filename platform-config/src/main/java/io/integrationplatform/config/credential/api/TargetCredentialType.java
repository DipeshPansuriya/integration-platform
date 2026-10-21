package io.integrationplatform.config.credential.api;

import static io.integrationplatform.config.credential.api.TargetCredentialField.API_KEY;
import static io.integrationplatform.config.credential.api.TargetCredentialField.BASIC_PASSWORD;
import static io.integrationplatform.config.credential.api.TargetCredentialField.BEARER_TOKEN;
import static io.integrationplatform.config.credential.api.TargetCredentialField.CLIENT_CERT_KEYSTORE_PASSWORD;
import static io.integrationplatform.config.credential.api.TargetCredentialField.CLIENT_CERT_PRIVATE_KEY;
import static io.integrationplatform.config.credential.api.TargetCredentialField.DB_ACCOUNT_PASSWORD;
import static io.integrationplatform.config.credential.api.TargetCredentialField.OAUTH_CLIENT_SECRET;
import static io.integrationplatform.config.credential.api.TargetCredentialField.QUEUE_MANAGER_PASSWORD;
import static io.integrationplatform.config.credential.api.TargetCredentialField.SASL_PASSWORD;
import static io.integrationplatform.config.credential.api.TargetCredentialField.SHARE_ACCOUNT_PASSWORD;
import static io.integrationplatform.config.credential.api.TargetCredentialField.TLS_KEYSTORE_PASSWORD;
import static io.integrationplatform.config.credential.api.TargetCredentialField.TLS_TRUSTSTORE_PASSWORD;

import java.util.List;
import java.util.Optional;

/**
 * The credential fields per target type - exactly the table of ADR-007 Decision 7 rule 10. The platform's own
 * PostgreSQL, Kafka and Keycloak credentials are not target credentials (they come from the secret store by
 * environment-variable name). The database CHECK of V2_3_7 lists the same pairs (TargetCredentialFieldsTest).
 */
public enum TargetCredentialType {

    API(API_KEY, BEARER_TOKEN, BASIC_PASSWORD, OAUTH_CLIENT_SECRET, CLIENT_CERT_PRIVATE_KEY,
            CLIENT_CERT_KEYSTORE_PASSWORD),
    MQ_CSV(QUEUE_MANAGER_PASSWORD, TLS_KEYSTORE_PASSWORD, TLS_TRUSTSTORE_PASSWORD),
    DB(DB_ACCOUNT_PASSWORD),
    FILE(SHARE_ACCOUNT_PASSWORD),
    KAFKA_TOPIC(SASL_PASSWORD, TLS_KEYSTORE_PASSWORD);

    private final List<TargetCredentialField> fields;

    TargetCredentialType(TargetCredentialField... fields) {
        this.fields = List.of(fields);
    }

    public List<TargetCredentialField> fields() {
        return fields;
    }

    /**
     * The target type of a registered system's type (E5 T-102: API, DB, MQ, FILE); MQ is the MQ_CSV target. An untyped
     * system (E3) has none. A KAFKA system (E16) is the KAFKA_TOPIC target.
     */
    public static Optional<TargetCredentialType> ofSystemType(String systemType) {
        if (systemType == null) {
            return Optional.empty();
        }
        return switch (systemType) {
            case "API" -> Optional.of(API);
            case "MQ" -> Optional.of(MQ_CSV);
            case "DB" -> Optional.of(DB);
            case "FILE" -> Optional.of(FILE);
            case "KAFKA" -> Optional.of(KAFKA_TOPIC);
            default -> Optional.empty();
        };
    }
}
