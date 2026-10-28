package io.integrationplatform.app.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.audit.api.ActivityEntry;
import io.integrationplatform.audit.api.ActivityEvent;
import io.integrationplatform.audit.api.ActivityReadApi;
import io.integrationplatform.audit.api.ActivityWriteApi;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E12 write side over the real ledger (V2_2_1220 plus the trace column), through the restricted application login.
 *
 * <pre>
 * Scenario: An activity event is recorded with its trace and read back
 *   Given a tenant-A transaction
 *   When an activity entry with a trace id is recorded
 *   Then ActivityReadApi lists it for tenant A with the same trace id
 *
 * &#64;negative &#64;tenant
 * Scenario: Tenant A's activity is invisible to tenant B
 *   Given an event recorded by tenant A
 *   When a tenant-B user lists the activity or asks for that event id
 *   Then the list holds none of A's events and the id is empty
 * </pre>
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActivityWriterContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-0000000000c1");
    private static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-0000000000d1");

    private static PlatformTestWiring.RestrictedLogin login;
    private static boolean seeded;

    @Autowired
    private ActivityWriteApi writer;

    @Autowired
    private ActivityReadApi reader;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestBoundTenantContext boundTenant;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @TestConfiguration
    static class TenantBindingConfiguration {

        @Bean
        @Primary
        TestBoundTenantContext testBoundTenantContext() {
            return new TestBoundTenantContext();
        }
    }

    static final class TestBoundTenantContext implements TenantContext {

        private final ThreadLocal<UUID> bound = new ThreadLocal<>();

        <T> T as(UUID tenantId, Supplier<T> work) {
            bound.set(tenantId);
            try {
                return work.get();
            } finally {
                bound.remove();
            }
        }

        @Override
        public Optional<UUID> currentTenantId() {
            return Optional.ofNullable(bound.get());
        }
    }

    @BeforeEach
    void grantAndSeedOnce() throws SQLException {
        if (seeded) {
            return;
        }
        seeded = true;
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            sql.execute("insert into public.tenant (id, code, status, time_zone) values"
                    + " ('" + TENANT_A + "', 'writer-tenant-a', 'ACTIVE', 'Etc/UTC'),"
                    + " ('" + TENANT_B + "', 'writer-tenant-b', 'ACTIVE', 'Etc/UTC')"
                    + " on conflict (id) do nothing");
        }
    }

    private void recordAs(UUID tenant, ActivityEntry entry) {
        boundTenant.as(tenant, () -> new TransactionTemplate(transactionManager).execute(status -> {
            writer.record(entry);
            return null;
        }));
        // row 544 (Q1): the writer appends to the outbox; the ledger row exists once the relay moved it, and the
        // app role's only way to move is audit_relay_move (same call the relay makes)
        boundTenant.as(tenant, () -> new TransactionTemplate(transactionManager).execute(status -> jdbc.queryForList(
                "select public.audit_relay_move(id) from public.audit_outbox where relayed_at is null")));
    }

    private ActivityReadApi.ActivityPage listAs(UUID tenant, String action) {
        return boundTenant.as(tenant, () -> reader.list(action, null, null, null, null));
    }

    @Test
    void anEntryIsRecordedWithItsTraceIdAndReadBackThroughTheReadApi() {
        recordAs(TENANT_A, new ActivityEntry("writer-a-roundtrip", "WRITER_ROUNDTRIP", List.of("flow:1", "entry:2"),
                "{\"v\":1}", "{\"v\":2}", Instant.now(), "trace-roundtrip-1"));

        List<ActivityEvent> events = listAs(TENANT_A, "WRITER_ROUNDTRIP").items();

        assertThat(events).hasSize(1);
        ActivityEvent event = events.get(0);
        assertThat(event.actor()).isEqualTo("writer-a-roundtrip");
        assertThat(event.targetIds()).containsExactly("flow:1", "entry:2");
        assertThat(event.before()).isEqualTo("{\"v\":1}");
        assertThat(event.after()).isEqualTo("{\"v\":2}");
        assertThat(event.traceId()).isEqualTo("trace-roundtrip-1");
        assertThat(boundTenant.as(TENANT_A, () -> reader.event(event.id()))).map(ActivityEvent::traceId)
                .contains("trace-roundtrip-1");
    }

    @Test
    void anEntryWithoutATraceIsStoredWithNone() {
        recordAs(TENANT_A, new ActivityEntry("writer-a-notrace", "WRITER_NOTRACE", null, null, null, Instant.now(),
                null));

        assertThat(listAs(TENANT_A, "WRITER_NOTRACE").items()).singleElement().satisfies(e -> {
            assertThat(e.traceId()).isNull();
            assertThat(e.targetIds()).isEmpty();
        });
    }

    @Test
    void anAuditEventMappedToAnEntryIsRecordedLikeAnyOther() {
        AuditEvent audit = new AuditEvent(TENANT_A, "admin-mapped", "WRITER_MAPPED", List.of("lookup:9"), null,
                "{\"x\":1}", Instant.now());

        recordAs(TENANT_A, ActivityEntry.from(audit, "trace-mapped"));

        assertThat(listAs(TENANT_A, "WRITER_MAPPED").items()).singleElement().satisfies(e -> {
            assertThat(e.actor()).isEqualTo("admin-mapped");
            assertThat(e.traceId()).isEqualTo("trace-mapped");
        });
    }

    @Test
    void tenantANeverReadsTenantBsRowsAndTenantBNeverReadsTenantAs() {
        recordAs(TENANT_A, new ActivityEntry("writer-a-iso", "WRITER_ISOLATION", List.of("a:1"), null, null,
                Instant.now(), "trace-iso-a"));
        recordAs(TENANT_B, new ActivityEntry("writer-b-iso", "WRITER_ISOLATION", List.of("b:1"), null, null,
                Instant.now(), "trace-iso-b"));

        List<ActivityEvent> seenByA = listAs(TENANT_A, "WRITER_ISOLATION").items();
        List<ActivityEvent> seenByB = listAs(TENANT_B, "WRITER_ISOLATION").items();

        assertThat(seenByA).extracting(ActivityEvent::actor).containsExactly("writer-a-iso");
        assertThat(seenByB).extracting(ActivityEvent::actor).containsExactly("writer-b-iso");
        long bId = seenByB.get(0).id();
        long aId = seenByA.get(0).id();
        assertThat(boundTenant.as(TENANT_A, () -> reader.event(bId))).as("A asks for B's event id").isEmpty();
        assertThat(boundTenant.as(TENANT_B, () -> reader.event(aId))).as("B asks for A's event id").isEmpty();
    }
}
