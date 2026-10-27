package io.integrationplatform.app.audit;

import static io.integrationplatform.app.audit.AuditContainerSupport.TENANT_A;
import static io.integrationplatform.app.audit.AuditContainerSupport.TENANT_B;
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
 * Row 544 (option A, L2): {@code public.audit_relay_move(bigint)} - the only way the application role moves a
 * committed outbox row into the ledger. Moves once, copies every field, refuses another tenant's row, fails closed
 * with no tenant bound, and is atomic (both writes or neither).
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditRelayMoveFunctionContainerTest {

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

    @Test
    void movesOnceCopiesEveryFieldAndASecondCallMovesNothing() throws SQLException {
        try (Connection app = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
            long id = AuditContainerSupport.appendOutbox(app, TENANT_A, "actor-move", "MOVE_ONCE", "trace-544");

            assertThat(AuditContainerSupport.callMove(app, id)).isEqualTo(1);
            assertThat(AuditContainerSupport.callMove(app, id)).as("already relayed: nothing moved").isZero();

            assertThat(AuditContainerSupport.count(app,
                    "select count(*) from public.audit_event where action = 'MOVE_ONCE'")).isEqualTo(1);
            assertThat(AuditContainerSupport.count(app,
                    "select count(*) from public.audit_event e join public.audit_outbox o"
                            + " on o.id = ? and e.tenant_id = o.tenant_id and e.actor = o.actor"
                            + " and e.action = o.action and e.target_ids = o.target_ids"
                            + " and e.before_state is not distinct from o.before_state"
                            + " and e.after_state is not distinct from o.after_state"
                            + " and e.event_at = o.event_at and e.trace_id is not distinct from o.trace_id"
                            + " and e.trace_id = 'trace-544' where o.relayed_at is not null", id))
                    .as("every field copied verbatim and the outbox row stamped relayed").isEqualTo(1);
        }
    }

    @Test
    void anotherTenantsRowMovesNothingAndIsLeftUntouched() throws SQLException {
        try (Connection asB = AuditContainerSupport.appSession(POSTGRES, login, TENANT_B);
                Connection asA = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
            long idB = AuditContainerSupport.appendOutbox(asB, TENANT_B, "actor-b", "CROSS_TENANT", null);

            assertThat(AuditContainerSupport.callMove(asA, idB)).as("tenant A cannot move tenant B's row").isZero();

            assertThat(AuditContainerSupport.count(asB,
                    "select count(*) from public.audit_outbox where id = ? and relayed_at is null", idB)).isEqualTo(1);
            assertThat(AuditContainerSupport.count(asB,
                    "select count(*) from public.audit_event where action = 'CROSS_TENANT'")).isZero();
        }
    }

    @Test
    @Tag("negative")
    void withNoTenantBoundTheMoveFailsClosed() throws SQLException {
        try (Connection unbound = AuditContainerSupport.appSession(POSTGRES, login, null)) {
            assertThatThrownBy(() -> AuditContainerSupport.callMove(unbound, 1L)).isInstanceOf(SQLException.class);
        }
        try (Connection blank = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
            try (Statement s = blank.createStatement()) {
                s.execute("select set_config('app.tenant_id', '', false)");
            }
            assertThatThrownBy(() -> AuditContainerSupport.callMove(blank, 1L)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void aFailingLedgerInsertLeavesTheOutboxRowPendingAndNoLedgerRow() throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            sql.execute("alter table public.audit_event add constraint row544_boom check (action <> 'ATOMIC_BOOM')");
            try (Connection app = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
                long id = AuditContainerSupport.appendOutbox(app, TENANT_A, "actor-atomic", "ATOMIC_BOOM", null);

                assertThatThrownBy(() -> AuditContainerSupport.callMove(app, id)).isInstanceOf(SQLException.class);

                assertThat(AuditContainerSupport.count(app,
                        "select count(*) from public.audit_outbox where id = ? and relayed_at is null", id))
                        .as("the outbox row is still pending (fail-visible)").isEqualTo(1);
                assertThat(AuditContainerSupport.count(app,
                        "select count(*) from public.audit_event where action = 'ATOMIC_BOOM'")).isZero();
            }
        } finally {
            try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql =
                    owner.createStatement()) {
                sql.execute("alter table public.audit_event drop constraint if exists row544_boom");
            }
        }
    }

    @Test
    void theFunctionIsADefinerWithAPinnedSearchPath() throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            assertThat(AuditContainerSupport.count(owner,
                    "select count(*) from pg_proc where proname = 'audit_relay_move' and prosecdef"
                            + " and proconfig @> array['search_path=pg_catalog, pg_temp']")).isEqualTo(1);
        }
    }
}
