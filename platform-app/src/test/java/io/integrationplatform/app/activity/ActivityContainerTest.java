package io.integrationplatform.app.activity;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Row327 T118 (E12-F2-S2, T-069): the activity view over the real audit ledger (V2_2_1220) through the restricted
 * application login, so the tenant-scoped reader's predicate AND row-level security both stand between tenants.
 *
 * <pre>
 * Scenario: Own events, newest first, filterable
 *   Given audit events of tenants A and B
 *   When a tenant-A user opens the activity view and filters by action and date
 *   Then only tenant A's matching events are listed
 *
 * &#64;negative &#64;tenant
 * Scenario: Another tenant's event id gives nothing
 *   Given a tenant-A user
 *   When they request a tenant-B event by id through the API
 *   Then they get 404          (the service answers empty; the controller's 404 is ActivityControllerTest's,
 *                               the HTTP forgery is E12ActivityMatrix's)
 * </pre>
 *
 * <p>The events are seeded through the owner connection (the ledger grants the application role no way to choose an
 * event time in the past); the reads run as the application with the tenant bound per call - the shape of
 * {@code AuditWriterContainerTest}. Written in row 327 lane t118 and NOT run there (no Docker in the lane); the lead
 * runs it in a docker-suite lock slot.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActivityContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-0000000000a1");
    private static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-0000000000b1");

    private static PlatformTestWiring.RestrictedLogin login;

    private static long aOldChange;
    private static long aNewChange;
    private static long aRetry;
    private static long aOutOfRange;
    private static long bChange;

    @Autowired
    private ActivityService activity;

    @Autowired
    private TestBoundTenantContext boundTenant;

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

        <T> T as(UUID tenantId, Supplier<T> read) {
            bound.set(tenantId);
            try {
                return read.get();
            } finally {
                bound.remove();
            }
        }

        @Override
        public Optional<UUID> currentTenantId() {
            return Optional.ofNullable(bound.get());
        }
    }

    /** Flyway runs with the application context, so seeding waits for the first test instance. */
    private static boolean seeded;

    @BeforeEach
    void grantAndSeedOnce() throws SQLException {
        if (seeded) {
            return;
        }
        seeded = true;
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            try (Statement sql = owner.createStatement()) {
                sql.execute("insert into public.tenant (id, code, status, time_zone) values"
                        + " ('" + TENANT_A + "', 'activity-tenant-a', 'ACTIVE', 'Etc/UTC'),"
                        + " ('" + TENANT_B + "', 'activity-tenant-b', 'ACTIVE', 'Etc/UTC')"
                        + " on conflict (id) do nothing");
            }
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            aOldChange = event(owner, TENANT_A, "CONFIG_CHANGED", today.minusDays(3));
            aNewChange = event(owner, TENANT_A, "CONFIG_CHANGED", today.minusDays(1));
            aRetry = event(owner, TENANT_A, "OPERATOR_RETRY", today.minusDays(2));
            aOutOfRange = event(owner, TENANT_A, "CONFIG_CHANGED", today.minusDays(20));
            bChange = event(owner, TENANT_B, "CONFIG_CHANGED", today.minusDays(1));
        }
    }

    /** One ledger row at noon UTC of {@code day}; {@code created_at} stays now() so the row lands in a live partition. */
    private static long event(Connection owner, UUID tenant, String action, LocalDate day) throws SQLException {
        try (PreparedStatement insert = owner.prepareStatement("insert into public.audit_event"
                + " (tenant_id, actor, action, target_ids, event_at) values (?::uuid, ?, ?, '[\"flow:1\"]'::jsonb, ?)"
                + " returning id")) {
            insert.setString(1, tenant.toString());
            insert.setString(2, "actor-" + tenant);
            insert.setString(3, action);
            insert.setObject(4, day.atTime(12, 0).atOffset(ZoneOffset.UTC));
            try (ResultSet id = insert.executeQuery()) {
                id.next();
                return id.getLong(1);
            }
        }
    }

    @Test
    void ownEventsNewestFirstFilteredByActionAndDate() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        ActivityService.ActivityPage page = boundTenant.as(TENANT_A,
                () -> activity.list("CONFIG_CHANGED", today.minusDays(7), today, null, null));

        assertThat(page.items()).extracting(ActivityService.Activity::id)
                .as("only tenant A's CONFIG_CHANGED events inside the range, newest first")
                .containsExactly(Long.toString(aNewChange), Long.toString(aOldChange));
        assertThat(page.items()).extracting(ActivityService.Activity::actor).containsOnly("actor-" + TENANT_A);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void withoutAFilterTheTenantSeesAllItsOwnEventsAndNoneOfTheOther() {
        ActivityService.ActivityPage page = boundTenant.as(TENANT_A,
                () -> activity.list(null, null, null, null, null));

        assertThat(page.items()).extracting(ActivityService.Activity::id)
                .containsExactly(Long.toString(aNewChange), Long.toString(aRetry), Long.toString(aOldChange),
                        Long.toString(aOutOfRange))
                .doesNotContain(Long.toString(bChange));
    }

    @Test
    void thePageIsBoundedAndSaysThereIsMore() {
        ActivityService.ActivityPage first = boundTenant.as(TENANT_A, () -> activity.list(null, null, null, 0, 2));
        ActivityService.ActivityPage second = boundTenant.as(TENANT_A, () -> activity.list(null, null, null, 1, 2));

        assertThat(first.items()).extracting(ActivityService.Activity::id)
                .containsExactly(Long.toString(aNewChange), Long.toString(aRetry));
        assertThat(first.hasMore()).isTrue();
        assertThat(second.items()).extracting(ActivityService.Activity::id)
                .containsExactly(Long.toString(aOldChange), Long.toString(aOutOfRange));
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    @Tag("negative")
    void anotherTenantsEventIdGivesNothing() {
        assertThat(boundTenant.as(TENANT_A, () -> activity.event(bChange)))
                .as("tenant B's event id read by tenant A")
                .isEmpty();
        assertThat(boundTenant.as(TENANT_A, () -> activity.event(aNewChange)))
                .map(ActivityService.Activity::targetIds)
                .contains(java.util.List.of("flow:1"));
        assertThat(boundTenant.as(TENANT_B, () -> activity.event(bChange))).isPresent();
    }

    @Test
    void eventTimesAreAnsweredAsUtcInstants() {
        ActivityService.Activity event = boundTenant.as(TENANT_A, () -> activity.event(aNewChange)).orElseThrow();

        assertThat(OffsetDateTime.parse(event.at()).getOffset()).isEqualTo(ZoneOffset.UTC);
    }
}
