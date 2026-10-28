package io.integrationplatform.app.audit;

import static io.integrationplatform.app.audit.AuditContainerSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.domain.audit.AuditEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Row 544 (option A, L4, container half): with {@code ipaas.audit.relay.mode=LEGACY} the relay uses the previous
 * UPDATE+INSERT statement, which works only after the documented rollback SQL of V2_4_1490 re-granted the two
 * privileges - the flag alone is not a rollback. The default (DEFINER) is exercised by every other audit container
 * test; an unknown value failing the start is {@code RelayModeTest}'s.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ipaas.audit.relay.mode=LEGACY")
class AuditRelayModeContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static PlatformTestWiring.RestrictedLogin login;
    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    @Autowired
    private JdbcAuditEventStore store;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AuditContainerSupport.BoundTenant boundTenant;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @TestConfiguration
    static class TenantBindingConfiguration {

        @Bean
        @Primary
        AuditContainerSupport.BoundTenant boundTenant() {
            return new AuditContainerSupport.BoundTenant();
        }
    }

    @BeforeEach
    void seed() throws SQLException {
        if (SEEDED.compareAndSet(false, true)) {
            AuditContainerSupport.seed(POSTGRES, login);
            // the documented rollback of the lockdown, exactly as written in the migration header
            try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql =
                    owner.createStatement()) {
                sql.execute(AuditContainerSupport.rollbackSql(
                        AuditContainerSupport.migration("V*__audit_relay_move_and_lockdown.sql")));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void legacyModeMovesRowsWithTheDirectStatementAfterTheRollbackSqlRegrantedThePrivileges() throws SQLException {
        boundTenant.bind(TENANT_A);
        try {
            Integer moved = new TransactionTemplate(transactionManager).execute(status -> {
                store.append(new AuditEvent(TENANT_A, "legacy-actor", "LEGACY_MODE", List.of("k:1"), null, null,
                        Instant.parse("2026-03-04T05:06:07Z")));
                return store.relayBound(50);
            });
            assertThat(moved).isEqualTo(1);
        } finally {
            boundTenant.unbind();
        }
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            assertThat(AuditContainerSupport.count(owner,
                    "select count(*) from public.audit_event where action = 'LEGACY_MODE'")).isEqualTo(1);
            assertThat(AuditContainerSupport.count(owner,
                    "select count(*) from public.audit_outbox where action = 'LEGACY_MODE' and relayed_at is not null"))
                    .isEqualTo(1);
        }
    }
}
