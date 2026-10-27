package io.integrationplatform.app.audit;

import static io.integrationplatform.app.audit.AuditContainerSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import java.sql.Connection;
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
 * Row 544 (option A, B1): the rollback SQL documented in the headers of V2_4_1470 and V2_4_1480 is executed as written
 * (extracted from the migration files), the application role can write the audit tables again as before the change, the
 * old approval function bodies are back, and re-applying the migrations restores the lockdown. One ordered test: each
 * step builds on the previous database state.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditOptionARollbackContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

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

    private static void asOwner(String sql) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement statement =
                owner.createStatement()) {
            statement.execute(sql);
        }
    }

    private static boolean deniedWith42501(Connection session, String sql) {
        try (Statement statement = session.createStatement()) {
            statement.execute(sql);
            return false;
        } catch (SQLException e) {
            return "42501".equals(e.getSQLState());
        }
    }

    private static final String LEDGER_INSERT = "insert into public.audit_event (tenant_id, actor, action, event_at)"
            + " values ('" + TENANT_A + "', 'rb', 'ROLLBACK_PROBE', now())";

    @Test
    void theDocumentedRollbackReopensTheOldPathsAndReapplyingTheMigrationsClosesThemAgain() throws Exception {
        String audit = AuditContainerSupport.migration("V*__audit_relay_move_and_lockdown.sql");
        String approvals = AuditContainerSupport.migration("V*__approval_actor_from_session.sql");

        // before: locked down
        try (Connection app = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
            assertThat(deniedWith42501(app, LEDGER_INSERT)).as("locked down before the rollback").isTrue();
        }

        // rollback, exactly as documented
        asOwner(AuditContainerSupport.rollbackSql(audit));
        asOwner(AuditContainerSupport.rollbackSql(approvals));

        try (Connection app = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
            try (Statement statement = app.createStatement()) {
                statement.execute(LEDGER_INSERT); // INSERT is back
            }
            long id = AuditContainerSupport.appendOutbox(app, TENANT_A, "rb-legacy", "ROLLBACK_LEGACY", null);
            try (Statement statement = app.createStatement()) {
                // the legacy relay statement works again (UPDATE relayed_at + FOR UPDATE + INSERT)
                assertThat(statement.executeUpdate("with picked as (select id from public.audit_outbox where id = " + id
                        + " and relayed_at is null for update skip locked), moved as (update public.audit_outbox o"
                        + " set relayed_at = now() from picked where o.id = picked.id returning o.tenant_id, o.actor,"
                        + " o.action, o.target_ids, o.before_state, o.after_state, o.event_at, o.trace_id)"
                        + " insert into public.audit_event (tenant_id, actor, action, target_ids, before_state,"
                        + " after_state, event_at, trace_id) select * from moved")).isEqualTo(1);
            }
            assertThatThrownBy(() -> AuditContainerSupport.callMove(app, id)).as("the function is dropped")
                    .isInstanceOf(SQLException.class);
        }
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            assertThat(AuditContainerSupport.count(owner,
                    "select count(*) from pg_proc where proname = 'config_approve_version'"
                            + " and prosrc like '%REFUSED_NO_ACTOR%'"))
                    .as("the old approval body is back (no actor refusal)").isZero();
        }

        // re-apply (the column already exists, so the ADD COLUMN is made idempotent for this replay only)
        asOwner(audit.replace("ADD COLUMN trace_id", "ADD COLUMN IF NOT EXISTS trace_id"));
        asOwner(approvals);

        try (Connection app = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
            assertThat(deniedWith42501(app, LEDGER_INSERT)).as("locked down again").isTrue();
            long id = AuditContainerSupport.appendOutbox(app, TENANT_A, "rb-again", "ROLLBACK_REAPPLY", null);
            assertThat(AuditContainerSupport.callMove(app, id)).isEqualTo(1);
        }
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            assertThat(AuditContainerSupport.count(owner,
                    "select count(*) from pg_proc where proname = 'config_approve_version'"
                            + " and prosrc like '%REFUSED_NO_ACTOR%'")).isEqualTo(1);
        }
    }
}
