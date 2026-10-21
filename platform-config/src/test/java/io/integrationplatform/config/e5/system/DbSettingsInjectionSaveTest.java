package io.integrationplatform.config.e5.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.system.application.SystemSettings;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * E16 security F2 (CWE-74), save time: a DB target's host and database become parts of a JDBC URL, so a value that
 * could add a driver property or a second host ({@code ? & ; / # , % @}, a backtick, whitespace) is refused when
 * the system is saved. F5: the optional sslmode is a closed list of encrypted modes.
 */
class DbSettingsInjectionSaveTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static List<String> errors(String settings) {
        return SystemSettings.validate(JSON.getNodeFactory().stringNode("DB"), JSON.readTree(settings));
    }

    private static String json(String host, String database) {
        return JSON.createObjectNode().put("host", host).put("port", 5432).put("database", database).toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"db.example?x=1", "db.example&x=1", "db.example;x", "db.example/x", "db.example#x",
        "a.example,b.example", "db.example%2f", "user@db.example", "db`.example", "db .example", "db.example\n",
        "db\t.example", "[::1", "-db.example", "db..example", "http://db.example", "db.example:5432"})
    @Tag("negative")
    @Tag("security")
    void aHostThatCouldCarryAUrlPartIsRefusedAtSave(String host) {
        assertThat(errors(json(host, "orders"))).singleElement().asString().startsWith("settings.host");
    }

    @ParameterizedTest
    @ValueSource(strings = {"x?socketFactory=evil", "orders&x=1", "orders;x", "or/ders", "orders#x", "a,b",
        "orders%20", "or@ders", "or`ders", "or ders", "orders\n", "", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    @Tag("negative")
    @Tag("security")
    void aDatabaseThatCouldCarryADriverPropertyIsRefusedAtSave(String database) {
        assertThat(errors(json("db.example", database))).singleElement().asString().startsWith("settings.database");
    }

    @ParameterizedTest
    @ValueSource(strings = {"db.example", "DB-1.Partner.Example", "10.1.2.3", "localhost", "[2001:db8::1]"})
    void aPlainHostNameOrIpLiteralIsAccepted(String host) {
        assertThat(errors(json(host, "orders_in.v-2"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"require", "verify-ca", "verify-full"})
    void anEncryptedSslmodeIsAccepted(String mode) {
        assertThat(errors("{\"host\":\"db.example\",\"port\":5432,\"database\":\"o\",\"sslmode\":\"" + mode + "\"}"))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"disable", "allow", "prefer", "require&x=1", ""})
    @Tag("negative")
    @Tag("security")
    void aWeakOrUnknownSslmodeIsRefusedAtSave(String mode) {
        assertThat(errors("{\"host\":\"db.example\",\"port\":5432,\"database\":\"o\",\"sslmode\":\"" + mode + "\"}"))
                .singleElement().asString().startsWith("settings.sslmode");
    }

    @Test
    void theMqHostRuleIsUnchanged() {
        assertThat(SystemSettings.validate(JSON.getNodeFactory().stringNode("MQ"),
                JSON.readTree("{\"host\":\"mq.example?x\",\"port\":61616,\"queue\":\"q\"}"))).isEmpty();
    }
}
