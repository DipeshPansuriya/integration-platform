package io.integrationplatform.ingest.chaos;

import io.integrationplatform.ingest.port.PublishPort;
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

    @Bean
    ChaosTenantContext chaosTenantContext() {
        return new ChaosTenantContext();
    }

    @Bean
    @Primary
    FaultInjectingPublishPort faultInjectingPublishPort() {
        return new FaultInjectingPublishPort();
    }

    @Bean
    TransactionTemplate chaosTransactionTemplate(PlatformTransactionManager transactionManager) {
        // The context's single PlatformTransactionManager is the scanned stamping manager (Boot backs off when a
        // TransactionManager bean exists) - so the seeding transaction here gets app.tenant_id stamped exactly
        // as a real intake transaction does, which is what lane A's OutboxWriter contract requires.
        return new TransactionTemplate(transactionManager);
    }
}
