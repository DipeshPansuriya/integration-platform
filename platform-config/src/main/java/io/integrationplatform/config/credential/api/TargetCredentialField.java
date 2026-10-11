package io.integrationplatform.config.credential.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every credential field a target can hold (ADR-007 Decision 7 rule 10): each is its own {@code target_credential}
 * row. The constant name is the stored value (text + CHECK, R10); {@link #jsonName()} is how the admin API names it.
 * Which type may hold which field is {@link TargetCredentialType}.
 */
public enum TargetCredentialField {

    API_KEY("apiKey"),
    BEARER_TOKEN("bearerToken"),
    BASIC_PASSWORD("basicPassword"),
    OAUTH_CLIENT_SECRET("oauthClientSecret"),
    CLIENT_CERT_PRIVATE_KEY("clientCertPrivateKey"),
    CLIENT_CERT_KEYSTORE_PASSWORD("clientCertKeystorePassword"),
    QUEUE_MANAGER_PASSWORD("queueManagerPassword"),
    TLS_KEYSTORE_PASSWORD("tlsKeystorePassword"),
    TLS_TRUSTSTORE_PASSWORD("tlsTruststorePassword"),
    DB_ACCOUNT_PASSWORD("accountPassword"),
    SHARE_ACCOUNT_PASSWORD("shareAccountPassword"),
    SASL_PASSWORD("saslPassword");

    private final String jsonName;

    TargetCredentialField(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    public static Optional<TargetCredentialField> ofJsonName(String jsonName) {
        return Arrays.stream(values()).filter(field -> field.jsonName.equals(jsonName)).findFirst();
    }
}
