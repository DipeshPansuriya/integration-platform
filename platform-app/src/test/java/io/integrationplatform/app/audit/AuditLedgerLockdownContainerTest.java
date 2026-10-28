package io.integrationplatform.app.audit;

import static io.integrationplatform.app.audit.AuditContainerSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Row 544 (option A, L1): what the application role may and may not do to the audit tables once the lockdown
 * migration ran. Every denial is SQLSTATE 42501 (insufficient privilege), proved as the restricted login that
 * {@link PlatformTestWiring} fixes for every container proof.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditLedgerLockdownContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    /** A throwaway role in a throwaway container; random so a secret scan has nothing to match. */
    private static final String OUTSIDER_PASSWORD = java.util.UUID.randomUUID().toString();

    private static PlatformTestWiring.RestrictedLogin login;
    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @BeforeEach
    void seed() throws SQLException {
        if (SEEDED.compareAndSet(false, true)) {
            AuditContainerSupport.seed(POSTGRES, login);
        }
    }

    private static void assertDenied(Connection session, String sql) {
        assertThatThrownBy(() -> {
            try (Statement statement = session.createStatement()) {
                statement.execute(sql);
            }
        }).isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
    }

    @Test
    @Tag("negative")
    void theApplicationRoleCannotInsertIntoTheLedgerDirectlyNorIntoAMonthPartition() throws SQLException {
        try (Connection app = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
            String row = "(tenant_id, actor, action, event_at) values ('" + TENANT_A + "', 'forger', 'FORGED', now())";
            assertDenied(app, "insert into public.audit_event " + row);
            String partition;
            try (Statement s = app.createStatement(); var rs = s.executeQuery(
                    "select 'public.audit_event_' || to_char(current_date, 'YYYYMM')")) {
                rs.next();
                partition = rs.getString(1);
            }
            assertDenied(app, "insert into " + partition + " " + row);
        }
    }

    @Test
    @Tag("negative")
    void theApplicationRoleCannotMarkAnOutboxRowRelayedNorLockItForUpdate() throws SQLException {
        try (Connection app = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
            long id = AuditContainerSupport.appendOutbox(app, TENANT_A, "actor-l1", "L1_PENDING", null);
            assertDenied(app, "update public.audit_outbox set relayed_at = now() where id = " + id);
            // plan D-2: FOR UPDATE needs UPDATE on at least one column, which the role no longer has
            assertDenied(app, "select id from public.audit_outbox where id = " + id + " for update");
            assertThat(AuditContainerSupport.count(app,
                    "select count(*) from public.audit_outbox where id = ? and relayed_at is null", id))
                    .as("the row is still pending").isEqualTo(1);
        }
    }

    @Test
    @Tag("negative")
    void updateDeleteAndTruncateStayRefusedOnBothTables() throws SQLException {
        try (Connection app = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
            assertDenied(app, "update public.audit_event set actor = 'x'");
            assertDenied(app, "delete from public.audit_event");
            assertDenied(app, "truncate public.audit_event");
            assertDenied(app, "update public.audit_outbox set actor = 'x'");
            assertDenied(app, "delete from public.audit_outbox");
            assertDenied(app, "truncate public.audit_outbox");
        }
    }

    @Test
    void theApplicationRoleKeepsOutboxInsertAndSelectLedgerSelectAndExecuteOnTheMove() throws SQLException {
        try (Connection app = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
            long id = AuditContainerSupport.appendOutbox(app, TENANT_A, "actor-l1-keep", "L1_KEEP", null);
            assertThat(AuditContainerSupport.count(app, "select count(*) from public.audit_outbox where id = ?", id))
                    .isEqualTo(1);
            AuditContainerSupport.count(app, "select count(*) from public.audit_event");
            assertThat(AuditContainerSupport.callMove(app, id)).as("EXECUTE on the move is granted").isEqualTo(1);
        }
    }

    @Test
    @Tag("negative")
    void aRoleOutsideTheApplicationRoleCannotExecuteTheMove() throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            sql.execute("create role row544_outsider login password '" + OUTSIDER_PASSWORD + "' nosuperuser nobypassrls");
        }
        try (Connection outsider = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "row544_outsider",
                OUTSIDER_PASSWORD)) {
            assertDenied(outsider, "select public.audit_relay_move(1)");
        } finally {
            try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql =
                    owner.createStatement()) {
                sql.execute("drop role row544_outsider");
            }
        }
    }

    // ------------------------------------------------------------------ row 570 C2: no pre-relayed outbox row

    private static final String OUTBOX_COLUMNS = "(tenant_id, actor, action, event_at%s) values ('" + TENANT_A
            + "', 'c2', 'C2_PROBE', now()%s)";

    @Test
    @Tag("negative")
    void theApplicationRoleCannotInsertAnOutboxRowThatIsAlreadyRelayedNorSupplyItsCreationTimeOrId() throws SQLException {
        try (Connection app = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
            assertDenied(app, "insert into public.audit_outbox " + String.format(OUTBOX_COLUMNS, ", relayed_at", ", now()"));
            assertDenied(app, "insert into public.audit_outbox " + String.format(OUTBOX_COLUMNS, ", relayed_at", ", null"));
            assertDenied(app, "insert into public.audit_outbox " + String.format(OUTBOX_COLUMNS, ", created_at", ", now()"));
            assertDenied(app, "insert into public.audit_outbox " + String.format(OUTBOX_COLUMNS, ", id", ", 424242")
                    .replace("values (", "overriding system value values ("));
            assertThat(AuditContainerSupport.count(app, "select count(*) from public.audit_outbox where action = ?",
                    "C2_PROBE")).as("nothing was inserted").isZero();
        }
    }

    @Test
    void theWritersInsertStillWorksAndTheRowIsPendingWithTheServersCreationTime() throws SQLException {
        try (Connection app = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
            long id = AuditContainerSupport.appendOutbox(app, TENANT_A, "actor-c2", "C2_OK", "trace-c2");
            assertThat(AuditContainerSupport.count(app,
                    "select count(*) from public.audit_outbox where id = ? and relayed_at is null"
                            + " and created_at is not null and trace_id = 'trace-c2'", id)).isEqualTo(1);
        }
    }

    /** A new outbox column must be a conscious grant decision: this list is the contract of V2_4_1540. */
    @Test
    void theInsertableOutboxColumnsAreExactlyTheWritersColumns() throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement();
                var rs = sql.executeQuery("select string_agg(attname, ',' order by attnum) from pg_attribute"
                        + " where attrelid = 'public.audit_outbox'::regclass and attnum > 0 and not attisdropped"
                        + " and has_column_privilege('" + login.username() + "', attrelid, attnum, 'INSERT')")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo(
                    "tenant_id,actor,action,target_ids,before_state,after_state,event_at,trace_id");
        }
    }
}
