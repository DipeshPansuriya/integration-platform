package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
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
 * T203 (karan T189-S1) against a real PostgreSQL: the approve-time width verdict refuses a partner account that is
 * wide through a predefined role (direct, inherited or NOINHERIT), a role attribute, a CREATE right on the database or
 * on a reachable schema, or a privilege beyond INSERT/SELECT on another table of the approved schema. ONE account per
 * case, each holding INSERT + SELECT on the approved table so the added token is the only reason; a narrow account
 * (positive control) must still be approved. Passwords are container-local fixed strings (R07).
 */
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DbAccountWidthVerdictContainerTest {

    static final PostgreSQLContainer TARGET = DbTargetContainers.target();
    static final String PASSWORD = "width-" + UUID.randomUUID().toString().substring(0, 8);
    static final String TABLE = "orders_in";

    private final UUID systemId = UUID.randomUUID();

    private int port() {
        return TARGET.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
    }

    private DestinationGuard guard() {
        try {
            return new DestinationGuard(AllowList.of(List.of(TARGET.getHost())),
                    name -> List.of(InetAddress.getLoopbackAddress()), List.of(TARGET.getHost()), List.of(port()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Connection owner(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + TARGET.getHost() + ":" + port() + "/" + database,
                TARGET.getUsername(), TARGET.getPassword());
    }

    private void owner(String database, String... statements) throws SQLException {
        try (Connection c = owner(database); Statement sql = c.createStatement()) {
            for (String statement : statements) {
                sql.execute(statement);
            }
        }
    }

    /** A login account holding exactly INSERT + SELECT on the approved table of the given database. */
    private void account(String database, String name, String... extra) throws SQLException {
        owner(database, "CREATE ROLE " + name + " LOGIN PASSWORD '" + PASSWORD + "'",
                "GRANT SELECT, INSERT ON " + TABLE + " TO " + name);
        owner(database, extra);
    }

    private DbTestConnection connection() {
        return new DbTestConnection(guard(), (host, p, timeout) -> { },
                new JdbcDbPrivilegeProbe(system -> Optional.of(PASSWORD.toCharArray()), "disable"), List.of(port()),
                Duration.ofSeconds(5));
    }

    private String settingsJson(String database, String user) {
        return "{\"host\":\"" + TARGET.getHost() + "\",\"port\":" + port() + ",\"database\":\"" + database
                + "\",\"user\":\"" + user + "\",\"table\":\"" + TABLE + "\"}";
    }

    private List<String> tokens(String database, String user) throws Exception {
        InetAddress pinned = guard().check(URI.create("http://" + TARGET.getHost() + ":" + port())).pinnedAddress();
        return new JdbcDbPrivilegeProbe(system -> Optional.of(PASSWORD.toCharArray()), "disable").probe(systemId,
                new DbSettings(TARGET.getHost(), port(), database, user, TABLE), pinned, Duration.ofSeconds(5));
    }

    private Optional<String> verdict(String database, String user) {
        return connection().accountWidthRefusal(systemId, settingsJson(database, user));
    }

    /** The account carries exactly the INSERT + SELECT of the approved table plus this one token, and is refused. */
    private void assertRefusedFor(String user, String token) throws Exception {
        assertRefusedFor(TARGET.getDatabaseName(), user, token);
        assertThat(tokens(TARGET.getDatabaseName(), user)).as(user).containsExactlyInAnyOrder("INSERT", "SELECT", token);
    }

    private void assertRefusedFor(String database, String user, String token) {
        assertThat(verdict(database, user)).as(user).get().asString().startsWith("DB_ACCOUNT_TOO_WIDE").contains(token);
    }

    @BeforeAll
    void start() throws SQLException {
        TARGET.start();
        owner(TARGET.getDatabaseName(), "CREATE TABLE " + TABLE + " (id bigserial PRIMARY KEY, awb text NOT NULL)",
                "CREATE TABLE users (id bigserial PRIMARY KEY)",
                "CREATE TABLE shipments_in (id bigserial PRIMARY KEY)",
                "CREATE VIEW users_view AS SELECT id FROM users");
    }

    @AfterAll
    void stop() {
        if (TARGET != null) {
            TARGET.stop();
        }
    }

    @Test
    @DisplayName("positive control: a narrow account (INSERT + SELECT on the table only) is still approved")
    void aNarrowAccountIsStillApproved() throws Exception {
        account(TARGET.getDatabaseName(), "narrow");

        assertThat(tokens(TARGET.getDatabaseName(), "narrow")).containsExactly("INSERT", "SELECT");
        assertThat(verdict(TARGET.getDatabaseName(), "narrow")).isEmpty();
    }

    @Test
    @DisplayName("each dangerous predefined role, granted directly, refuses the account")
    void aDirectPredefinedRoleMemberIsRefused() throws Exception {
        String db = TARGET.getDatabaseName();
        for (String role : List.of("pg_execute_server_program", "pg_write_server_files", "pg_read_server_files",
                "pg_signal_backend", "pg_write_all_data", "pg_read_all_data")) {
            String user = "m_" + role.substring(3);
            account(db, user, "GRANT " + role + " TO " + user);
            assertRefusedFor(user, role.toUpperCase());
        }
    }

    @Test
    @DisplayName("membership through a group role (inherited) refuses the account")
    void anInheritedPredefinedRoleIsRefused() throws Exception {
        String db = TARGET.getDatabaseName();
        owner(db, "CREATE ROLE grp_outer NOLOGIN", "CREATE ROLE grp_inner NOLOGIN",
                "GRANT pg_execute_server_program TO grp_inner", "GRANT grp_inner TO grp_outer");
        account(db, "chained", "GRANT grp_outer TO chained");

        assertRefusedFor("chained", "PG_EXECUTE_SERVER_PROGRAM");
    }

    @Test
    @DisplayName("membership granted WITHOUT inheritance still refuses (the account can SET ROLE into it)")
    void aNoInheritMembershipIsRefused() throws Exception {
        String db = TARGET.getDatabaseName();
        account(db, "noinh", "GRANT pg_read_server_files TO noinh WITH INHERIT FALSE, SET TRUE");

        assertRefusedFor("noinh", "PG_READ_SERVER_FILES");
    }

    @Test
    @DisplayName("pg_database_owner: the owner of the target database is refused")
    void theDatabaseOwnerIsRefused() throws Exception {
        owner(TARGET.getDatabaseName(), "CREATE ROLE dbowner LOGIN PASSWORD '" + PASSWORD + "'",
                "CREATE DATABASE ownerdb OWNER dbowner");
        owner("ownerdb", "CREATE TABLE " + TABLE + " (id bigserial PRIMARY KEY, awb text NOT NULL)",
                "GRANT SELECT, INSERT ON " + TABLE + " TO dbowner");

        assertThat(tokens("ownerdb", "dbowner")).contains("PG_DATABASE_OWNER");
        assertRefusedFor("ownerdb", "dbowner", "PG_DATABASE_OWNER");
    }

    @Test
    @DisplayName("role attributes CREATEROLE, CREATEDB and REPLICATION each refuse the account")
    void roleAttributesAreRefused() throws Exception {
        String db = TARGET.getDatabaseName();
        account(db, "a_createrole", "ALTER ROLE a_createrole CREATEROLE");
        account(db, "a_createdb", "ALTER ROLE a_createdb CREATEDB");
        account(db, "a_replication", "ALTER ROLE a_replication REPLICATION");

        assertRefusedFor("a_createrole", "CREATEROLE");
        assertRefusedFor("a_createdb", "CREATEDB");
        assertRefusedFor("a_replication", "REPLICATION");
    }

    @Test
    @DisplayName("CREATE on the database refuses the account")
    void databaseCreateIsRefused() throws Exception {
        String db = TARGET.getDatabaseName();
        account(db, "db_create", "GRANT CREATE ON DATABASE " + db + " TO db_create");

        assertRefusedFor("db_create", "DATABASE_CREATE");
    }

    @Test
    @DisplayName("CREATE on a reachable schema (not the approved one) refuses; CREATE without USAGE reaches nothing")
    void createOnAReachableSchemaIsRefused() throws Exception {
        String db = TARGET.getDatabaseName();
        owner(db, "CREATE SCHEMA reach", "CREATE SCHEMA unreachable");
        account(db, "sch_create", "GRANT USAGE, CREATE ON SCHEMA reach TO sch_create");
        account(db, "sch_nousage", "GRANT CREATE ON SCHEMA unreachable TO sch_nousage");

        assertRefusedFor("sch_create", "ANY_SCHEMA_CREATE");
        assertThat(verdict(db, "sch_nousage")).isEmpty(); // not reachable: no USAGE, so no object can be made there
    }

    @Test
    @DisplayName("a privilege beyond INSERT/SELECT on ANOTHER table of the approved schema refuses (table, column, group)")
    void aPrivilegeOnAnotherTableIsRefused() throws Exception {
        String db = TARGET.getDatabaseName();
        owner(db, "CREATE ROLE other_grp NOLOGIN", "GRANT DELETE ON users TO other_grp");
        account(db, "o_update", "GRANT UPDATE ON users TO o_update");
        account(db, "o_colupd", "GRANT UPDATE (id) ON users TO o_colupd");
        account(db, "o_truncate", "GRANT TRUNCATE ON users TO o_truncate");
        account(db, "o_group", "GRANT other_grp TO o_group");
        account(db, "o_view", "GRANT UPDATE ON users_view TO o_view");

        for (String user : List.of("o_update", "o_colupd", "o_truncate", "o_group", "o_view")) {
            assertRefusedFor(user, "OTHER_TABLE_PRIVILEGES");
        }
    }

    @Test
    @DisplayName("INSERT/SELECT on a second table, SELECT on a view, and tables of another schema do not refuse")
    void siblingTablesWithTheApprovedRightsStayNarrow() throws Exception {
        String db = TARGET.getDatabaseName();
        owner(db, "CREATE SCHEMA elsewhere", "CREATE TABLE elsewhere.audit (id int)");
        account(db, "siblings", "GRANT SELECT, INSERT ON shipments_in TO siblings",
                "GRANT SELECT ON users_view TO siblings", "GRANT USAGE ON SCHEMA elsewhere TO siblings",
                "GRANT UPDATE ON elsewhere.audit TO siblings");

        assertThat(tokens(db, "siblings")).containsExactly("INSERT", "SELECT");
        assertThat(verdict(db, "siblings")).isEmpty(); // documented scope: other schemas' tables are not walked
    }

    @Test
    @DisplayName("cost: a narrow account in a schema of 3000 relations is read well inside the 5 s probe timeout")
    void theOtherTableReadIsBoundedInALargeSchema() throws Exception {
        owner(TARGET.getDatabaseName(), "CREATE DATABASE bigdb");
        owner("bigdb", "CREATE TABLE " + TABLE + " (id bigserial PRIMARY KEY, awb text NOT NULL)",
                "DO $$ BEGIN FOR i IN 1..3000 LOOP EXECUTE format('CREATE TABLE t_%s (id int)', i); END LOOP; END $$");
        account("bigdb", "big_narrow");

        long start = System.nanoTime();
        List<String> granted = tokens("bigdb", "big_narrow");
        long millis = (System.nanoTime() - start) / 1_000_000;

        System.out.println("T203 cost: 3000 other tables, whole probe incl. connect = " + millis + " ms");
        assertThat(granted).containsExactly("INSERT", "SELECT");
        assertThat(millis).isLessThan(5_000);
    }
}
