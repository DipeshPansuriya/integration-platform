package io.integrationplatform.ingest.chaos;

import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.ingest.internal.relay.ActiveTenantSource;
import io.integrationplatform.ingest.internal.relay.RelayTenantContext;
import io.integrationplatform.ingest.port.PublishPort;
import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lane D chaos wiring (T-009): the smallest context that drives the real outbox pipeline end to end against
 * {@link OutboxChaosDatabase}.
 *
 * <ul>
 *   <li>Scans {@code io.integrationplatform.ingest} so lane A's {@code OutboxWriter} and lane B's
 *       {@code OutboxRelay} are picked up as beans by their seam interfaces the moment either is integrated
 *       (the E2 seam convention lane C set with {@code TenancyPersistenceConfiguration}); no lane's class name
 *       is referenced here.</li>
 *   <li>Scans {@code io.integrationplatform.tenancy.internal.persistence} to reuse lane C's
 *       {@code TenantStampingTransactionManager} - the stamping is not re-implemented in the test; it is the
 *       production path R11 mandates (the E1 {@code TenantBoundTransactionsContainerTest} used the same manager,
 *       constructed in-module because the package is closed).</li>
 *   <li>The {@link PublishPort} the relay gets is lane D's {@link FaultInjectingPublishPort} ({@code @Primary} -
 *       the decorator pattern the brief prescribes over editing any lane's file). If lane B registers its T-042
 *       in-memory adapter {@code @Primary} too, the collision is resolved by arjun-fullstack at integration.</li>
 *   <li>Flyway auto-run is switched off by the test class properties - the fixture migrates as the owner login,
 *       the way a deployment does; auto-config would try the restricted login, which holds no DDL rights.</li>
 * </ul>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {"io.integrationplatform.ingest", "io.integrationplatform.tenancy.internal.persistence"})
class OutboxChaosTestApplication {

    @Bean
    DataSource dataSource() {
        // Pool of 4: the seeding transaction, one per-tenant relay transaction per cycle, and headroom for a
        // relay that opens its claim and its mark in separate short transactions.
        return OutboxChaosDatabase.get().appPool(4);
    }

    /**
     * B43: the scan of {@code io.integrationplatform.ingest} also picks up the intake wiring, whose
     * {@code PayloadValidationService} (B37) needs the configuration module's rule port. The chaos scenarios
     * seed the outbox directly and never run an intake, so the port is never called; it fails loudly if a
     * scenario ever starts to, instead of silently passing records as valid.
     */
    @Bean
    RuleEvaluationPort chaosRuleEvaluationPort() {
        return (ruleSetId, input) -> {
            throw new UnsupportedOperationException("the chaos context runs no intake validation");
        };
    }

    @Bean
    ChaosTenantContext chaosTenantContext() {
        return new ChaosTenantContext();
    }

    @Bean
    @Primary
    FaultInjectingPublishPort faultInjectingPublishPort() {
        return new FaultInjectingPublishPort();
    }

    // ------------------------------------------------------------------ integration glue (row 278, the lead)

    /**
     * The relay's binding (integration glue, wave-0 integration): the real relay binds and unbinds its own
     * job context per cycle (ADR-011 Decision 8) - never the request context, never the suite's seeding context.
     * {@code RelayTenantContext} is package-private where it binds and unbinds; constructing the one shared
     * instance here is the composition the lanes agreed the integration lead owns.
     */
    @Bean
    RelayTenantContext relayTenantContext() {
        return new RelayTenantContext();
    }

    /**
     * The stamp source (integration glue): whichever context is bound wins, so one stamping transaction
     * manager serves both phases - seeding reads the suite's binding, a relay cycle reads the relay's. The
     * {@code @Primary} makes this the single candidate lane A's unqualified {@code TenantContext} injections
     * (R08) and the stamping manager's per-transaction lookup (R11) both resolve.
     */
    @Bean
    @Primary
    TenantContext stampSource(ChaosTenantContext chaosTenantContext, RelayTenantContext relayTenantContext) {
        return () -> chaosTenantContext.currentTenantId()
                .or(() -> relayTenantContext.currentTenantId());
    }

    /**
     * The active tenants the relay loops over (integration glue): read from the fixture's owner identity, the
     * way a deployment's directory crossing does - the application role sees the own directory row only
     * (V1_3_2 {@code tenant_dir_self}), so no app-path query can list every tenant.
     */
    @Bean
    ActiveTenantSource chaosActiveTenants() {
        return () -> {
            try (Connection owner = OutboxChaosDatabase.get().ownerConnection();
                    ResultSet rows = owner.createStatement()
                            .executeQuery("select id from public.tenant where status = 'ACTIVE'")) {
                List<UUID> ids = new ArrayList<>();
                while (rows.next()) {
                    ids.add(UUID.fromString(rows.getString(1)));
                }
                return ids;
            } catch (SQLException e) {
                throw new IllegalStateException("the fixture's active-tenant read failed", e);
            }
        };
    }

    @Bean
    TransactionTemplate chaosTransactionTemplate(PlatformTransactionManager transactionManager) {
        // The context's single PlatformTransactionManager is the scanned stamping manager (Boot backs off when a
        // TransactionManager bean exists) - so the seeding transaction here gets app.tenant_id stamped exactly
        // as a real intake transaction does, which is what lane A's OutboxWriter contract requires.
        return new TransactionTemplate(transactionManager);
    }
}
