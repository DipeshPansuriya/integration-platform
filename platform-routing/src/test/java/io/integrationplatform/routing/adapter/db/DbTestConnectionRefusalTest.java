package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E16 R6 (CWE-918, limited): DB test-connection applies the SAME port allow-list the send path applies
 * ({@code DbTargetCall.requireAllowedPort}) - an off-list port is refused before the probe, so an allow-listed
 * host cannot be port-scanned with SUCCESS / DATABASE_UNREACHABLE answers over ports 1-65535.
 */
class DbTestConnectionRefusalTest {

    private final DbBrokerProbe probe = mock(DbBrokerProbe.class);

    private final DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("db.partner.example")), host -> {
        throw new AssertionError("the save-time check must not resolve " + host);
    });

    private final DbTestConnection connection =
            new DbTestConnection(guard, probe, Set.of(5432), Duration.ofSeconds(5));

    private String json(int port) {
        return "{\"host\":\"db.partner.example\",\"port\":" + port + ",\"database\":\"orders\"}";
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("R6: an off-list port is refused before anything is probed or dialled")
    void aPortOutsideTheAllowListIsRefusedWithoutProbing() {
        Map<String, Object> answer = connection.test(java.util.UUID.randomUUID(), json(22));

        assertThat(answer.get("result")).isEqualTo("FAILED");
        assertThat(answer.get("reason")).isEqualTo("DESTINATION_REFUSED");
        assertThat(answer.get("message")).asString()
                .isEqualTo("port 22 is not an allowed database port");
        verifyNoInteractions(probe);
    }

    @Test
    @DisplayName("An allowed port is still probed and answers SUCCESS as before")
    void anAllowedPortIsStillProbedAndAnswersSuccess() throws Exception {
        Map<String, Object> answer = connection.test(java.util.UUID.randomUUID(), json(5432));

        assertThat(answer.get("result")).isEqualTo("SUCCESS");
        assertThat(answer.get("reason")).isNull();
    }
}