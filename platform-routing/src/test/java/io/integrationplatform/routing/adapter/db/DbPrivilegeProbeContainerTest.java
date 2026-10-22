package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E16 F3 (design note task 11, Karan change 4) against a real PostgreSQL as the DB target: the probe answers the
 * account's REAL grants on the exact target table (information_schema.table_privileges), reports none for an
 * ungranted table, maps a refused account to AUTH_REFUSED and a closed port to a database that did not answer, and
 * wipes the credential copy when the probe is over. The credential seam is a test fixture holding the SAME
 * container-local fixed password (R07: never a real credential) - the vault composition is the unit seam.
 *
 * <p>Authored by arjun-fullstack while the Docker lock was held elsewhere: NOT RUN when committed (the
 * container-proof lane picks it up with the migration suite).
 */
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DbPrivilegeProbeContainerTest {

    static final PostgreSQLContainer TARGET = DbTargetContainers.target();
    static final String RESTRICTED_PASSWORD = "restricted-" + UUID.randomUUID().toString().substring(0, 8);

    private final UUID systemId = UUID.randomUUID();

    /** The vault seam hands the probe the ORIGINAL array the caller holds (like the send path's open/close contract):
     * only then can a wipe test assert on the caller's own bytes going cold. */
    private DbSystemCredentials credentials(char[] password) {
        return system -> Optional.of(password);
    }

    private JdbcDbPrivilegeProbe probe(char[] password) {
        // the container speaks no TLS: the operator default's stronger modes are the production wiring (DbTargetPostgresTest precedent)
        return new JdbcDbPrivilegeProbe(credentials(password), "disable");
    }

    private InetAddress pinnedAddress() {
        try {
            DestinationGuard guard = guard();
            return guard.check(URI.create("http://" + TARGET.getHost() + ":"
                    + TARGET.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))).pinnedAddress();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private DestinationGuard guard() {
        try {
            return new DestinationGuard(AllowList.of(List.of(TARGET.getHost())),
                    name -> List.of(InetAddress.getLoopbackAddress()), List.of(TARGET.getHost()),
                    List.of(TARGET.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private DbSettings settings(String table, int port) {
        return new DbSettings(TARGET.getHost(), port, TARGET.getDatabaseName(), "restricted", table);
    }

    @BeforeAll
    void start() throws SQLException {
        TARGET.start();
        try (Connection owner = owner(); Statement sql = owner.createStatement()) {
            sql.execute("CREATE ROLE restricted LOGIN PASSWORD '" + RESTRICTED_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE");
            sql.execute("CREATE TABLE orders_in (id bigserial PRIMARY KEY, awb text NOT NULL)");
            sql.execute("CREATE TABLE users (id bigserial PRIMARY KEY)");
            sql.execute("GRANT SELECT, INSERT ON orders_in TO restricted"); // nothing granted on users
        }
    }

    @AfterAll
    void stop() {
        if (TARGET != null) {
            TARGET.stop();
        }
    }

    private Connection owner() throws SQLException {
        return DriverManager.getConnection(TARGET.getJdbcUrl(), TARGET.getUsername(), TARGET.getPassword());
    }

    @Test
    @DisplayName("the probe reports the account's real grants on the exact target table")
    void theProbeReportsTheRealGrantsOnTheTargetTable() throws Exception {
        char[] password = RESTRICTED_PASSWORD.toCharArray();

        List<String> grants = probe(password).probe(systemId, settings("orders_in",
                TARGET.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)), pinnedAddress(), Duration.ofSeconds(5));

        assertThat(grants).containsExactly("INSERT", "SELECT");
        assertThat(password).containsOnly('\0'); // the wipe survives even a successful probe
    }

    @Test
    @DisplayName("an ungranted table reports no privileges - the truth, never an optimistic list")
    void anUngrantedTableReportsNoPrivileges() throws Exception {
        List<String> grants = probe(RESTRICTED_PASSWORD.toCharArray()).probe(systemId, settings("users",
                TARGET.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)), pinnedAddress(), Duration.ofSeconds(5));

        assertThat(grants).isEmpty();
    }

    @Test
    @DisplayName("a wrong password is AUTH_REFUSED, not a success or a transport crash")
    void aWrongPasswordIsAuthRefused() {
        assertThatThrownBy(() -> probe("not-the-password".toCharArray()).probe(systemId, settings("orders_in",
                TARGET.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)), pinnedAddress(), Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(DbPrivilegeProbe.ProbeRefused.class,
                        e -> assertThat(e.reason).isEqualTo("AUTH_REFUSED"));
    }

    @Test
    @DisplayName("a closed port is a database that did not answer")
    void aClosedPortIsUnreachable() throws Exception {
        // a port nothing listens on right now: bind an ephemeral one and release it (never mappedPort + n, which can
        // pass 65535 and trip the DbSettings validation before any dial - the DbTargetPostgresTest closedPort precedent)
        int closed = DbTargetPostgresTest.closedPort();
        assertThatThrownBy(() -> probe(RESTRICTED_PASSWORD.toCharArray()).probe(systemId, settings("orders_in",
                closed), pinnedAddress(), Duration.ofSeconds(5)))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("through DbTestConnection the credentialed answer carries the grants in detail")
    void theTestConnectionAnswerCarriesTheGrants() {
        DbTestConnection connection = new DbTestConnection(guard(), (host, port, timeout) -> { },
                probe(RESTRICTED_PASSWORD.toCharArray()), List.of(TARGET.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)),
                Duration.ofSeconds(5));
        String json = "{\"host\":\"" + TARGET.getHost() + "\",\"port\":"
                + TARGET.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + ",\"database\":\""
                + TARGET.getDatabaseName() + "\",\"user\":\"restricted\",\"table\":\"orders_in\"}";

        java.util.Map<String, Object> answer = connection.test(systemId, json);

        assertThat(answer.get("result")).isEqualTo("SUCCESS");
        assertThat((String) answer.get("detail")).contains("orders_in").contains("INSERT").contains("SELECT");
    }
}