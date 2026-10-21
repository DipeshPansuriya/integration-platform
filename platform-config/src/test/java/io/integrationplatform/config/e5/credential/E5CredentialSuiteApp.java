package io.integrationplatform.config.e5.credential;

import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import io.integrationplatform.suitesupport.SuiteSchedulerConfig;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * E5 lane C's composed running system: the config module's real controllers and services, the tenancy module's
 * stamping transaction manager, login bootstrap, the REAL OIDC resource-server chain with its role interceptor and the
 * real source-credential lifecycle (tenancy {@code internal.credential}). Two things are the suite's own:
 *
 * <ul>
 *   <li>the JWT verification key ({@link E5CredentialFixture#decoder()}; the configured issuer is never contacted);</li>
 *   <li>the identity provider's admin port - {@link #IDENTITY_PROVIDER}, which records every create and disable and can
 *       be told to fail; the real Keycloak adapter and its five controls are proven in platform-tenancy
 *       (KeycloakAdminAdapterTest, E5KeycloakTimeoutDuringIssueTest) and against the real realm in platform-app.</li>
 * </ul>
 *
 * Not a {@code @Configuration}, so the other config suite apps' component scans never pick it up (E5SystemSuiteApp's
 * javadoc names the trap).
 */
@EnableAutoConfiguration
@Import(SuiteSchedulerConfig.class)
@ComponentScan(basePackages = {
        "io.integrationplatform.config",
        "io.integrationplatform.tenancy.internal.persistence",
        "io.integrationplatform.tenancy.internal.principal",
        "io.integrationplatform.tenancy.internal.web",
        "io.integrationplatform.tenancy.internal.credential"})
public class E5CredentialSuiteApp {

    static final SuiteIdentityProvider IDENTITY_PROVIDER = new SuiteIdentityProvider();

    @Primary
    @Bean
    JwtDecoder e5CredentialSuiteTokenDecoder() {
        return E5CredentialFixture.decoder();
    }

    @Bean
    IdentityProviderAdmin e5CredentialSuiteIdentityProvider() {
        return IDENTITY_PROVIDER;
    }

    /** The suite's identity provider: records calls, fails on demand, probes the platform row during a create. */
    static final class SuiteIdentityProvider implements IdentityProviderAdmin {

        private static final SecureRandom RANDOM = new SecureRandom();

        final List<String> created = Collections.synchronizedList(new ArrayList<>());
        final List<String> disabled = Collections.synchronizedList(new ArrayList<>());
        volatile boolean failCreate;
        volatile boolean failDisable;
        volatile Consumer<String> duringCreate = clientId -> { };

        void reset() {
            failCreate = false;
            failDisable = false;
            duringCreate = clientId -> { };
        }

        @Override
        public String issuer() {
            return E5CredentialFixture.OIDC_ISSUER.toString();
        }

        @Override
        public CreatedClient createConfidentialClient(String clientId) {
            duringCreate.accept(clientId);
            if (failCreate) {
                throw new IdentityProviderAdminException("The identity provider did not create the client "
                        + clientId + "; nothing was created on the platform side - try again later", null);
            }
            created.add(clientId);
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            return new CreatedClient(clientId, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        }

        @Override
        public void disableClient(String clientId) {
            if (failDisable) {
                throw new IdentityProviderAdminException("The identity provider did not disable the client "
                        + clientId, null);
            }
            disabled.add(clientId);
        }
    }
}
