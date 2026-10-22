package io.integrationplatform.routing.adapter.file;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.FileRoots;
import io.integrationplatform.config.destination.MqConnectionCheck;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import io.integrationplatform.tenancy.TenantContext;

/**
 * T-150: the File adapter's beans - the "FILE" test-connection check (same MqConnectionCheck interface the
 * checks map holds) and the production target call. Gated by its own property so no existing context changes
 * until the lead wires the checks map and the TargetCallRegistry entry; the gate flips with the integration.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("IPASS_FILE_TARGET_ENABLED")
public class FileAdapterConfiguration {

    /**
     * F4 (CWE-73/22): a File target's root is confined to {@code <operator base>/<bound tenant>/...}
     * ({@code platform.config.destinations.file-base-dirs}); no base configured or no tenant bound, nothing resolves.
     */
    @Bean
    FolderResolver fileFolderResolver(ObjectProvider<FileRoots> roots, TenantContext tenantContext) {
        return rootPath -> tenantContext.currentTenantId()
                .flatMap(tenant -> roots.getIfAvailable(FileRoots::none).resolve(rootPath, tenant));
    }

    @Bean
    MqConnectionCheck fileConnectionCheck(FolderResolver folders) {
        return (systemId, settingsJson) -> new FileTestConnection(id -> Optional.of(FileSettings.fromJson(settingsJson)),
                folders).test(systemId);
    }

    @Bean
    JdbcFileTargets fileTargets(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcFileTargets(jdbcClient, tenantContext);
    }

    /** Until the composition supplies the mapped name and body, a FILE delivery has no message (PERMANENT). */
    @Bean
    FileOutbound fileOutbound(JdbcFileTargets targets, ObjectProvider<FileTargetContent> contents) {
        return new FileTargetOutbound(targets::settingsOf, contents.getIfAvailable(() -> send -> Optional.empty()));
    }

    @Bean
    FileTargetCall fileTargetCall(FileOutbound outbound, FolderResolver folders,
            ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactionManager,
            ObjectProvider<CircuitBreakerRegistry> breakers,
            ObjectProvider<MeterRegistry> meters, ObjectProvider<ObservationRegistry> observations) {
        return compose(outbound, folders, transactionManager.getIfAvailable(),
                breakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                meters.getIfAvailable(SimpleMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    /**
     * T175 (T71 for FILE): every database read of the send (settings, mapped name and body) happens in
     * {@code resolve}, so it runs in ONE short tenant-stamped transaction that is over before the file is written.
     * The folder resolve and the write touch only the file system and hold no pooled connection.
     */
    static FileTargetCall compose(FileOutbound outbound, FolderResolver folders,
            org.springframework.transaction.PlatformTransactionManager transactions,
            CircuitBreakerRegistry breakers, MeterRegistry meters, ObservationRegistry observations) {
        if (transactions != null) {
            org.springframework.transaction.support.TransactionTemplate tx =
                    new org.springframework.transaction.support.TransactionTemplate(transactions);
            FileOutbound reads = outbound;
            outbound = send -> tx.execute(status -> reads.resolve(send));
        }
        return new FileTargetCall(outbound, folders, breakers, meters, observations);
    }
}
