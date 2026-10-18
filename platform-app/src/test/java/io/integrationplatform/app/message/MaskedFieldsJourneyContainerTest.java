package io.integrationplatform.app.message;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.config.flow.api.MaskedFields;
import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-066 / E12-F1-S2 (row 278 T108), base-suite probe BS-11: a flow whose interface version masks {@code customerId}
 * accepts a message carrying {@code MASK-PROBE-7Q3Z}; the target refuses it and echoes the value in its answer. The
 * probe must appear neither in the failures read (the tracking API's {@code lastError}) nor in any log line the app
 * writes. Seeded by the owner on the real tables (with the V2_2_1210 column in place), read over the restricted login.
 */
@Tag("container")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MaskedFieldsJourneyContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final String PROBE = "MASK-PROBE-7Q3Z";
    private static final String PAYLOAD = "{\"customerId\":\"" + PROBE + "\",\"orderId\":\"o-bs11\"}";

    private static final UUID TENANT = UUID.fromString("0000000a-0000-4000-8000-0000000000b1");
    private static final UUID FLOW = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID MESSAGE = UUID.randomUUID();
    private static final UUID DELIVERY = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    private static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(10);
    private static final LocalDate TODAY = LocalDate.ofInstant(T0, ZoneOffset.UTC);

    private static PlatformTestWiring.RestrictedLogin login;

    @Autowired
    private MessageTrackingService tracking;

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

    /** Seeded from the first test, once the context (and Flyway) is up. */
    private static boolean seeded;

    private static synchronized void seedOnce() throws SQLException {
        if (seeded) {
            return;
        }
        seeded = true;
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            // ponytail: the probe needs the version's snapshot only; its contract/author parents are not under test,
            // so the owner seed skips the FK triggers for this connection instead of building the whole config chain.
            try (Statement replica = owner.createStatement()) {
                replica.execute("set session_replication_role = replica");
            }
            run(owner, "insert into public.tenant (id, code, status, time_zone)"
                    + " values (?::uuid, 'masked-journey', 'ACTIVE', 'Etc/UTC')", TENANT);
            run(owner, "insert into public.interface (id, tenant_id, code, name, current_version, published_version,"
                    + " status, masked_fields) values (?::uuid, ?::uuid, 'bs11-orders', 'BS-11 orders', 1, 1,"
                    + " 'PUBLISHED', '[\"customerId\"]'::jsonb)", FLOW, TENANT);
            run(owner, "insert into public.interface_version (id, tenant_id, interface_id, version, status,"
                    + " business_key_rule, snapshot, source_contract_version_id, author_id)"
                    + " values (?::uuid, ?::uuid, ?::uuid, 1, 'PUBLISHED', '$.orderId', ?::jsonb, ?::uuid, ?::uuid)",
                    VERSION, TENANT, FLOW,
                    "{\"interface\":{\"code\":\"bs11-orders\",\"businessKeyRule\":\"$.orderId\","
                            + "\"maskedFields\":[\"customerId\"]}}",
                    UUID.randomUUID(), UUID.randomUUID());
            run(owner, "insert into public.message (id, tenant_id, interface_id, interface_version_id,"
                    + " source_contract_version_id, business_key, state, accepted_at, updated_at)"
                    + " values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'o-bs11', 'ROUTED', ?, ?)",
                    MESSAGE, TENANT, FLOW, VERSION, UUID.randomUUID(), at(T0), at(T0.plusMillis(100)));
            run(owner, "insert into public.payload (message_id, tenant_id, accepted_at, body)"
                    + " values (?::uuid, ?::uuid, ?, ?::jsonb)", MESSAGE, TENANT, at(T0), PAYLOAD);
            run(owner, "insert into public.delivery (id, tenant_id, state, created_at, updated_at, dead_letter_class,"
                    + " interface_id, target_id, message_id) values (?::uuid, ?::uuid, 'DEAD_LETTERED', ?, ?,"
                    + " 'PERMANENT', ?::uuid, ?::uuid, ?::uuid)",
                    DELIVERY, TENANT, at(T0), at(T0.plusSeconds(2)), FLOW, TARGET, MESSAGE);
            // the target echoes the masked value back in its refusal - the leak the read must close
            run(owner, "insert into public.delivery_attempt (tenant_id, delivery_id, layer, outcome_class, started_at,"
                    + " finished_at, detail) values (?::uuid, ?::uuid, 1, 'PERMANENT', ?, ?, ?)",
                    TENANT, DELIVERY, at(T0.plusMillis(950)), at(T0.plusSeconds(1)),
                    "HTTP 400 customerId " + PROBE + " unknown");
            run(owner, "insert into public.delivery_event (tenant_id, delivery_id, type, detail, occurred_at)"
                    + " values (?::uuid, ?::uuid, 'DEAD_LETTER', 'PERMANENT', ?)", TENANT, DELIVERY,
                    at(T0.plusSeconds(2)));
        }
    }

    @Test
    void theFailuresReadNeverHandsBackTheMaskedValueTheTargetEchoed() throws SQLException {
        seedOnce();

        List<MessageTrackingService.Failure> failures = boundTenant.as(TENANT, () -> tracking.failures(FLOW, TODAY));

        assertThat(failures).extracting(MessageTrackingService.Failure::trackingId)
                .containsExactly(MESSAGE.toString());
        MessageTrackingService.Failure refused = failures.get(0);
        assertThat(refused.lastError()).isEqualTo("HTTP 400 customerId " + MaskedFields.MARKER + " unknown");
        assertThat(refused.toString()).doesNotContain(PROBE);
    }

    @Test
    void noLogLineCarriesTheMaskedValueOnceIntakeHasSeenIt(CapturedOutput output) throws SQLException {
        seedOnce();
        // what intake does on acceptance (IntakeService): the version's masked paths pick the values to scrub
        MaskedFields.register(MaskedFields.valuesOf(JsonMapper.builder().build().readTree(PAYLOAD),
                List.of("customerId")));

        LoggerFactory.getLogger("io.integrationplatform.delivery")
                .warn("BS-11 delivery {} refused: HTTP 400 customerId {} unknown", DELIVERY, PROBE);
        boundTenant.as(TENANT, () -> tracking.failures(FLOW, TODAY));

        assertThat(output.getAll()).contains("BS-11 delivery").doesNotContain(PROBE);
    }

    // ------------------------------------------------------------------ owner-side seeding (RLS bypassed)

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void run(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                statement.setObject(i + 1, value instanceof UUID uuid ? uuid.toString() : value);
            }
            statement.executeUpdate();
        }
    }
}
