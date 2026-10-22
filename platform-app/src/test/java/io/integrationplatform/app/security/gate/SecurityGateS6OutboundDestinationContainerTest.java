package io.integrationplatform.app.security.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.delivery.port.Stage1Deliveries;
import io.integrationplatform.tenancy.port.WorkTenantLookup;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T141 / T-147 Release 1 security gate, suite S6 (OutboundDestination), from release-1-security-test-plan.md.
 * The DB-level cases run on the real schema through the application's own beans (stage-1 writes, the work-tenant
 * lookup, the tenant-stamping transaction manager); S6-A5 is a deployment-only check. Container tag: never runs by
 * default; the area roll-up is {@link Release1Gate} and the case list is {@link Release1GateCatalogue}.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityGateS6OutboundDestinationContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final AtomicBoolean GRANTED = new AtomicBoolean();
    private static PlatformTestWiring.RestrictedLogin login;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    /** The worker-style tenant binding the application's composite TenantContext composes (as a stage consumer's). */
    @TestConfiguration
    static class BindingConfiguration {
        @Bean
        @org.springframework.context.annotation.Primary
        GateFixture.BoundTenant gateBoundTenant(
                @org.springframework.beans.factory.annotation.Qualifier("requestTenantContext")
                io.integrationplatform.tenancy.TenantContext request) {
            return new GateFixture.BoundTenant(request);
        }
    }

    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private GateFixture.BoundTenant binding;
    @Autowired
    private Stage1Deliveries stage1;
    @Autowired
    private WorkTenantLookup workTenants;

    @BeforeEach
    void grantApplicationRole() throws SQLException {
        if (GRANTED.compareAndSet(false, true)) {
            PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        }
    }

    private GateFixture fixture() throws SQLException {
        GateFixture fixture = new GateFixture(POSTGRES, login, binding, transactions);
        fixture.seedTenants();
        return fixture;
    }

    /**
     * S6-A1: Forged tenant header/value on a record (ADR-014 conformance 4).
     * Expected: ipaas_work_tenant(MESSAGE, id) (ADR-011 D8) resolves the tenant from the row; the forged value changes nothing; unknown id → no effect, offset commits
     */
    @Test
    void s6_a1_forged_tenant_header_value_on_a() throws SQLException {
        GateFixture f = fixture();
        Instant now = Instant.now();
        UUID messageA = f.insertMessage(GateFixture.TENANT_A, now, "s6a1-A");
        UUID messageB = f.insertMessage(GateFixture.TENANT_B, now, "s6a1-B");
        assertThat(workTenants.tenantOf("MESSAGE", messageA)).isEqualTo(GateFixture.TENANT_A);
        assertThat(workTenants.tenantOf("MESSAGE", messageB)).isEqualTo(GateFixture.TENANT_B);
        // a "forged value": the other tenant bound on the worker thread changes the answer for nobody
        assertThat(binding.callAs(GateFixture.TENANT_B, () -> workTenants.tenantOf("MESSAGE", messageA)))
                .isEqualTo(GateFixture.TENANT_A);
        assertThat(binding.callAs(GateFixture.TENANT_A, () -> workTenants.tenantOf("MESSAGE", messageB)))
                .isEqualTo(GateFixture.TENANT_B);
        // the DELIVERY kind resolves from the delivery row the same way
        f.inTenantTx(GateFixture.TENANT_A, () -> stage1.insertPerRoute(
                List.of(GateFixture.delivery(messageA, UUID.randomUUID(), now))));
        UUID deliveryA = UUID.fromString(f.scalar(GateFixture.TENANT_A,
                "select id::text from public.delivery where message_id = ?::uuid", messageA.toString()));
        assertThat(workTenants.tenantOf("DELIVERY", deliveryA)).isEqualTo(GateFixture.TENANT_A);
        // an unknown id: no tenant, no effect on the store
        String before = f.ownerScalar("select count(*) from public.message");
        assertThat(workTenants.tenantOf("MESSAGE", UUID.randomUUID())).isNull();
        assertThat(workTenants.tenantOf("DELIVERY", UUID.randomUUID())).isNull();
        assertThat(f.ownerScalar("select count(*) from public.message")).isEqualTo(before);
    }

    /**
     * S6-A2: ipaas_work_tenant definer abuse.
     * Expected: SECURITY DEFINER returns only the tenant id and only for the closed kinds; wrong-kind/garbage input → NULL/error, no tenant grant; no fifth definer function exists (ADR-012 §4, T5 stays at four)
     */
    @Test
    void s6_a2_ipaas_work_tenant_definer_abuse() throws SQLException {
        GateFixture f = fixture();
        UUID messageB = f.insertMessage(GateFixture.TENANT_B, Instant.now(), "s6a2-B");
        // called as tenant A (stamped) at B's message: only the closed kinds answer, and only with the tenant id
        for (String kind : List.of("TENANT", "message", "Message", "", " MESSAGE", "MESSAGE; drop table public.message --",
                "' or 1=1 --", "PAYLOAD", "ROUTE", "DELIVERY ")) {
            assertThat(f.scalar(GateFixture.TENANT_A, "select public.ipaas_work_tenant(?, ?::uuid)::text", kind,
                    messageB.toString())).as("kind [%s]", kind).isNull();
        }
        assertThat(f.scalar(GateFixture.TENANT_A, "select public.ipaas_work_tenant('MESSAGE', null)::text")).isNull();
        assertThat(f.ownerScalar("select count(*) from public.message where id = ?::uuid", messageB.toString()))
                .as("the abuse attempts changed nothing").isEqualTo("1");
        // the shape of the function: definer, uuid-only result, pinned search_path, no PUBLIC execute
        assertThat(f.ownerScalar("select p.prosecdef::text || '|' || p.prorettype::regtype::text || '|' ||"
                + " coalesce(array_to_string(p.proconfig, ';'), '') from pg_proc p where p.oid ="
                + " 'public.ipaas_work_tenant(text,uuid)'::regprocedure"))
                .isEqualTo("true|uuid|search_path=pg_catalog, public");
        String acl = f.ownerScalar("select coalesce(p.proacl::text, '') from pg_proc p where p.oid ="
                + " 'public.ipaas_work_tenant(text,uuid)'::regprocedure");
        assertThat(acl).contains("ipaas_app=X/").doesNotContain("{=X").doesNotContain(",=X");
        // the definer set stays closed: the four tenant-resolution boundary functions and no fifth (ADR-012 section 4)
        TreeSet<String> definers = new TreeSet<>();
        definers.addAll(List.of(f.ownerScalar("select string_agg(proname, ',' order by proname) from pg_proc where"
                + " prosecdef and pronamespace = 'public'::regnamespace and proname like 'ipaas\\_%'").split(",")));
        assertThat(definers).containsExactly("ipaas_active_tenants", "ipaas_client_bootstrap", "ipaas_login_bootstrap",
                "ipaas_work_tenant");
    }

    /**
     * S6-A3: Record produced without / with forged aggregate ids (ADR-011 D7: ids only).
     * Expected: Stage 1 keys off ids only; extra fields ignored (or rejected); replay/duplicate behaves per ADR-014 process table (0 rows updated)
     */
    @Test
    void s6_a3_record_produced_without_with_forged_aggregate() throws SQLException {
        GateFixture f = fixture();
        Instant now = Instant.now();
        UUID messageA = f.insertMessage(GateFixture.TENANT_A, now, "s6a3-A");
        UUID messageB = f.insertMessage(GateFixture.TENANT_B, now, "s6a3-B");
        // no tenant bound: stage 1 refuses before any SQL runs (a record carries ids, never a tenant)
        assertThatThrownBy(() -> stage1.markMessageRouted(messageA)).isInstanceOf(RuntimeException.class);
        // A's worker handed B's message id: the flip finds no row under A's policy, B's message is untouched
        assertThat(f.inTenantTx(GateFixture.TENANT_A, () -> stage1.markMessageRouted(messageB))).isFalse();
        assertThat(f.scalar(GateFixture.TENANT_B, "select state from public.message where id = ?::uuid",
                messageB.toString())).isEqualTo("ACCEPTED");
        // an unknown id flips nothing and does not raise
        assertThat(f.inTenantTx(GateFixture.TENANT_A, () -> stage1.markMessageRouted(UUID.randomUUID()))).isFalse();
        // the fan-out for B's message id by A's worker lands, if at all, in A's tenant: B sees nothing
        f.inTenantTx(GateFixture.TENANT_A, () -> stage1.insertPerRoute(
                List.of(GateFixture.delivery(messageB, UUID.randomUUID(), now))));
        assertThat(f.scalar(GateFixture.TENANT_B, "select count(*) from public.delivery where message_id = ?::uuid",
                messageB.toString())).isEqualTo("0");
        String owners = f.ownerScalar("select string_agg(distinct tenant_id::text, ',') from public.delivery where"
                + " message_id = ?::uuid", messageB.toString());
        assertThat(owners).as("tenant of a delivery written by A's worker").isIn(null, GateFixture.TENANT_A.toString());
    }

    /**
     * S6-A4: Stage-1 conditional-update bypass (ADR-014 conformance 2, the ADR-010:100 gate).
     * Expected: 0 rows inserted: created_at comes from the message's accepted_at, never the caller's clock (the Stage1Deliveries seam takes the message, not a free Instant - architecture test pins it)
     */
    @Test
    void s6_a4_stage_1_conditional_update_bypass() throws SQLException {
        GateFixture f = fixture();
        Instant yesterday = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(12, ChronoUnit.HOURS);
        UUID message = f.insertMessage(GateFixture.TENANT_A, yesterday, "s6a4");
        assertThat(f.inTenantTx(GateFixture.TENANT_A, () -> stage1.insertPerRoute(
                List.of(GateFixture.delivery(message, UUID.randomUUID(), yesterday))))).isEqualTo(1);
        assertThat(f.ownerScalar("select (d.created_at = m.accepted_at)::text || '|' ||"
                + " (d.created_at::date < current_date)::text from public.delivery d join public.message m on"
                + " m.id = d.message_id where d.message_id = ?::uuid", message.toString()))
                .as("created_at is the message's accepted day, not the caller's clock").isEqualTo("true|true");
        // a day with no partition cannot be written at all: the day is never a free instant
        assertThatThrownBy(() -> f.inTenantTx(GateFixture.TENANT_A, () -> stage1.insertPerRoute(
                List.of(GateFixture.delivery(message, UUID.randomUUID(), Instant.now().minus(60, ChronoUnit.DAYS))))))
                .satisfies(e -> assertThat(GateFixture.sqlState(e)).isEqualTo("23514"));
        // the guard is conditional on ACCEPTED: first flip wins, a re-consume updates 0 rows and keeps routed_at
        assertThat(f.inTenantTx(GateFixture.TENANT_A, () -> stage1.markMessageRouted(message))).isTrue();
        String routedAt = f.scalar(GateFixture.TENANT_A, "select routed_at::text from public.message where id ="
                + " ?::uuid", message.toString());
        assertThat(routedAt).isNotNull();
        assertThat(f.inTenantTx(GateFixture.TENANT_A, () -> stage1.markMessageRouted(message))).isFalse();
        assertThat(f.scalar(GateFixture.TENANT_A, "select routed_at::text from public.message where id = ?::uuid",
                message.toString())).isEqualTo(routedAt);
    }

    /**
     * S6-A5: Relay lock / consumer authorization posture (E8-F2).
     * Expected: Recorded posture on the demo compose (likely open localhost for demo); **Kafka ACL production posture is deployment-only** - marked TBD with an explicit "cannot be verified from this environment", filed for the release checklist, not a code finding
     */
    @Test
    @Disabled("BY DESIGN: T136 deployment-only check, runs from the release checklist (relay lock / consumer authorisation posture needs the deployed environment) (T141 gate case S6-A5)")
    void s6_a5_relay_lock_consumer_authorization_posture() {
        fail("S6-A5 body lands with T136");
    }

    /**
     * S6-A6: Duplicate record publication (ADR-014 conformance 1).
     * Expected: N deliveries for N routes, not 2N; second run updates 0 rows; 0 second outbox rows
     */
    @Test
    void s6_a6_duplicate_record_publication() throws SQLException {
        GateFixture f = fixture();
        Instant now = Instant.now();
        UUID message = f.insertMessage(GateFixture.TENANT_A, now, "s6a6");
        List<io.integrationplatform.delivery.port.Stage1Delivery> routes = List.of(
                GateFixture.delivery(message, UUID.randomUUID(), now), GateFixture.delivery(message, UUID.randomUUID(), now),
                GateFixture.delivery(message, UUID.randomUUID(), now));
        assertThat(f.inTenantTx(GateFixture.TENANT_A, () -> stage1.insertPerRoute(routes))).isEqualTo(3);
        assertThat(f.inTenantTx(GateFixture.TENANT_A, () -> stage1.insertPerRoute(routes)))
                .as("the same record published twice inserts nothing").isZero();
        assertThat(f.scalar(GateFixture.TENANT_A, "select count(*) from public.delivery where message_id = ?::uuid",
                message.toString())).isEqualTo("3");
        assertThat(f.ownerScalar("select count(*) from public.outbox o join public.delivery d on d.id ="
                + " o.aggregate_id where d.message_id = ?::uuid and o.topic = 'platform.delivery.due'",
                message.toString())).as("one due record per delivery, none for the duplicate run").isEqualTo("3");
        assertThat(f.inTenantTx(GateFixture.TENANT_A, () -> stage1.markMessageRouted(message))).isTrue();
        assertThat(f.inTenantTx(GateFixture.TENANT_A, () -> stage1.markMessageRouted(message))).isFalse();
    }
}
