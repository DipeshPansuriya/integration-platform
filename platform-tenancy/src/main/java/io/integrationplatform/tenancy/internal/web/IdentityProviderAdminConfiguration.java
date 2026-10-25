package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.internal.credential.IdpDisableReconciliation;
import io.integrationplatform.tenancy.internal.credential.SourceCredentialRevocation;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-186 wiring: the identity-provider admin adapter (port {@link IdentityProviderAdmin}, E5 T-103 issues through it),
 * the revoke order and the credential reconciliation. Present only where the admin client's secret is configured
 * ({@value KeycloakAdminAdapter#ADMIN_SECRET_VARIABLE}, an environment variable NAME, R07): a deployment without it has
 * no way to administer clients, and anything that needs one fails at start-up instead of half-revoking.
 *
 * <p>Settings: {@code platform.tenancy.identity-admin.client-id} (default {@value #DEFAULT_ADMIN_CLIENT}, lane D's
 * realm), the realm from {@code OIDC_ISSUER_URI}; the
 * optional {@code platform.tenancy.identity-admin.base-uri} (env {@code KEYCLOAK_ADMIN_BASE_URI}) moves the admin REST
 * calls to an internal address when the public edge hides /admin (tokens and the issuer stay public); the retry and breaker settings are the shared
 * {@code platform.tenancy.identity-provider.*} ones (one breaker for the provider).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = KeycloakAdminAdapter.ADMIN_SECRET_VARIABLE)
class IdentityProviderAdminConfiguration {

    static final String ADMIN_CLIENT_ID = "platform.tenancy.identity-admin.client-id";
    static final String DEFAULT_ADMIN_CLIENT = "platform-admin";
    /** Optional (env KEYCLOAK_ADMIN_BASE_URI via tenancy-security.yml): where the admin REST calls go; unset = the issuer's host. */
    static final String ADMIN_BASE_URI = "platform.tenancy.identity-admin.base-uri";

    /** The reconciliation's first wait after a failed disable, doubling per attempt up to the cap. */
    static final Duration RECONCILE_INITIAL_BACKOFF = Duration.ofSeconds(30);
    static final Duration RECONCILE_MAX_BACKOFF = Duration.ofMinutes(15);

    @Bean
    IdentityProviderAdmin identityProviderAdmin(Environment environment, ObjectProvider<MeterRegistry> meters,
            ObjectProvider<ObservationRegistry> observations) {
        ObservationRegistry observationRegistry = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        return new KeycloakAdminAdapter(
                environment.getRequiredProperty(TenancySecurityConfiguration.ISSUER_URI).strip(),
                environment.getProperty(ADMIN_BASE_URI),
                environment.getProperty(ADMIN_CLIENT_ID, DEFAULT_ADMIN_CLIENT),
                () -> environment.getRequiredProperty(KeycloakAdminAdapter.ADMIN_SECRET_VARIABLE),
                KeycloakAdminAdapter.restTemplate(IdentityProviderSettings.from(environment),
                        meters.getIfAvailable(CompositeMeterRegistry::new), observationRegistry),
                observationRegistry);
    }

    @Bean
    SourceCredentialRevocation sourceCredentialRevocation(SourceCredentialStore store,
            PlatformTransactionManager transactions, IdentityProviderAdmin identityProviderAdmin) {
        return new SourceCredentialRevocation(store, new TransactionTemplate(transactions), identityProviderAdmin);
    }

    @Bean
    IdpDisableReconciliation idpDisableReconciliation(SourceCredentialStore store,
            PlatformTransactionManager transactions, IdentityProviderAdmin identityProviderAdmin) {
        return new IdpDisableReconciliation(store, new TransactionTemplate(transactions), identityProviderAdmin,
                Clock.systemUTC(), RECONCILE_INITIAL_BACKOFF, RECONCILE_MAX_BACKOFF);
    }
}
