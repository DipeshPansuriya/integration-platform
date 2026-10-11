package io.integrationplatform.e5.contract;

import static io.integrationplatform.e5.contract.E5ContractFixture.TARGET_A_V1;
import static io.integrationplatform.e5.contract.E5ContractFixture.TARGET_B_V2_OPENAPI;
import static io.integrationplatform.e5.contract.E5ContractFixture.importApproved;
import static io.integrationplatform.e5.contract.E5ContractFixture.registerTargetSystem;
import static io.integrationplatform.e5.contract.E5ContractFixture.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import io.integrationplatform.config.contract.api.ContractVersions;
import io.integrationplatform.config.contract.api.UsableContractVersion;
import io.integrationplatform.e5.contract.E5ContractFixture.Tenant;
import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.internal.principal.TenantBoundAuthentication;
import io.integrationplatform.tenancy.port.LoginBinding;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E5-F2-S1 (T-106; ADR-011 Decision 3 rules 3 and 5; E5-C7, E5-C8) on ONE running application context:
 *
 * <pre>
 * Scenario: A new version with 0 deploys
 *   Given a running system
 *   When a new target contract version is added, approved and published
 *   Then it is usable at once with 0 deploys and 0 restarts
 *
 * Scenario: Every message records its contract version
 *   Given a message processed for Target B
 *   When its delivery is read
 *   Then it names contract version v2.0
 * </pre>
 *
 * <p>"Usable" under ADR-011 Decision 3 is APPROVED: a contract version has no publish step of its own (publishing is
 * the interface version's, rule 6), and RETIRED means no new pins. The common API (E11 T-048) and the delivery creation
 * (E9) do not exist yet, so "a message processed for Target B" runs at the deepest existing seam, inside the running
 * context's own beans and its tenant-stamping transaction manager: the config module's {@link ContractVersions} port
 * resolves Target B's usable target contract version, and the ingest module's {@link OutboxWriter} records it on the
 * outbox row of the delivery-due record (additive {@code contract_version_id}, V2_1_2). The delivery table carries the
 * same additive column (V2_3_3_1) for the delivery epic to write. 0 deploys: the same context (id and start time), no
 * migration applied during the walk. Container group (R03): PostgreSQL.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(E5ZeroDeployContractVersionContainerTest.SuiteDecoder.class)
class E5ZeroDeployContractVersionContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static RestrictedLogin login;

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ContractVersions contractVersions;

    @Autowired
    private OutboxWriter outbox;

    @Autowired
    private PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SuiteDecoder {

        @Bean
        @Primary
        JwtDecoder e5ZeroDeploySuiteJwtDecoder() {
            return E5ContractFixture.subjectDecoder();
        }
    }

    @Test
    @DisplayName("Scenario: A new version with 0 deploys + Every message records its contract version (v2.0 for Target B)")
    void aNewTargetContractVersionIsUsableAtOnceAndEveryMessageRecordsIt() throws Exception {
        Tenant tenant = E5ContractFixture.seedTenant(POSTGRES, "zd", "TENANT_ADMIN", "CONFIGURATOR", "APPROVER");
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        String author = tenant.subject("CONFIGURATOR");
        String approver = tenant.subject("APPROVER");
        String targetA = registerTargetSystem(port, tenant, "tgt-a");
        String targetB = registerTargetSystem(port, tenant, "tgt-b");

        // Given a running system: Target A and Target B both on an approved contract v1.0
        importApproved(port, author, approver, targetA, "1.0", "JSON_SCHEMA", TARGET_A_V1);
        importApproved(port, author, approver, targetB, "1.0", "JSON_SCHEMA", TARGET_A_V1);
        String contextId = context.getId();
        long startedAt = context.getStartupDate();
        String migrations = scalar(POSTGRES, "SELECT count(*)::text FROM flyway_schema_history");
        assertThat(labelRecordedFor(processMessageFor(tenant, targetB))).as("before: Target B on v1.0")
                .isEqualTo(targetB + "@1.0");

        // When a new target contract version is added (OpenAPI import), submitted and approved by another user
        String v20 = importApproved(port, author, approver, targetB, "2.0", "OPENAPI", TARGET_B_V2_OPENAPI);

        // Then it is usable at once: the very next message processed for Target B names v2.0 ...
        long messageForB = processMessageFor(tenant, targetB);
        assertThat(labelRecordedFor(messageForB)).isEqualTo(targetB + "@2.0");
        assertThat(scalar(POSTGRES, "SELECT contract_version_id::text FROM outbox WHERE id = ?::bigint",
                Long.toString(messageForB))).isEqualTo(v20);
        // ... while Target A stays on its own v1.0 (side by side)
        assertThat(labelRecordedFor(processMessageFor(tenant, targetA))).isEqualTo(targetA + "@1.0");

        // ... with 0 restarts and 0 deploys: the same context, no migration applied
        assertThat(context.getId()).isEqualTo(contextId);
        assertThat(context.getStartupDate()).as("the context never restarted").isEqualTo(startedAt);
        assertThat(scalar(POSTGRES, "SELECT count(*)::text FROM flyway_schema_history"))
                .as("no migration ran while the version was added").isEqualTo(migrations);
    }

    @Test
    @DisplayName("The delivery records its target contract version too: an additive nullable uuid column (V2_3_3_1)")
    void theDeliveryCarriesTheContractVersionColumn() throws Exception {
        assertThat(scalar(POSTGRES, "SELECT coalesce((SELECT data_type || '|' || is_nullable"
                + " FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'delivery'"
                + " AND column_name = 'contract_version_id'), 'missing')")).isEqualTo("uuid|YES");
    }

    /**
     * One message for one target, processed at the deepest existing seam inside the running context: tenant bound
     * the way a request binds it, one transaction of the context's own (tenant-stamping) transaction manager, the
     * target's usable contract version resolved and recorded on the delivery-due outbox row.
     */
    private long processMessageFor(Tenant tenant, String targetSystem) {
        SecurityContext security = SecurityContextHolder.createEmptyContext();
        Jwt jwt = Jwt.withTokenValue("e5-zero-deploy").header("alg", "none")
                .subject(tenant.subject("CONFIGURATOR")).build();
        security.setAuthentication(new TenantBoundAuthentication(new JwtAuthenticationToken(jwt),
                new LoginBinding(tenant.userId("CONFIGURATOR"), "ACTIVE", tenant.id(), tenant.code(), "ACTIVE",
                        ZoneId.of("Etc/UTC"))));
        SecurityContextHolder.setContext(security);
        try {
            Long outboxId = new TransactionTemplate(transactions).execute(status -> {
                UsableContractVersion usable = contractVersions.usableTargetVersion(targetSystem)
                        .orElseThrow(() -> new AssertionError("no usable contract version for " + targetSystem));
                return outbox.append(new OutboxEntry(OutboxTopic.DELIVERY_DUE, UUID.randomUUID(),
                        targetSystem + "/order-" + UUID.randomUUID(), null, null, usable.id()));
            });
            return outboxId;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** What the recorded row names: {@code <system>@<version label>}, read as the owner through the stored id. */
    private static String labelRecordedFor(long outboxId) throws Exception {
        return scalar(POSTGRES, "SELECT s.code || '@' || cv.version_label FROM outbox o"
                + " JOIN contract_version cv ON cv.id = o.contract_version_id"
                + " JOIN contract c ON c.id = cv.contract_id JOIN system s ON s.id = c.system_id"
                + " WHERE o.id = ?::bigint", Long.toString(outboxId));
    }
}
