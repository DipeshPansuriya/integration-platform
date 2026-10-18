package io.integrationplatform.config.e5.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.system.application.DestinationSettingsCheck;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Row 327 T97 (T-184, ADR-011 Decision 1): every target type's host field passes the ONE destination guard when an
 * OUTBOUND system is saved - the API baseUrl's host, the MQ host and the DB host; a FILE root has no host (its path
 * confinement is the File adapter's, {@code ConfinedPath}). The save-time check never resolves a name: the resolver
 * here fails the test if it is asked. Each error names the field. Not tagged container (R03); the API answer (400) is
 * {@code SystemRegistryContainerTest.saveTimeDestinationGuardRefuses}.
 */
class DestinationSettingsCheckTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final List<String> lookups = new ArrayList<>();
    private final DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("*.partner.example")), host -> {
        lookups.add(host);
        throw new AssertionError("the save-time check must not resolve " + host);
    });

    private List<String> errors(String type, String settings) {
        return DestinationSettingsCheck.errors(type, JSON.readTree(settings), guard);
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
        "API | {\"baseUrl\":\"http://127.0.0.1:8080/orders\"}            | settings.baseUrl | loopback",
        "API | {\"baseUrl\":\"http://10.0.0.5/orders\"}                  | settings.baseUrl | private",
        "API | {\"baseUrl\":\"http://[::ffff:10.0.0.5]/\"}               | settings.baseUrl | private",
        "MQ  | {\"host\":\"169.254.169.254\",\"port\":1414,\"queue\":\"Q\"} | settings.host    | link-local",
        "MQ  | {\"host\":\"127.0.0.1\",\"port\":1414,\"queue\":\"Q\"}       | settings.host    | loopback",
        "DB  | {\"host\":\"fd00::1\",\"port\":5432,\"database\":\"d\"}      | settings.host    | unique-local",
        "DB  | {\"host\":\"100.64.0.1\",\"port\":5432,\"database\":\"d\"}   | settings.host    | carrier-grade-nat",
        "API | {\"baseUrl\":\"https://not-allowed.example/\"}             | settings.baseUrl | allow-list",
        "MQ  | {\"host\":\"mq.elsewhere.example\",\"port\":1414,\"queue\":\"Q\"} | settings.host | allow-list"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("Every type's host field is refused at save by the one guard, the error naming the field")
    void everyTypesHostIsCheckedAtSave(String type, String settings, String field, String reason) {
        List<String> errors = errors(type, settings);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains(field).contains(reason);
        assertThat(lookups).isEmpty();
    }

    @Test
    @DisplayName("Allow-listed hosts of every type save with no lookup; a FILE root has no host to check")
    void allowedHostsAndFileRootsPass() {
        assertThat(errors("API", "{\"baseUrl\":\"https://api.partner.example/v1\"}")).isEmpty();
        assertThat(errors("MQ", "{\"host\":\"mq.partner.example\",\"port\":1414,\"queue\":\"Q\"}")).isEmpty();
        assertThat(errors("DB", "{\"host\":\"db.partner.example\",\"port\":5432,\"database\":\"d\"}")).isEmpty();
        assertThat(errors("FILE", "{\"rootPath\":\"/data/out\"}")).isEmpty();
        assertThat(lookups).isEmpty();
    }

    @Test
    @DisplayName("A malformed or missing host is the schema's error, not reported twice")
    void schemaErrorsAreNotRepeated() {
        assertThat(errors("API", "{\"baseUrl\":\"ftp://files.partner.example/\"}")).isEmpty();
        assertThat(errors("API", "{\"baseUrl\":\"not a url\"}")).isEmpty();
        assertThat(errors("MQ", "{\"port\":1414,\"queue\":\"Q\"}")).isEmpty();
        assertThat(errors("MQ", "{\"host\":7,\"port\":1414,\"queue\":\"Q\"}")).isEmpty();
    }
}
