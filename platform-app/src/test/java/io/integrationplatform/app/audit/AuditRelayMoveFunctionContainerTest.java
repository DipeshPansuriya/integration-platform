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
            assertThatThrownBy(() -> AuditContainerSupport.callMove(unbound, 1L)).isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState()).as("unset setting: undefined_object")
                    .isEqualTo("42704");
        }
        try (Connection blank = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
            try (Statement s = blank.createStatement()) {
                s.execute("select set_config('app.tenant_id', '', false)");
            }
            assertThatThrownBy(() -> AuditContainerSupport.callMove(blank, 1L)).isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState()).as("blank setting: invalid uuid text")
                    .isEqualTo("22P02");
        }
    }

    @Test
    void aFailingLedgerInsertLeavesTheOutboxRowPendingAndNoLedgerRow() throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            sql.execute("alter table public.audit_event add constraint row544_boom check (action <> 'ATOMIC_BOOM')");
            try (Connection app = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A)) {
                long id = AuditContainerSupport.appendOutbox(app, TENANT_A, "actor-atomic", "ATOMIC_BOOM", null);

                assertThatThrownBy(() -> AuditContainerSupport.callMove(app, id)).isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState()).as("the CHECK constraint fired")
                        .isEqualTo("23514");

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

    /**
     * Row 544 review C1: the test containers run the function as the container superuser, which bypasses row-level
     * security. Here the function is owned for a moment by a NOSUPERUSER NOBYPASSRLS role holding only what the
     * migration owner holds on the two tables, so FORCE RLS binds the owner too: it still moves once, refuses another
     * tenant's row (both gates), and fails closed with no tenant. The owner is put back afterwards.
     */
    @Test
    void underANosuperuserNobypassrlsOwnerTheMoveStillMovesOnceAndRefusesAnotherTenant() throws SQLException {
        String original = POSTGRES.getUsername();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            sql.execute("create role row544_fn_owner nologin nosuperuser nobypassrls");
            sql.execute("grant select, update on public.audit_outbox to row544_fn_owner");
            sql.execute("grant select, insert on public.audit_event to row544_fn_owner");
            sql.execute("grant create on schema public to row544_fn_owner");
            sql.execute("alter function public.audit_relay_move(bigint) owner to row544_fn_owner");
            try (Connection check = PlatformTestWiring.ownerConnection(POSTGRES)) {
                assertThat(AuditContainerSupport.count(check,
                        "select count(*) from pg_proc p join pg_roles r on r.oid = p.proowner"
                                + " where p.proname = 'audit_relay_move' and not r.rolsuper and not r.rolbypassrls"))
                        .as("the function is owned by a role that is bound by row-level security").isEqualTo(1);
            }
            try (Connection asA = AuditContainerSupport.appSession(POSTGRES, login, TENANT_A);
                    Connection asB = AuditContainerSupport.appSession(POSTGRES, login, TENANT_B);
                    Connection unbound = AuditContainerSupport.appSession(POSTGRES, login, null)) {
                long idA = AuditContainerSupport.appendOutbox(asA, TENANT_A, "actor-rls-a", "RLS_OWNER_A", null);
                long idB = AuditContainerSupport.appendOutbox(asB, TENANT_B, "actor-rls-b", "RLS_OWNER_B", null);

                assertThat(AuditContainerSupport.callMove(asA, idA)).isEqualTo(1);
                assertThat(AuditContainerSupport.callMove(asA, idA)).isZero();
                assertThat(AuditContainerSupport.callMove(asA, idB)).as("another tenant's row").isZero();
                assertThat(AuditContainerSupport.count(asA,
                        "select count(*) from public.audit_event where action = 'RLS_OWNER_A'")).isEqualTo(1);
                assertThat(AuditContainerSupport.count(asB,
                        "select count(*) from public.audit_outbox where id = ? and relayed_at is null", idB))
                        .isEqualTo(1);
                assertThatThrownBy(() -> AuditContainerSupport.callMove(unbound, idA)).isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42704");
            }
        } finally {
            try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql =
                    owner.createStatement()) {
                sql.execute("alter function public.audit_relay_move(bigint) owner to \"" + original + "\"");
                sql.execute("drop owned by row544_fn_owner");
                sql.execute("drop role if exists row544_fn_owner");
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
