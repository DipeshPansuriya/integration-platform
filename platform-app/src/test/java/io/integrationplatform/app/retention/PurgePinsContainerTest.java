package io.integrationplatform.app.retention;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.TestImages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Pins two facts the payload purge and the dormant allow-list rest on (ADR-012 amendment notes, PR 56). Test-only; both
 * run the real Flyway tree, never a schema the test builds itself.
 *
 * <ul>
 *   <li>V2_4_1440 revokes INSERT and DELETE on {@code operator_allowed_host} from the application role. The test first
 *       migrates to 2.4.1430, where both are still held, so the check is proven able to fail before it is asserted green.
 *   <li>The Flyway-created day partitions of message / payload / delivery carry no row-level security of their own: the
 *       purge guard counts as the table owner, and an RLS-enabled partition would make that count read 0 and fail open.
 * </ul>
 */
@Tag("container")
@Testcontainers
class PurgePinsContainerTest {

    private static final String APP_ROLE = "ipaas_app";
    private static final String HOSTS = "public.operator_allowed_host";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Test
    @Tag("negative")
    void migrationV2_4_1440RevokesInsertAndDeleteOnOperatorAllowedHostFromTheAppRole() throws SQLException {
        String url = newDatabase("grant_pin");

        flyway(url).target("2.4.1430").load().migrate();
        assertThat(privilege(url, "INSERT")).as("before 1440 the app role still holds INSERT").isTrue();
        assertThat(privilege(url, "DELETE")).as("before 1440 the app role still holds DELETE").isTrue();

        flyway(url).load().migrate();
        assertThat(privilege(url, "INSERT")).as("after 1440: INSERT revoked").isFalse();
        assertThat(privilege(url, "DELETE")).as("after 1440: DELETE revoked").isFalse();
        assertThat(privilege(url, "SELECT")).as("SELECT may stay on the dormant table").isTrue();
    }

    @Test
    @Tag("negative")
    void theProductionDayPartitionsOfMessagePayloadAndDeliveryHaveNoRowLevelSecurityOfTheirOwn() throws SQLException {
        String url = newDatabase("partition_rls_pin");
        flyway(url).load().migrate();
        String today = LocalDate.now(ZoneOffset.UTC).format(DAY);

        for (String parent : new String[] {"message", "payload", "delivery"}) {
            List<String> partitions = partitionsWithRls(url, parent, false);
            assertThat(partitions).as("Flyway-created %s partitions", parent).contains(parent + "_" + today);
            assertThat(partitionsWithRls(url, parent, true))
                    .as("%s day partitions with relrowsecurity: the purge guard would count 0 and fail open", parent)
                    .isEmpty();
        }

        // Control: the same query does see a partition once RLS is switched on, so the empty result above is a real pass.
        try (Connection c = connect(url); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE public.message_" + today + " ENABLE ROW LEVEL SECURITY");
        }
        assertThat(partitionsWithRls(url, "message", true)).containsExactly("message_" + today);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static FluentConfiguration flyway(String url) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
    }

    private static String newDatabase(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static boolean privilege(String url, String privilege) throws SQLException {
        try (Connection c = connect(url);
                ResultSet rs = c.createStatement().executeQuery(
                        "select has_table_privilege('" + APP_ROLE + "', '" + HOSTS + "', '" + privilege + "')")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    /** Day partitions of {@code parent} (all, or only those with relrowsecurity set). */
    private static List<String> partitionsWithRls(String url, String parent, boolean onlyRls) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = connect(url);
                ResultSet rs = c.createStatement().executeQuery("select c.relname from pg_inherits i"
                        + " join pg_class c on c.oid = i.inhrelid"
                        + " where i.inhparent = 'public." + parent + "'::regclass"
                        + (onlyRls ? " and c.relrowsecurity" : "") + " order by c.relname")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }
}
