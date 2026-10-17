package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.application.CredentialAudit;
import io.integrationplatform.config.credential.application.SourceCredentialService;
import io.integrationplatform.config.credential.application.TargetCredentialService;
import io.integrationplatform.config.credential.crypto.EnvironmentKeyEncryptionKeys;
import io.integrationplatform.config.credential.readiness.CredentialKeyReferences;
import io.integrationplatform.config.credential.readiness.TargetCredentialKeysHealthIndicator;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.SourceCredentialAdmin;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E5 lane C (T-103, T-185) composition, in lane C's own file beside the systems (lane B) and contracts (lane A) ones:
 * the target-credential repository (also the send step's vault), the deployment's key-encryption keys, the two
 * credential services, and the readiness check of the keys (bean name {@value #KEYS_INDICATOR}, so the readiness group
 * names it {@code targetCredentialKeys} in config-secrets.yml).
 *
 * <p>The source-credential lifecycle is the tenancy module's {@link SourceCredentialAdmin}, taken through an
 * {@link ObjectProvider}: a composition without it (the E3/E6 suite apps scan this module but not tenancy's
 * {@code internal.credential}) still starts, and the source endpoints answer 503 there. The start-up half of the key
 * check reads the referenced key ids through {@link CredentialKeyReferences} when a bean of it exists - none yet (the
 * reviewed tenant-boundary crossing it needs is a Needs decision of the T44 hand-back).
 */
@Configuration(proxyBeanMethods = false)
class CredentialPersistenceConfiguration {

    static final String KEYS_INDICATOR = "targetCredentialKeysHealthIndicator";

    @Bean
    CredentialTargetRepository credentialTargetRepository(JdbcClient jdbcClient,
            ObjectProvider<TenantContext> tenantContexts) {
        // read at each call, like the stamp: with no TenantContext bean every call fails closed
        return new CredentialTargetRepository(jdbcClient,
                () -> Optional.ofNullable(tenantContexts.getIfAvailable()).flatMap(TenantContext::currentTenantId));
    }

    @Bean
    EnvironmentKeyEncryptionKeys keyEncryptionKeys(Environment environment) {
        return new EnvironmentKeyEncryptionKeys(environment);
    }

    @Bean
    CredentialAudit credentialAudit(ApplicationEventPublisher events, PlatformTransactionManager transactions) {
        // E12-F2-S1 (T-134): each credential action's audit event is published in a write transaction (joined
        // when the target save's is open) so the AFTER_COMMIT audit writer persists it
        return new CredentialAudit(events, new TransactionTemplate(transactions));
    }

    @Bean
    TargetCredentialService targetCredentialService(CredentialTargetRepository targets,
            PlatformTransactionManager transactions, KeyEncryptionKeys keys, CredentialAudit audit) {
        return new TargetCredentialService(targets, new TransactionTemplate(transactions), keys, audit);
    }

    @Bean
    SourceCredentialService sourceCredentialService(JdbcSystemRepository systems,
            ObjectProvider<SourceCredentialAdmin> admins, PlatformTransactionManager transactions,
            CredentialAudit audit) {
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        return new SourceCredentialService(systems, admins, readOnly, audit);
    }

    @Bean(KEYS_INDICATOR)
    TargetCredentialKeysHealthIndicator targetCredentialKeysHealthIndicator(KeyEncryptionKeys keys,
            ObjectProvider<CredentialKeyReferences> references) {
        CredentialKeyReferences referenced = references.getIfAvailable(() -> Set::of);
        return new TargetCredentialKeysHealthIndicator(keys, referenced);
    }
}
