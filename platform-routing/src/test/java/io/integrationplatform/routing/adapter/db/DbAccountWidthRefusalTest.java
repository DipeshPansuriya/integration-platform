package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** T178a, no containers: the approve-time width verdict of {@link DbTestConnection} - fail closed on every doubt. */
class DbAccountWidthRefusalTest {

    private static final String HOST = "db.partner.example";
    private static final String SETTINGS = "{\"host\":\"" + HOST + "\",\"port\":5432,\"database\":\"orders\","
            + "\"user\":\"restricted\",\"table\":\"orders_in\"}";

    private final DbPrivilegeProbe privileges = mock(DbPrivilegeProbe.class);
    private final UUID systemId = UUID.randomUUID();

    private DbTestConnection connection(DbPrivilegeProbe probe) throws Exception {
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of(HOST)),
                name -> List.of(InetAddress.getLoopbackAddress()), List.of(HOST), List.of(5432));
        return probe == null ? new DbTestConnection(guard, (h, p, t) -> { }, Duration.ofSeconds(5))
                : new DbTestConnection(guard, (h, p, t) -> { }, probe, List.of(5432), Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("INSERT and SELECT only: no refusal")
    void aNarrowAccountIsAccepted() throws Exception {
        when(privileges.probe(eq(systemId), any(DbSettings.class), any(), any(Duration.class)))
                .thenReturn(List.of("INSERT", "SELECT"));
        assertThat(connection(privileges).accountWidthRefusal(systemId, SETTINGS)).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Superuser, CREATE, pg_write_all_data, TRUNCATE, BYPASSRLS (anything beyond INSERT and SELECT) refuse, naming them")
    void aWideAccountIsRefused() throws Exception {
        when(privileges.probe(eq(systemId), any(DbSettings.class), any(), any(Duration.class)))
                .thenReturn(List.of("BYPASSRLS", "INSERT", "PG_WRITE_ALL_DATA", "SCHEMA_CREATE", "SELECT", "SUPERUSER",
                        "TRUNCATE"));
        assertThat(connection(privileges).accountWidthRefusal(systemId, SETTINGS)).get().asString()
                .startsWith("DB_ACCOUNT_TOO_WIDE").contains("SUPERUSER").contains("SCHEMA_CREATE")
                .contains("PG_WRITE_ALL_DATA").contains("TRUNCATE").contains("BYPASSRLS").doesNotContain("INSERT,");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Fail closed: no probe wired, a refused probe, an unreachable database, a missing table, bad settings")
    void everyDoubtRefuses() throws Exception {
        assertThat(connection(null).accountWidthRefusal(systemId, SETTINGS)).get().asString()
                .startsWith("DB_ACCOUNT_UNVERIFIED");
        when(privileges.probe(eq(systemId), any(DbSettings.class), any(), any(Duration.class)))
                .thenThrow(new DbPrivilegeProbe.ProbeRefused("NO_CREDENTIAL", "no password in the vault"));
        assertThat(connection(privileges).accountWidthRefusal(systemId, SETTINGS)).get().asString()
                .startsWith("DB_ACCOUNT_UNVERIFIED").contains("NO_CREDENTIAL");
        when(privileges.probe(eq(systemId), any(DbSettings.class), any(), any(Duration.class)))
                .thenThrow(new java.io.IOException("down"));
        assertThat(connection(privileges).accountWidthRefusal(systemId, SETTINGS)).get().asString()
                .startsWith("DB_ACCOUNT_UNVERIFIED");
        assertThat(connection(privileges).accountWidthRefusal(systemId, SETTINGS.replace(",\"table\":\"orders_in\"", "")))
                .get().asString().startsWith("DB_ACCOUNT_UNVERIFIED");
        assertThat(connection(privileges).accountWidthRefusal(systemId, "{}")).get().asString()
                .startsWith("DB_ACCOUNT_UNVERIFIED");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("T203: each added token alone refuses the account as TOO_WIDE and names it")
    void eachAccountLevelTokenAloneRefuses() throws Exception {
        for (String token : List.of("PG_EXECUTE_SERVER_PROGRAM", "PG_WRITE_SERVER_FILES", "PG_READ_SERVER_FILES",
                "PG_SIGNAL_BACKEND", "PG_DATABASE_OWNER", "PG_READ_ALL_DATA", "CREATEROLE", "CREATEDB", "REPLICATION",
                "DATABASE_CREATE", "ANY_SCHEMA_CREATE", "OTHER_TABLE_PRIVILEGES")) {
            when(privileges.probe(eq(systemId), any(DbSettings.class), any(), any(Duration.class)))
                    .thenReturn(List.of("INSERT", token, "SELECT"));
            assertThat(connection(privileges).accountWidthRefusal(systemId, SETTINGS)).as(token).get().asString()
                    .startsWith("DB_ACCOUNT_TOO_WIDE").contains(token).doesNotContain("INSERT,");
        }
    }
}
