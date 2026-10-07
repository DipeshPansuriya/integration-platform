package io.integrationplatform.tenancy.internal.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E4 (T-097), ADR-007 Decision 1: V2_4_1 creates the Spring Session JDBC tables the console's server sessions live in
 * - in their own schema {@code console_session}, written by the restricted application login only, never by PUBLIC,
 * and with no row-level security (a session row is not tenant data; it is found by its random id only). Container
 * group (R03).
 */
@Tag("container")
class ConsoleSessionMigrationContainerTest {

    private RlsTestDatabase db;

    @BeforeEach
    void database() {
        db = RlsTestDatabase.get();
    }

    @Test
    void theApplicationLoginStoresReadsAndDeletesASessionAndItsAttributes() throws SQLException {
        String primaryId = UUID.randomUUID().toString();
        try (Connection app = db.appConnection()) {
            try (PreparedStatement insert = app.prepareStatement("insert into console_session.spring_session"
                    + " (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time,"
                    + " principal_name) values (?, ?, 1, 1, 1800, 1801, 'subject')")) {
                insert.setString(1, primaryId);
                insert.setString(2, UUID.randomUUID().toString());
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement attribute = app.prepareStatement("insert into console_session.spring_session_attributes"
                    + " (session_primary_id, attribute_name, attribute_bytes) values (?, 'a', ?)")) {
                attribute.setString(1, primaryId);
                attribute.setBytes(2, new byte[] {1, 2, 3});
                assertThat(attribute.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement update = app.prepareStatement(
                    "update console_session.spring_session set last_access_time = 2 where primary_id = ?")) {
                update.setString(1, primaryId);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement delete = app.prepareStatement(
                    "delete from console_session.spring_session where primary_id = ?")) {
                delete.setString(1, primaryId);
                assertThat(delete.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement left = app.prepareStatement(
                    "select count(*) from console_session.spring_session_attributes where session_primary_id = ?")) {
                left.setString(1, primaryId);
                try (ResultSet rs = left.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).as("attributes cascade with their session").isZero();
                }
            }
        }
    }

    @Test
    void aLoginOutsideTheApplicationRoleCannotReadSessions() throws SQLException {
        try (Connection outsider = db.outsiderConnection(); Statement sql = outsider.createStatement()) {
            assertThatThrownBy(() -> sql.executeQuery("select count(*) from console_session.spring_session"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void theSessionTablesHaveNoRowLevelSecurityAndOnlyTheApplicationRoleIsGranted() throws SQLException {
        try (Connection admin = db.superuserConnection(); Statement sql = admin.createStatement()) {
            assertThat(text(sql, "select string_agg(relname || ':' || relrowsecurity, ',' order by relname)"
                    + " from pg_class where relnamespace = 'console_session'::regnamespace and relkind = 'r'"))
                    .isEqualTo("spring_session:false,spring_session_attributes:false");
            assertThat(text(sql, "select string_agg(distinct r.rolname, ',' order by r.rolname)"
                    + " from pg_class c, aclexplode(c.relacl) a join pg_roles r on r.oid = a.grantee"
                    + " where c.relnamespace = 'console_session'::regnamespace and c.relkind = 'r'"
                    + " and r.rolname <> '" + RlsTestDatabase.OWNER + "'"))
                    .isEqualTo(RlsTestDatabase.APP_ROLE);
            assertThat(text(sql, "select count(*) from pg_class c, aclexplode(c.relacl) a"
                    + " where c.relnamespace = 'console_session'::regnamespace and a.grantee = 0"))
                    .as("PUBLIC holds nothing").isEqualTo("0");
            assertThat(text(sql, "select string_agg(a.privilege_type, ',' order by a.privilege_type)"
                    + " from pg_class c, aclexplode(c.relacl) a"
                    + " where c.relname = 'spring_session' and c.relnamespace = 'console_session'::regnamespace"
                    + " and a.grantee = (select oid from pg_roles where rolname = '" + RlsTestDatabase.APP_ROLE + "')"))
                    .isEqualTo("DELETE,INSERT,SELECT,UPDATE");
        }
    }

    private static String text(Statement sql, String query) throws SQLException {
        try (ResultSet rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
