package io.integrationplatform.app.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.TestImages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Row 542 (Karan, typed-code sink trace): V2_4_1470 adds the charset CHECK NOT VALID and V2_4_1480 the
 * case-insensitive unique index on {@code public.interface}. The database is migrated to 2.4.1460, a legacy row that
 * breaks the pattern is stored, Flyway migrates on (NOT VALID scans nothing, so it cannot fail on stored data), and
 * the tests assert that new rows are refused, the legacy row is left alone, and the constraint stays unvalidated.
 */
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InterfaceCodeConstraintContainerTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private PostgreSQLContainer postgres;

    @BeforeAll
    void migrateWithALegacyRow() throws Exception {
        postgres = new PostgreSQLContainer(TestImages.POSTGRES);
        postgres.start();
        flyway().target("2.4.1460").load().migrate();
        try (Connection c = connect()) {
            tenant(c, tenantA, "code-a");
            tenant(c, tenantB, "code-b");
            insert(c, tenantA, "legacy code with spaces"); // stored before the CHECK existed
        }
        flyway().load().migrate(); // must not fail on the legacy row
    }

    @AfterAll
    void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("F3: the CHECK is present and deliberately NOT validated; the legacy row is untouched")
    void theCheckIsNotValidAndTheLegacyRowSurvives() throws SQLException {
        assertThat(scalar("select convalidated::text from pg_constraint where conname = 'interface_code_charset'"))
                .isEqualTo("false");
        assertThat(scalar("select count(*)::text from public.interface where code = 'legacy code with spaces'"))
                .isEqualTo("1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", ".lead", "..", ".", "a/b", "a?b", "a\"b", "ok\n", "ok\r\n", "a\nb", "",
            "é", "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijkl"})
    @DisplayName("F3: a new row whose code breaks the pattern is refused by the database (check_violation)")
    void aBadCodeIsRefused(String bad) throws SQLException {
        try (Connection c = connect()) {
            assertThatThrownBy(() -> insert(c, tenantB, bad)).isInstanceOfSatisfying(SQLException.class,
                    e -> assertThat(e.getSQLState()).isEqualTo(CHECK_VIOLATION));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.", "CON", "nul.txt", "a..b", "7", "CTS_TO_ERP_INVOICES", "flow-5b6f2a8e-0000-4000-8000-000000000000"})
    @DisplayName("F3: the codes the service accepts are accepted by the database too")
    void aGoodCodeIsAccepted(String good) throws SQLException {
        try (Connection c = connect()) {
            insert(c, tenantB, good);
        }
        assertThat(scalar("select count(*)::text from public.interface where tenant_id = '" + tenantB
                + "' and code = '" + good + "'")).isEqualTo("1");
    }

    @Test
    @DisplayName("F1: a case variant in the same tenant is refused (unique violation); another tenant may reuse it")
    void aCaseVariantIsRefusedWithinOneTenantOnly() throws SQLException {
        try (Connection c = connect()) {
            insert(c, tenantA, "Orders");
            assertThatThrownBy(() -> insert(c, tenantA, "orders")).isInstanceOfSatisfying(SQLException.class, e -> {
                assertThat(e.getSQLState()).isEqualTo(UNIQUE_VIOLATION);
                assertThat(e.getMessage()).contains("interface_code_ci_unique");
            });
            insert(c, tenantB, "orders");
        }
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        c.setAutoCommit(true);
        return c;
    }

    private String scalar(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void tenant(Connection c, UUID id, String code) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
            p.setString(1, id.toString());
            p.setString(2, code + "-" + id.toString().substring(0, 8));
            p.executeUpdate();
        }
    }

    private static void insert(Connection c, UUID tenant, String code) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO public.interface (id, tenant_id, code, name) VALUES (gen_random_uuid(), ?::uuid, ?, 'n')")) {
            p.setString(1, tenant.toString());
            p.setString(2, code);
            p.executeUpdate();
        }
    }
}
