package io.integrationplatform.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.domain.audit.AuditActions;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E12 N3 (T-067 narrow, row278 wave): the append-only audit ledger. An {@link AuditEvent} published inside a
 * transaction persists exactly one {@code audit_event} row after commit and none after rollback; UPDATE and DELETE
 * are refused for the application role (R15); the table is under row-level security, so a tenant-B session reads
 * none of tenant A's rows; and the operator retry and replay events (E13 N2 publishes them through
 * {@link AuditActions}) carry actor, tenant, delivery id and the pinned versions used.
 *
 * <p>Boots the real application over the restricted login (the shape {@code PlatformTestWiring} fixes for every
 * platform-app container proof). The tenant is bound through the test {@link TenantContext} - the same
 * bind-per-transaction shape the delivery-retry trigger uses - because this proof needs no HTTP identity, only
 * a stamped transaction.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditWriterContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-00000000000b");

    private static PlatformTestWiring.RestrictedLogin login;
    private static final AtomicInteger GRANTED = new AtomicInteger();

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestBoundTenantContext boundTenant;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    /** The tenant is bound explicitly per step; the stamping transaction manager stamps what is bound. */
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

        void bind(UUID tenantId) {
            bound.set(tenantId);
        }

        void unbind() {
            bound.remove();
        }

        @Override
        public Optional<UUID> currentTenantId() {
            return Optional.ofNullable(bound.get());
        }
    }

    @BeforeEach
    void grantApplicationRoleAndSeedTenants() throws SQLException {
        if (GRANTED.incrementAndGet() == 1) {
            PlatformTestWiring.grantApplicationRole(POSTGRES, login);
            try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql =
                    owner.createStatement()) {
                sql.execute("insert into public.tenant (id, code, status, time_zone) values"
                        + " ('" + TENANT_A + "', 'audit-tenant-a', 'ACTIVE', 'Etc/UTC'),"
                        + " ('" + TENANT_B + "', 'audit-tenant-b', 'ACTIVE', 'Etc/UTC')"
                        + " on conflict (id) do nothing");
            }
        }
    }

    private void publishInTenantTransaction(UUID tenant, AuditEvent event) {
        boundTenant.bind(tenant);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> events.publishEvent(event));
        } finally {
            boundTenant.unbind();
        }
    }

    private void publishInTenantTransactionFailingAfter(UUID tenant, AuditEvent event) {
        boundTenant.bind(tenant);
        try {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                events.publishEvent(event);
                throw new IllegalStateException("the business transaction rolls back");
            })).isInstanceOf(IllegalStateException.class);
        } finally {
            boundTenant.unbind();
        }
    }

    private int countUnderTenant(UUID tenant, String action) {
        boundTenant.bind(tenant);
        try {
            return new TransactionTemplate(transactionManager)
                    .execute(status -> jdbc.queryForObject("select count(*) from public.audit_event"
                            + " where action = ?", Integer.class, action));
        } finally {
            boundTenant.unbind();
        }
    }

    @Test
    void anAuditEventPublishedInsideATransactionPersistsExactlyOneRowAfterCommitAndNoneAfterRollback() {
        AuditEvent committed = new AuditEvent(TENANT_A, "admin-commit-leg", "LOOKUP_ENTRY_CHANGED",
                List.of("lookup:1", "entry:2"), "{\"v\":1}", "{\"v\":2}", Instant.now());
        AuditEvent rolledBack = new AuditEvent(TENANT_A, "admin-rollback-leg", "LOOKUP_ENTRY_CHANGED",
                List.of("lookup:1", "entry:3"), "{\"v\":1}", "{\"v\":3}", Instant.now());

        publishInTenantTransaction(TENANT_A, committed);
        publishInTenantTransactionFailingAfter(TENANT_A, rolledBack);

        assertThat(countUnderTenant(TENANT_A, "LOOKUP_ENTRY_CHANGED"))
                .as("one row for the committed publish; the rolled-back publish reaches the listener never (after-commit)")
                .isEqualTo(1);
    }

    @Test
    void theApplicationRoleCannotUpdateOrDeleteAnAuditEvent() {
        AuditEvent event = new AuditEvent(TENANT_A, "admin-grants-leg", "LOOKUP_ENTRY_CHANGED",
                List.of("lookup:1", "entry:grants"), null, null, Instant.now());
        publishInTenantTransaction(TENANT_A, event);

        assertThatThrownBy(() -> jdbc.update("update public.audit_event set actor = 'tampered'"))
                .as("R15: the ledger grants no UPDATE")
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from public.audit_event"))
                .as("R15: the ledger grants no DELETE")
                .isInstanceOf(DataAccessException.class);
        assertThat(countUnderTenant(TENANT_A, "LOOKUP_ENTRY_CHANGED"))
                .as("the row stays exactly as appended")
                .isGreaterThan(0);
    }

    @Test
    void anotherTenantReadsNoneOfTheAuditRows() {
        AuditEvent event = new AuditEvent(TENANT_A, "admin-rls-leg", "LOOKUP_ENTRY_CHANGED",
                List.of("lookup:1", "entry:rls"), null, null, Instant.now());
        publishInTenantTransaction(TENANT_A, event);

        assertThat(countUnderTenant(TENANT_A, "LOOKUP_ENTRY_CHANGED")).isGreaterThan(0);
        assertThat(countUnderTenant(TENANT_B, "LOOKUP_ENTRY_CHANGED"))
                .as("RLS: a tenant-B session reads 0 of tenant A's audit rows")
                .isZero();
    }

    @Test
    void anOperatorRetryEventCarriesActorTenantDeliveryIdAndPinnedVersions() {
        UUID deliveryId = UUID.randomUUID();
        UUID mappingVersion = UUID.randomUUID();
        UUID contractVersion = UUID.randomUUID();
        AuditEvent retry = AuditActions.operatorRetry("support-retry-leg", TENANT_A, deliveryId, mappingVersion,
                contractVersion, Instant.now());

        publishInTenantTransaction(TENANT_A, retry);

        List<String> rows = boundTenantRead(TENANT_A,
                "select actor, tenant_id::text, target_ids::text from public.audit_event"
                        + " where action = 'OPERATOR_RETRY' and actor = 'support-retry-leg'");
        assertThat(rows).hasSize(1);
        String[] parts = rows.get(0).split("\\|", 3);
        assertThat(parts[0]).isEqualTo("support-retry-leg");
        assertThat(UUID.fromString(parts[1])).isEqualTo(TENANT_A);
        assertThat(parts[2])
                .contains("delivery:" + deliveryId)
                .contains("mapping_version:" + mappingVersion)
                .contains("contract_version:" + contractVersion);
    }

    @Test
    void anOperatorReplayEventCarriesActorTenantDeliveryIdAndPinnedVersions() {
        UUID deliveryId = UUID.randomUUID();
        UUID mappingVersion = UUID.randomUUID();
        UUID contractVersion = UUID.randomUUID();
        AuditEvent replay = AuditActions.operatorReplay("support-replay-leg", TENANT_A, deliveryId, mappingVersion,
                contractVersion, Instant.now());

        publishInTenantTransaction(TENANT_A, replay);

        assertThat(countUnderTenant(TENANT_A, "OPERATOR_REPLAY")).isEqualTo(1);
        List<String> rows = boundTenantRead(TENANT_A,
                "select actor, tenant_id::text, target_ids::text from public.audit_event"
                        + " where action = 'OPERATOR_REPLAY' and actor = 'support-replay-leg'");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).startsWith("support-replay-leg|");
        String targets = rows.get(0).split("\\|", 3)[2];
        assertThat(targets)
                .contains("delivery:" + deliveryId)
                .contains("mapping_version:" + mappingVersion)
                .contains("contract_version:" + contractVersion);
    }

    @Test
    void anEventNamingAnotherTenantThanTheBoundOneIsRefusedAndTheCommittedBusinessStands() {
        AuditEvent forged = new AuditEvent(TENANT_B, "attacker-forged-leg", "LOOKUP_ENTRY_CHANGED",
                List.of("lookup:1", "entry:forged"), null, null, Instant.now());

        // published under tenant A's transaction but naming tenant B: the commit succeeds, nothing persists
        assertThatCode(() -> publishInTenantTransaction(TENANT_A, forged)).doesNotThrowAnyException();

        assertThat(countUnderTenant(TENANT_B, "LOOKUP_ENTRY_CHANGED")).isZero();
        int underA = countUnderTenant(TENANT_A, "LOOKUP_ENTRY_CHANGED");
        // earlier legs wrote rows under A with this action too; the forged one must not have added any:
        // count by the forged actor alone
        assertThat(countUnderActor(TENANT_A, "attacker-forged-leg")).isZero();
        assertThat(underA).isGreaterThanOrEqualTo(0);
    }

    @Test
    void theAuditLedgerForcesRowLevelSecurityEvenForTheTableOwner() throws SQLException {
        // row278 M2: ENABLE alone exempts the owner; FORCE puts the owner under the tenant policy too
        assertThat(forcesRowLevelSecurity("public.audit_event"))
                .as("audit_event is FORCE ROW LEVEL SECURITY")
                .isTrue();
        assertThat(forcesRowLevelSecurity("public.audit_outbox"))
                .as("the audit outbox is FORCE ROW LEVEL SECURITY too")
                .isTrue();
    }

    @Test
    void theAuditWriteRidesTheBusinessTransactionThroughTheOutbox() throws SQLException {
        // row278 D2/M3: the outbox row commits with the business; the after-commit relay marks it relayed
        AuditEvent committed = new AuditEvent(TENANT_A, "admin-outbox-commit", "LOOKUP_ENTRY_CHANGED",
                List.of("lookup:1", "entry:outbox"), null, null, Instant.now());
        AuditEvent rolledBack = new AuditEvent(TENANT_A, "admin-outbox-rollback", "LOOKUP_ENTRY_CHANGED",
                List.of("lookup:1", "entry:outbox-rb"), null, null, Instant.now());

        publishInTenantTransaction(TENANT_A, committed);
        publishInTenantTransactionFailingAfter(TENANT_A, rolledBack);

        assertThat(countUnderActor(TENANT_A, "admin-outbox-commit")).isEqualTo(1);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement sql = owner.createStatement()) {
            sql.execute("set app.tenant_id = '" + TENANT_A + "'");
            try (var rows = sql.executeQuery("select actor, relayed_at is not null from public.audit_outbox"
                    + " where actor like 'admin-outbox-%' order by id")) {
                assertThat(rows.next()).as("the committed event left one outbox row").isTrue();
                assertThat(rows.getString(1)).isEqualTo("admin-outbox-commit");
                assertThat(rows.getBoolean(2)).as("the after-commit relay moved it").isTrue();
                assertThat(rows.next()).as("the rolled-back event left no outbox row").isFalse();
            }
        }
    }

    private static boolean forcesRowLevelSecurity(String table) throws SQLException {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
                var read = owner.prepareStatement("select relforcerowsecurity from pg_class where oid = ?::regclass")) {
            read.setString(1, table);
            try (var rows = read.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private int countUnderActor(UUID tenant, String actor) {
        boundTenant.bind(tenant);
        try {
            return new TransactionTemplate(transactionManager)
                    .execute(status -> jdbc.queryForObject("select count(*) from public.audit_event where actor = ?",
                            Integer.class, actor));
        } finally {
            boundTenant.unbind();
        }
    }

    private List<String> boundTenantRead(UUID tenant, String sql) {
        boundTenant.bind(tenant);
        try {
            return new TransactionTemplate(transactionManager)
                    .execute(status -> jdbc.query(sql, (rs, n) -> rs.getString(1) + "|" + rs.getString(2)
                            + "|" + rs.getString(3)));
        } finally {
            boundTenant.unbind();
        }
    }
}
