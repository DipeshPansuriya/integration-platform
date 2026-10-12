package io.integrationplatform.config.e5.credential;

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
import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.credential.api.TargetCredentialField;
import io.integrationplatform.config.credential.api.TargetCredentialType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-007 Decision 7 rule 10 (T-185, S-d): every credential the platform stores is named - one target_credential row
 * per field, the fields per target type exactly as the ADR's table, including MQ_CSV and DB. The code's register and
 * the database's CHECK (V2_3_7) are compared, so neither can drift from the other.
 */
class TargetCredentialFieldsTest {

    @Test
    @DisplayName("The ADR-007 D7 rule 10 table: API, MQ_CSV, DB, FILE, KAFKA_TOPIC and their fields")
    void theTablePerType() {
        assertThat(TargetCredentialType.API.fields()).containsExactly(API_KEY, BEARER_TOKEN, BASIC_PASSWORD,
                OAUTH_CLIENT_SECRET, CLIENT_CERT_PRIVATE_KEY, CLIENT_CERT_KEYSTORE_PASSWORD);
        assertThat(TargetCredentialType.MQ_CSV.fields()).containsExactly(QUEUE_MANAGER_PASSWORD,
                TLS_KEYSTORE_PASSWORD, TLS_TRUSTSTORE_PASSWORD);
        assertThat(TargetCredentialType.DB.fields()).containsExactly(DB_ACCOUNT_PASSWORD);
        assertThat(TargetCredentialType.FILE.fields()).containsExactly(SHARE_ACCOUNT_PASSWORD);
        assertThat(TargetCredentialType.KAFKA_TOPIC.fields()).containsExactly(SASL_PASSWORD, TLS_KEYSTORE_PASSWORD);
    }

    @Test
    @DisplayName("A registered system's type names its target type (MQ is the MQ_CSV target); an untyped system has none")
    void systemTypes() {
        assertThat(TargetCredentialType.ofSystemType("API")).contains(TargetCredentialType.API);
        assertThat(TargetCredentialType.ofSystemType("MQ")).contains(TargetCredentialType.MQ_CSV);
        assertThat(TargetCredentialType.ofSystemType("DB")).contains(TargetCredentialType.DB);
        assertThat(TargetCredentialType.ofSystemType("FILE")).contains(TargetCredentialType.FILE);
        assertThat(TargetCredentialType.ofSystemType(null)).isEmpty();
        assertThat(TargetCredentialType.ofSystemType("FTP")).isEmpty();
    }

    @Test
    @DisplayName("The API names a field in camelCase; the row stores the register's name")
    void jsonNames() {
        assertThat(BASIC_PASSWORD.jsonName()).isEqualTo("basicPassword");
        assertThat(QUEUE_MANAGER_PASSWORD.jsonName()).isEqualTo("queueManagerPassword");
        assertThat(DB_ACCOUNT_PASSWORD.jsonName()).isEqualTo("accountPassword");
        assertThat(TargetCredentialField.ofJsonName("tlsTruststorePassword")).contains(TLS_TRUSTSTORE_PASSWORD);
        assertThat(TargetCredentialField.ofJsonName("password")).isEmpty();
    }

    @Test
    @DisplayName("The database CHECK of V2_3_7 lists exactly the register's (type, field) pairs")
    void theMigrationMatchesTheRegister() throws IOException {
        Path migration = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize()
                .resolve("src/main/resources/db/migration/V2_3_7__target_credential.sql");
        String sql = Files.readString(migration);
        Set<String> inSql = new TreeSet<>();
        Matcher pair = Pattern.compile("\\('([A-Z_]+)',\\s*'([A-Z_]+)'\\)").matcher(sql);
        while (pair.find()) {
            inSql.add(pair.group(1) + "/" + pair.group(2));
        }
        Set<String> inCode = new TreeSet<>();
        Arrays.stream(TargetCredentialType.values()).forEach(type -> type.fields()
                .forEach(field -> inCode.add(type.name() + "/" + field.name())));
        assertThat(inSql).isEqualTo(inCode);
    }
}
