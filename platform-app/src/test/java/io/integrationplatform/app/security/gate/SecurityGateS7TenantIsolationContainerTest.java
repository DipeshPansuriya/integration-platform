package io.integrationplatform.app.security.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T141 / T-147 Release 1 security gate, suite S7 (TenantIsolation), from release-1-security-test-plan.md.
 * Every case is disabled until the tracker item it names is on main; the area roll-up is
 * {@link Release1Gate} and the case list is {@link Release1GateCatalogue}. Container tag: never runs by default.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityGateS7TenantIsolationContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final AtomicBoolean GRANTED = new AtomicBoolean();
    private static PlatformTestWiring.RestrictedLogin login;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @BeforeEach
    void grantApplicationRole() throws SQLException {
        if (GRANTED.compareAndSet(false, true)) {
            PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        }
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), login.username(), login.password());
    }

    private static TreeSet<String> query(String sql) throws SQLException {
        TreeSet<String> result = new TreeSet<>();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                Statement st = owner.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }

    /**
     * S7-A1: Unique (message_id, route_id, created_at) under parallel double-submit.
     * Expected: ON CONFLICT DO NOTHING → exactly 1 delivery, 1 outbox row; the loser gets 0 rows and writes nothing
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: dependency T68 (E8 message store / E9 stage-1) is on main (c33503da, V2_3_930); body not yet written - DB-level case on the real schema (T141 gate case S7-A1)")
    void s7_a1_unique_under_parallel_double_submit() {
        fail("S7-A1 body lands with T68");
    }

    /**
     * S7-A2: Replay re-drive creates no second delivery (ADR-014: replay re-drives the same row).
     * Expected: Same row DEAD_LETTERED → DUE, never a second delivery row; created_at still the message's accepted day
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T111 is on main (fcad1c39); body not yet written - replay re-drive on the real schema (T141 gate case S7-A2)")
    void s7_a2_replay_re_drive_creates_no_second() {
        fail("S7-A2 body lands with T111");
    }

    /**
     * S7-A3: Cross-tenant message-id submission.
     * Expected: Ingest resolves tenant from the credential (ADR-007 D2); B's ids never match A's routing; 403 + audit on body customerId mismatch
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: T115 is on main (68ec617c); body not yet written - cross-tenant message id submission over the intake API (platform-e2e or SpringBootTest) (T141 gate case S7-A3)")
    void s7_a3_cross_tenant_message_id_submission() {
        fail("S7-A3 body lands with T115");
    }

    /**
     * S7-A4: Payload retention / purge guard (30 days, m-f1fcfc; ADR-014 conformance 5).
     * Expected: Guard day survives (nothing dropped); clean day's message_D, payload_D, delivery_D all dropped; DELETE as ipaas_app fails 42501 on payload/message
     */
    @Test
    @Disabled("BLOCKED by T75: only PayloadPurgePlaceholderHandler is on main (platform-app scheduler); the real payload retention purge (partition drop, maintenance role T-135b) is not built (T141 gate case S7-A4)")
    void s7_a4_payload_retention_purge_guard() {
        fail("S7-A4 body lands with T75");
    }

    /**
     * S7-A5: Message state transitions resistant to replay.
     * Expected: No state machine regression; the conditional update only mutates ACCEPTED; ROUTE_FAILED follows its ADR-010 path
     */
    @Test
    @Disabled("NOT RE-ENABLED 2026-10-22: dependency T68 (E8 message store / E9 stage-1) is on main (c33503da, V2_3_930); body not yet written - DB-level case on the real schema (T141 gate case S7-A5)")
    void s7_a5_message_state_transitions_resistant_to_replay() {
        fail("S7-A5 body lands with T68");
    }

    /**
     * S7-A6: message/payload grant conformance (ADR-014 §1).
     * Expected: All 42501; grants: message SELECT/INSERT/UPDATE(state, routed_at, updated_at), payload SELECT/INSERT only
     */
    @Test
    void s7_a6_message_payload_grant_conformance() throws SQLException {
        // every statement is tried as the restricted application login, tenant stamped, and must be refused 42501
        List<String> tamper = List.of(
                "update public.message set tenant_id = tenant_id",
                "update public.message set accepted_at = accepted_at",
                "delete from public.message",
                "truncate public.message",
                "update public.payload set tenant_id = tenant_id",
                "delete from public.payload",
                "truncate public.payload");
        try (Connection app = appConnection()) {
            app.setAutoCommit(false);
            for (String statement : tamper) {
                try (Statement sql = app.createStatement()) {
                    // a rolled-back transaction drops the transaction-local stamp: stamp again for every attempt
                    sql.execute("select set_config('app.tenant_id', '0000000a-0000-4000-8000-00000000000a', true)");
                }
                assertThatThrownBy(() -> {
                    try (Statement sql = app.createStatement()) {
                        sql.execute(statement);
                    }
                }).as(statement).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).as(statement).isEqualTo("42501"));
                app.rollback();
            }
        }
        assertThat(query("select privilege_type from information_schema.role_table_grants where table_schema ="
                + " 'public' and table_name = 'message' and grantee = 'ipaas_app'"))
                .as("table privileges of the application role on message").containsExactly("INSERT", "SELECT");
        assertThat(query("select column_name from information_schema.column_privileges where table_schema = 'public'"
                + " and table_name = 'message' and grantee = 'ipaas_app' and privilege_type = 'UPDATE'"))
                .as("updatable columns of message").containsExactly("routed_at", "state", "updated_at");
        assertThat(query("select privilege_type from information_schema.role_table_grants where table_schema ="
                + " 'public' and table_name = 'payload' and grantee = 'ipaas_app'"))
                .as("table privileges of the application role on payload").containsExactly("INSERT", "SELECT");
        assertThat(query("select column_name from information_schema.column_privileges where table_schema = 'public'"
                + " and table_name = 'payload' and grantee = 'ipaas_app' and privilege_type = 'UPDATE'"))
                .as("updatable columns of payload").isEmpty();
        // a partition reached by its own name must not widen the parent's grants (ADR-014)
        List<String> widened = new ArrayList<>();
        for (String partition : query("select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid"
                + " join pg_class p on p.oid = i.inhparent where p.relname in ('message', 'payload')")) {
            for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) {
                if (!query("select has_table_privilege('ipaas_app', 'public.\"" + partition + "\"', '" + privilege
                        + "')::text").first().equals("false")) {
                    widened.add(partition + " grants " + privilege);
                }
            }
        }
        assertThat(widened).as("partitions that grant the application role more than the parent").isEmpty();
    }
}
