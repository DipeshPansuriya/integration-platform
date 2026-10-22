package io.integrationplatform.config.e5.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E16 F3 (design note tasks 2-5; Dipesh D2 and D4; Karan's changes 1, 2, 5, 6) against a real PostgreSQL: the migration
 * V2_4_1330 - the table and its CHECKs, the partial unique index, FORCE row-level security, no UPDATE or DELETE grant
 * for the app role, the backfill of existing {@code approvedTables} entries as PENDING_APPROVAL with the key stripped
 * from the settings, and the SECURITY DEFINER decision function (self-approval refused, state machine, target
 * fingerprint, actor from the session stamp, pinned search_path).
 *
 * <p>Authored by arjun-fullstack while the Docker lock was held elsewhere: NOT RUN when committed. The suite builds its
 * own container: migrate to 2.4.1320, seed the pre-existing data, then migrate on to 2.4.1330.
 */
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovedTableMigrationContainerTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PostgreSQLContainer postgres;
    private String appUser;
    private String appPassword;
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID dbSystemA = UUID.randomUUID();
    private final UUID apiSystemA = UUID.randomUUID();
    private final UUID dbSystemB = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();

    @BeforeAll
    void migrateSeedAndMigrateOn() throws Exception {
        postgres = new PostgreSQLContainer("postgres:17.11-alpine");
        postgres.start();
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        List<String> locations = new ArrayList<>();
        for (Path folder : List.of(basedir.getParent().resolve("platform-tenancy/src/main/resources/db/migration"),
                basedir.resolve("src/main/resources/db/migration"))) {
            if (Files.isDirectory(folder)) {
                locations.add("filesystem:" + folder.toString().replace('\\', '/'));
            }
        }
        Flyway before = flyway(locations).target("2.4.1320").load();
        before.migrate();

        // the data that exists BEFORE the migration: self-asserted approvedTables in the settings JSON
        try (Connection owner = owner()) {
            tenant(owner, tenantA, "approved-a");
            tenant(owner, tenantB, "approved-b");
            system(owner, tenantA, dbSystemA, "DB", "{\"host\":\"db.a.example\",\"port\":5432,\"database\":\"orders\","
                    + "\"user\":\"ipass_orders\",\"table\":\"orders_in\","
                    + "\"approvedTables\":[\"orders_in\",\"Orders_IN\",\"users\",\"bad name\"]}");
            system(owner, tenantA, apiSystemA, "API", "{\"baseUrl\":\"https://api.example/v1\"}");
            system(owner, tenantB, dbSystemB, "DB", "{\"host\":\"db.b.example\",\"port\":5432,\"database\":\"b\","
                    + "\"user\":\"ipass_b\",\"approvedTables\":[\"b_in\"]}");
        }

        flyway(locations).load().migrate();

        appUser = "app_login_" + Long.toUnsignedString(RANDOM.nextLong(), 36);
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        appPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        try (Connection owner = owner(); Statement sql = owner.createStatement()) {
            sql.execute("CREATE ROLE \"" + appUser + "\" LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + appPassword + "'");
            sql.execute("GRANT ipaas_app TO \"" + appUser + "\"");
        }
    }

    @AfterAll
    void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(List<String> locations) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new)).baselineOnMigrate(true).baselineVersion("0");
    }

    private Connection owner() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** The app login in one transaction with the tenant (and optionally the acting user) stamped like the platform. */
    private Connection app(UUID tenant, UUID actor) throws SQLException {
        Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), appUser, appPassword);
        connection.setAutoCommit(false);
        try (PreparedStatement stamp = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            stamp.setString(1, tenant.toString());
            stamp.execute();
        }
        if (actor != null) {
            try (PreparedStatement stamp = connection.prepareStatement("select set_config('app.actor_id', ?, true)")) {
                stamp.setString(1, actor.toString());
                stamp.execute();
            }
        }
        return connection;
    }

    private static void tenant(Connection owner, UUID id, String code) throws SQLException {
        try (PreparedStatement sql = owner.prepareStatement(
                "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
            sql.setString(1, id.toString());
            sql.setString(2, code + "-" + id.toString().substring(0, 8));
            sql.executeUpdate();
        }
    }

    private static void system(Connection owner, UUID tenant, UUID id, String type, String settings)
            throws SQLException {
        try (PreparedStatement sql = owner.prepareStatement("INSERT INTO public.system (id, tenant_id, code, name,"
                + " direction, type, settings) VALUES (?::uuid, ?::uuid, ?, 'n', 'TARGET', ?, ?::jsonb)")) {
            sql.setString(1, id.toString());
            sql.setString(2, tenant.toString());
            sql.setString(3, "sys-" + id.toString().substring(0, 8));
            sql.setString(4, type);
            sql.setString(5, settings);
            sql.executeUpdate();
        }
    }

    private String scalar(String sql, String... values) throws SQLException {
        try (Connection owner = owner(); PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** A request as the app would insert it (tenant stamped); answers the row id. */
    private String request(UUID tenant, UUID system, String table, UUID by) throws SQLException {
        try (Connection app = app(tenant, null);
                PreparedStatement insert = app.prepareStatement("insert into public.db_target_approved_table"
                        + " (tenant_id, system_id, table_name, status, requested_by, target_hash)"
                        + " values (current_setting('app.tenant_id')::uuid, ?::uuid, ?, 'PENDING_APPROVAL', ?,"
                        + " (select public.db_target_fingerprint(s.settings) from public.system s where s.id = ?::uuid))"
                        + " returning id::text")) {
            insert.setString(1, system.toString());
            insert.setString(2, table);
            insert.setString(3, by.toString());
            insert.setString(4, system.toString());
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                String id = rs.getString(1);
                app.commit();
                return id;
            }
        }
    }

    private String decide(UUID tenant, UUID actor, String id, String decision) throws SQLException {
        try (Connection app = app(tenant, actor);
                PreparedStatement call = app.prepareStatement("select public.config_decide_db_table_approval(?::uuid, ?)")) {
            call.setString(1, id);
            call.setString(2, decision);
            try (ResultSet rs = call.executeQuery()) {
                rs.next();
                String result = rs.getString(1);
                app.commit();
                return result;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- task 2

    @Test
    @DisplayName("D2: every existing approvedTables entry moved as PENDING_APPROVAL (deduplicated, plain identifiers only), requested by 'migration'")
    void backfillMovedTheEntriesAsPending() throws Exception {
        // the test's own slice only (requested_by = 'migration' marks exactly the backfill's rows): the class shares
        // one migrated database, so state-machine/actor rows the other tests insert must not reach this expectation
        assertThat(scalar("select string_agg(table_name || ':' || status || ':' || requested_by, ',' order by"
                + " lower(table_name)) from public.db_target_approved_table where system_id = ?::uuid"
                + " and requested_by = 'migration'", dbSystemA.toString()))
                .isIn("orders_in:PENDING_APPROVAL:migration,users:PENDING_APPROVAL:migration",
                        "Orders_IN:PENDING_APPROVAL:migration,users:PENDING_APPROVAL:migration");
        assertThat(scalar("select count(*) from public.db_target_approved_table where system_id = ?::uuid"
                + " and requested_by = 'migration'", dbSystemB.toString())).isEqualTo("1");
        assertThat(scalar("select count(*) from public.db_target_approved_table where system_id = ?::uuid"
                + " and requested_by = 'migration'", apiSystemA.toString())).isEqualTo("0");
        assertThat(scalar("select count(*) from public.db_target_approved_table where status <> 'PENDING_APPROVAL'"
                + " and requested_by = 'migration'"))
                .as("nothing is grandfathered as approved").isEqualTo("0");
    }

    @Test
    @DisplayName("D2: the approvedTables key is gone from every stored DB system's settings; other keys stay")
    void theKeyWasStrippedFromTheStoredSettings() throws Exception {
        assertThat(scalar("select count(*) from public.system where jsonb_exists(settings, 'approvedTables')")).isEqualTo("0");
        assertThat(scalar("select settings ->> 'table' from public.system where id = ?::uuid", dbSystemA.toString()))
                .isEqualTo("orders_in");
    }

    @Test
    @DisplayName("The backfilled rows carry the fingerprint of the system's host, port, database and account (Karan 1)")
    void backfilledRowsAreBoundToTheTarget() throws Exception {
        // the backfill's own rows only (requested_by = 'migration'): approval rows other tests insert carry the same
        // SQL fingerprint and would inflate an unfiltered count
        assertThat(scalar("select count(*) from public.db_target_approved_table a"
                + " join public.system s on s.id = a.system_id"
                + " where a.requested_by = 'migration'"
                + " and a.target_hash = public.db_target_fingerprint(s.settings)")).isEqualTo("3");
    }

    @Test
    @DisplayName("Table shape: FORCE row-level security, CHECKs, partial unique index, composite foreign key")
    void theTableShape() throws Exception {
        assertThat(scalar("select relrowsecurity || ',' || relforcerowsecurity from pg_class"
                + " where oid = 'public.db_target_approved_table'::regclass")).isEqualTo("true,true");
        assertThat(scalar("select count(*) from pg_constraint where conrelid = 'public.db_target_approved_table'::regclass"
                + " and contype = 'f'")).isEqualTo("2"); // tenant + composite system
        assertThat(scalar("select indexdef from pg_indexes where indexname = 'db_target_approved_table_live'"))
                .contains("lower(table_name)").contains("REVOKED");

        UUID system = dbSystemB;
        assertThatThrownBy(() -> request(tenantB, system, "a b", requester)).isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23514"); // plain identifier CHECK
        String first = request(tenantB, system, "dup_table", requester);
        assertThat(first).isNotBlank();
        assertThatThrownBy(() -> request(tenantB, system, "DUP_TABLE", requester)).isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23505"); // one live row per lower(name)
    }

    @Test
    @DisplayName("Grants: the app role may SELECT and INSERT but never UPDATE or DELETE a row (a decision moves only through the function)")
    void noUpdateAndNoDeleteForTheAppRole() throws Exception {
        String id = request(tenantA, dbSystemA, "grant_check", requester);
        try (Connection app = app(tenantA, null); Statement sql = app.createStatement()) {
            assertThatThrownBy(() -> sql.executeUpdate("update public.db_target_approved_table set status = 'APPROVED'"
                    + " where id = '" + id + "'")).isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
        }
        try (Connection app = app(tenantA, null); Statement sql = app.createStatement()) {
            assertThatThrownBy(() -> sql.executeUpdate("delete from public.db_target_approved_table where id = '"
                    + id + "'")).isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
        }
        assertThat(scalar("select status from public.db_target_approved_table where id = ?::uuid", id))
                .isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("S1 (V2_4_1340): the app role can only INSERT a PENDING_APPROVAL row - an APPROVED or REVOKED row, or any decider, is refused (42501)")
    void anInsertIsAlwaysPending() throws Exception {
        // status, decided_by, decided_at (sql), revoked_by
        String[][] forged = {
            {"APPROVED", "y", "now()", null},   // already approved by a made-up decider: the S1 exploit
            {"APPROVED", "y", "null", null},
            {"REVOKED", "y", "now()", "z"},
            {"PENDING_APPROVAL", "y", "null", null},
            {"PENDING_APPROVAL", null, "null", "z"},
            {"PENDING_APPROVAL", null, "now()", null},
        };
        int n = 0;
        for (String[] f : forged) {
            String table = "forged_" + n++;
            try (Connection app = app(tenantA, null);
                    PreparedStatement insert = app.prepareStatement("insert into public.db_target_approved_table"
                            + " (tenant_id, system_id, table_name, status, requested_by, decided_by, decided_at,"
                            + " revoked_by, target_hash) values (current_setting('app.tenant_id')::uuid, ?::uuid, ?, ?,"
                            + " 'x', ?, " + f[2] + ", ?, (select public.db_target_fingerprint(s.settings)"
                            + " from public.system s where s.id = ?::uuid))")) {
                insert.setString(1, dbSystemA.toString());
                insert.setString(2, table);
                insert.setString(3, f[0]);
                insert.setString(4, f[1]);
                insert.setString(5, f[3]);
                insert.setString(6, dbSystemA.toString());
                assertThatThrownBy(insert::executeUpdate).as(java.util.Arrays.toString(f))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
            }
        }
        assertThat(scalar("select count(*) from public.db_target_approved_table where table_name like 'forged%'"))
                .isEqualTo("0");
        // the legitimate request still works, and the definer function is still the way to APPROVED
        String id = request(tenantA, dbSystemA, "still_pending", requester);
        assertThat(decide(tenantA, approver, id, "APPROVE")).isEqualTo("APPROVED");
        assertThat(scalar("select status from public.db_target_approved_table where id = ?::uuid", id))
                .isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------------------------------------- task 3

    @Test
    @DisplayName("RLS: tenant B reads none of tenant A's rows, cannot decide them, and cannot insert as tenant A")
    void tenantIsolation() throws Exception {
        String id = request(tenantA, dbSystemA, "rls_check", requester);
        try (Connection b = app(tenantB, null); Statement sql = b.createStatement();
                ResultSet rs = sql.executeQuery("select count(*) from public.db_target_approved_table where id = '"
                        + id + "'")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
        assertThat(decide(tenantB, approver, id, "APPROVE")).isEqualTo("NOT_FOUND");
        try (Connection b = app(tenantB, null);
                PreparedStatement insert = b.prepareStatement("insert into public.db_target_approved_table (tenant_id,"
                        + " system_id, table_name, status, requested_by, target_hash) values (?::uuid, ?::uuid,"
                        + " 'forged', 'PENDING_APPROVAL', 'x', 'h')")) {
            insert.setString(1, tenantA.toString());
            insert.setString(2, dbSystemA.toString());
            assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
        }
        assertThat(scalar("select status from public.db_target_approved_table where id = ?::uuid", id))
                .isEqualTo("PENDING_APPROVAL");
    }

    // ---------------------------------------------------------------------------------------------- task 4

    @Test
    @DisplayName("D4: the requester can never approve their own request; another person can; the state machine holds")
    void theDecisionFunction() throws Exception {
        String id = request(tenantA, dbSystemA, "func_check", requester);

        assertThat(decide(tenantA, requester, id, "APPROVE")).isEqualTo("REFUSED_SELF_APPROVAL");
        assertThat(scalar("select status from public.db_target_approved_table where id = ?::uuid", id))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(decide(tenantA, approver, id, "REVOKE")).as("a pending row is not revocable")
                .isEqualTo("REFUSED_PENDING_APPROVAL");
        assertThat(decide(tenantA, approver, id, "APPROVE")).isEqualTo("APPROVED");
        assertThat(scalar("select decided_by from public.db_target_approved_table where id = ?::uuid", id))
                .isEqualTo(approver.toString());
        assertThat(decide(tenantA, approver, id, "APPROVE")).isEqualTo("REFUSED_APPROVED");
        assertThat(decide(tenantA, requester, id, "REVOKE")).as("revoking is the safe direction: anyone with the role")
                .isEqualTo("REVOKED");
        assertThat(decide(tenantA, approver, id, "REVOKE")).isEqualTo("REFUSED_REVOKED");
        assertThat(decide(tenantA, approver, id, "APPROVE")).isEqualTo("REFUSED_REVOKED");
        assertThat(decide(tenantA, approver, id, "DELETE")).isEqualTo("REFUSED_UNKNOWN_DECISION");
        // a revoked row frees the name for a new request
        assertThat(request(tenantA, dbSystemA, "func_check", requester)).isNotEqualTo(id);
    }

    @Test
    @DisplayName("Karan 2: the decider comes from the session stamp only - without it the function refuses to run")
    void theActorComesFromTheSessionStamp() throws Exception {
        String id = request(tenantA, dbSystemA, "actor_check", requester);
        try (Connection app = app(tenantA, null);
                PreparedStatement call = app.prepareStatement("select public.config_decide_db_table_approval(?::uuid, 'APPROVE')")) {
            call.setString(1, id);
            try (ResultSet rs = call.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("REFUSED_NO_ACTOR");
            }
        }
        assertThat(scalar("select count(*) from pg_proc where proname = 'config_decide_db_table_approval'"
                + " and pronargs = 3")).as("no overload that takes the decider as an argument").isEqualTo("0");
    }

    @Test
    @DisplayName("Karan 1: repointing the system after the request refuses the approval; after approval the approval stops counting")
    void anApprovalIsBoundToTheTarget() throws Exception {
        String stale = request(tenantA, dbSystemA, "repoint_before", requester);
        String approved = request(tenantA, dbSystemA, "repoint_after", requester);
        assertThat(decide(tenantA, approver, approved, "APPROVE")).isEqualTo("APPROVED");
        assertThat(currentTargetMatches(approved)).isTrue();

        String original = scalar("select settings ->> 'host' from public.system where id = ?::uuid", dbSystemA.toString());
        try (Connection owner = owner(); PreparedStatement sql = owner.prepareStatement(
                "update public.system set settings = jsonb_set(settings, '{host}', '\"evil.example\"') where id = ?::uuid")) {
            sql.setString(1, dbSystemA.toString());
            sql.executeUpdate();
        }
        try {
            assertThat(decide(tenantA, approver, stale, "APPROVE")).isEqualTo("REFUSED_TARGET_CHANGED");
            assertThat(currentTargetMatches(approved)).as("the old approval no longer matches the repointed system")
                    .isFalse();
        } finally {
            try (Connection owner = owner(); PreparedStatement sql = owner.prepareStatement(
                    "update public.system set settings = jsonb_set(settings, '{host}', to_jsonb(?::text)) where id = ?::uuid")) {
                sql.setString(1, original);
                sql.setString(2, dbSystemA.toString());
                sql.executeUpdate();
            }
        }
    }

    private boolean currentTargetMatches(String id) throws SQLException {
        // ::text: getString of a boolean renders 't'/'f', which Boolean.parseBoolean reads as FALSE - the same
        // rendering defect theFunctionIsHardened had; cast so the comparison is read as a real word
        return Boolean.parseBoolean(scalar("select (a.target_hash = public.db_target_fingerprint(s.settings))::text"
                + " from public.db_target_approved_table a join public.system s on s.id = a.system_id"
                + " where a.id = ?::uuid", id));
    }

    @Test
    @DisplayName("Karan 6: the definer function pins its search_path and PUBLIC cannot execute it")
    void theFunctionIsHardened() throws Exception {
        assertThat(scalar("select prosecdef || '|' || array_to_string(proconfig, ',') from pg_proc"
                + " where proname = 'config_decide_db_table_approval'")).startsWith("true|search_path=pg_catalog")
                .contains("pg_temp");
        // has_function_privilege returns a boolean: JDBC getString renders it 'f' (no ::text on a pg_catalog call)
        assertThat(scalar("select has_function_privilege('public', 'public.config_decide_db_table_approval(uuid, text)',"
                + " 'EXECUTE')")).isEqualTo("f");
    }
}
