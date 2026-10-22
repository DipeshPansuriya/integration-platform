package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E16 F3 (design note task 11, Karan change 4), no containers: the answer keeps its fixed shape and reports the
 * account's real privileges in {@code detail}; every refusal is an answer with its own reason, and no privilege
 * probe runs when the socket never answered or the destination is refused.
 */
class DbTestConnectionPrivilegesTest {

    private static final String HOST = "db.partner.example";
    private static final int PORT = 5432;
    private static final String SETTINGS = "{\"host\":\"" + HOST + "\",\"port\":" + PORT
            + ",\"database\":\"orders\",\"user\":\"restricted\",\"table\":\"orders_in\"}";

    private final DbBrokerProbe socket = (host, port, timeout) -> { };
    private final DbPrivilegeProbe privileges = mock(DbPrivilegeProbe.class);
    private final UUID systemId = UUID.randomUUID();

    private DestinationGuard guard(int port) {
        try {
            return new DestinationGuard(AllowList.of(List.of(HOST)), name -> List.of(InetAddress.getLoopbackAddress()),
                    List.of(HOST), List.of(port));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private DbTestConnection connection(List<Integer> allowedPorts) {
        return new DbTestConnection(guard(PORT), socket, privileges, allowedPorts, Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("success answers the fixed shape with the account's privileges in detail")
    void successAnswersTheFixedShapeWithPrivileges() throws Exception {
        when(privileges.probe(eq(systemId), any(DbSettings.class), any(), any(Duration.class)))
                .thenReturn(List.of("INSERT", "SELECT"));

        var answer = connection(List.of(PORT)).test(systemId, SETTINGS);

        assertThat(answer.keySet()).containsExactly("systemId", "type", "result", "httpStatus", "responseTimeMs",
                "reason", "detail", "message");
        assertThat(answer.get("result")).isEqualTo("SUCCESS");
        assertThat(answer.get("reason")).isNull();
        assertThat((String) answer.get("detail")).contains("orders_in").contains("INSERT").contains("SELECT")
                .contains("restricted");
        assertThat((String) answer.get("message")).contains("credentialed");
    }

    @Test
    @DisplayName("a target that names no table reports no privileges but still answers SUCCESS")
    void aTargetWithoutTableReportsNoPrivileges() throws Exception {
        when(privileges.probe(eq(systemId), any(DbSettings.class), any(), any(Duration.class)))
                .thenReturn(List.of());
        String noTable = "{\"host\":\"" + HOST + "\",\"port\":" + PORT + ",\"database\":\"orders\",\"user\":\"r\"}";

        var answer = connection(List.of(PORT)).test(systemId, noTable);

        assertThat(answer.get("result")).isEqualTo("SUCCESS");
        assertThat(answer.get("detail")).isNull();
    }

    @Test
    @Tag("negative")
    @DisplayName("an unallowed port is refused before any credential open or dial")
    void anUnallowedPortIsRefused() throws Exception {
        var answer = connection(List.of(6432)).test(systemId, SETTINGS);

        assertThat(answer.get("result")).isEqualTo("FAILED");
        assertThat(answer.get("reason")).isEqualTo("DESTINATION_REFUSED");
        assertThat((String) answer.get("message")).contains("allowed database port");
        verify(privileges, never()).probe(any(), any(), any(), any());
    }

    @Test
    @Tag("negative")
    @DisplayName("a full-guard refusal is DESTINATION_REFUSED and no probe runs")
    void aFullGuardRefusalIsDestinationRefused() throws Exception {
        String stranger = "{\"host\":\"db.stranger.example\",\"port\":" + PORT
                + ",\"database\":\"orders\",\"user\":\"restricted\",\"table\":\"t\"}";

        var answer = connection(List.of(PORT)).test(systemId, stranger);

        assertThat(answer.get("result")).isEqualTo("FAILED");
        assertThat(answer.get("reason")).isEqualTo("DESTINATION_REFUSED");
        verify(privileges, never()).probe(any(), any(), any(), any());
    }

    @Test
    @Tag("negative")
    @DisplayName("a probe refusal keeps its reason in the answer - AUTH_REFUSED reads as FAILED")
    void aProbeRefusalKeepsItsReason() throws Exception {
        when(privileges.probe(eq(systemId), any(DbSettings.class), any(), any(Duration.class)))
                .thenThrow(new DbPrivilegeProbe.ProbeRefused("AUTH_REFUSED", "the target database refused the account"));

        var answer = connection(List.of(PORT)).test(systemId, SETTINGS);

        assertThat(answer.get("result")).isEqualTo("FAILED");
        assertThat(answer.get("reason")).isEqualTo("AUTH_REFUSED");
    }

    @Test
    @Tag("negative")
    @DisplayName("the socket never answered: the failure is DATABASE_UNREACHABLE and no privilege probe runs")
    void anUnreachableTargetNeverPrivilegeProbes() throws Exception {
        DbBrokerProbe dead = (host, port, timeout) -> { throw new IOException("not answering"); };
        DbTestConnection connection = new DbTestConnection(guard(PORT), dead, privileges, List.of(PORT),
                Duration.ofSeconds(5));

        var answer = connection.test(systemId, SETTINGS);

        assertThat(answer.get("result")).isEqualTo("FAILED");
        assertThat(answer.get("reason")).isEqualTo("DATABASE_UNREACHABLE");
        verify(privileges, never()).probe(any(), any(), any(), any());
    }

    @Test
    @DisplayName("no privilege probe wired: the old socket-only answer shape, no ports enforced")
    void noPrivilegeProbeIsTheOldSocketOnlyAnswer() {
        DbTestConnection connection = new DbTestConnection(guard(PORT), socket, Duration.ofSeconds(5));

        var answer = connection.test(systemId, SETTINGS);

        assertThat(answer.get("result")).isEqualTo("SUCCESS");
        assertThat(answer.get("detail")).isNull();
        assertThat(answer.get("message")).isEqualTo("the target database accepted a connection");
    }
}