package io.integrationplatform.tenancy.internal.credential;

import io.integrationplatform.tenancy.api.CredentialCleanup;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import io.integrationplatform.tenancy.port.SourceCredentialAdmin;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E5 T-103 wiring: the source-credential lifecycle as the public {@link SourceCredentialAdmin} port. Present in every
 * composition that has the store and a transaction manager; the identity-provider admin adapter is optional (it exists
 * only where {@code PLATFORM_ADMIN_CLIENT_SECRET} is configured, IdentityProviderAdminConfiguration) - without it issue
 * and rotate refuse cleanly and revoke still bites platform-first.
 *
 * <p>Settings (config/config-secrets.yml, optional): {@value #ROTATION_GRACE} (default {@code 7d}, ADR-007 Decision 3)
 * and {@value #PENDING_TIMEOUT} (default {@code 15m}, the age after which the reconciliation abandons an unfinished
 * issue).
 */
@Configuration(proxyBeanMethods = false)
class SourceCredentialLifecycleConfiguration {

    static final String ROTATION_GRACE = "platform.tenancy.source-credential.rotation-grace";
    static final String PENDING_TIMEOUT = "platform.tenancy.source-credential.pending-timeout";

    @Bean
    SourceCredentialLifecycle sourceCredentialLifecycle(SourceCredentialStore store,
            PlatformTransactionManager transactions, ObjectProvider<IdentityProviderAdmin> identityProvider,
            Environment environment) {
        Binder binder = Binder.get(environment);
        return new SourceCredentialLifecycle(store, new TransactionTemplate(transactions),
                Optional.ofNullable(identityProvider.getIfAvailable()),
                binder.bind(ROTATION_GRACE, Duration.class).orElse(Duration.ofDays(7)),
                binder.bind(PENDING_TIMEOUT, Duration.class).orElse(Duration.ofMinutes(15)));
    }

    /** E14 T-203: the clean-up pass the CREDENTIAL_CLEANUP job runs per bound tenant (public port in tenancy.api). */
    @Bean
    CredentialCleanup credentialCleanup(SourceCredentialLifecycle sourceCredentialLifecycle,
            ObjectProvider<IdpDisableReconciliation> reconciliation) {
        return new TenantCredentialCleanup(sourceCredentialLifecycle,
                Optional.ofNullable(reconciliation.getIfAvailable()));
    }
}
