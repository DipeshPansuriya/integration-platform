package io.integrationplatform.tenancy.internal.principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.tenancy.port.ClientBootstrap;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import io.integrationplatform.tenancy.port.SchedulerRegistry;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * E14 T-127 follow-up: the operator-agent branch of {@link PrincipalTenantResolver}. An operator agent is a machine
 * credential registered in {@code scheduler.operator_credential} (platform-scoped, ADR-012 section 5): ACTIVE is an
 * {@link OperatorAgentAuthentication}; revoked or unknown is 401; a token that names a tenant is 403. Humans are still
 * resolved first and a non-operator client still takes the source path.
 */
class PrincipalTenantResolverOperatorTest {

    private static final String ISSUER = "https://idp.example/realms/platform";

    private final LoginBootstrap noHuman = (iss, sub) -> Optional.empty();
    private final SchedulerRegistry operators = mock(SchedulerRegistry.class);
    private final List<DeniedTenantAttempt> denials = new ArrayList<>();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/jobs/DAILY/run");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String azp, String tenant) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").issuer(ISSUER).subject("service-account");
        if (azp != null) {
            jwt.claim("azp", azp);
        }
        if (tenant != null) {
            jwt.claim("tenant", tenant);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build(), List.of()));
    }

    private PrincipalTenantResolver resolver(LoginBootstrap humans, ClientBootstrap clients) {
        return new PrincipalTenantResolver(humans, clients, operators, denials::add);
    }

    private static Authentication bound() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void anActiveOperatorCredentialBecomesAnOperatorAgent() throws Exception {
        when(operators.operatorCredentialActive(ISSUER, "ops-agent")).thenReturn(true);
        ClientBootstrap clients = mock(ClientBootstrap.class);
        authenticate("ops-agent", null);

        resolver(noHuman, clients).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(bound()).isInstanceOfSatisfying(OperatorAgentAuthentication.class,
                operator -> assertThat(operator.clientId()).isEqualTo("ops-agent"));
        verify(clients, never()).resolve(any(), any());
        assertThat(denials).isEmpty();
    }

    @Test
    void aRevokedOrUnknownOperatorCredentialIs401() throws Exception {
        when(operators.operatorCredentialActive(ISSUER, "ops-agent")).thenReturn(false);
        authenticate("ops-agent", null);

        resolver(noHuman, null).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(bound()).isNull();
        assertThat(denials).singleElement().satisfies(denial -> {
            assertThat(denial.channel()).isEqualTo(DeniedTenantAttempt.Channel.INACTIVE_CREDENTIAL);
            assertThat(denial.boundTenantId()).isNull();
        });
    }

    @Test
    void anOperatorTokenThatNamesATenantIsRefused() throws Exception {
        when(operators.operatorCredentialActive(ISSUER, "ops-agent")).thenReturn(true);
        authenticate("ops-agent", "acme");

        resolver(noHuman, null).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
        assertThat(bound()).isNotInstanceOf(OperatorAgentAuthentication.class);
        assertThat(denials).singleElement()
                .satisfies(denial -> assertThat(denial.channel()).isEqualTo(DeniedTenantAttempt.Channel.TOKEN_CLAIM));
    }

    @Test
    void aNonOperatorClientStillTakesTheSourcePath() throws Exception {
        when(operators.operatorCredentialActive(any(), any())).thenReturn(false);
        ClientBootstrap clients = mock(ClientBootstrap.class);
        when(clients.resolve(ISSUER, "source-1")).thenReturn(Optional.empty());
        authenticate("source-1", null);

        resolver(noHuman, clients).doFilter(request, response, chain);

        verify(clients).resolve(ISSUER, "source-1");
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(bound()).isNull();
    }

    @Test
    void aBoundHumanIsResolvedBeforeAnyOperatorLookup() throws Exception {
        LoginBinding human = new LoginBinding(UUID.randomUUID(), "ACTIVE", UUID.randomUUID(), "acme", "ACTIVE",
                ZoneOffset.UTC);
        authenticate("console", null);

        resolver((iss, sub) -> Optional.of(human), null).doFilter(request, response, chain);

        assertThat(bound()).isInstanceOf(TenantBoundAuthentication.class);
        verify(operators, never()).operatorCredentialActive(any(), any());
    }

    @Test
    void aTokenWithoutAzpIsStill403() throws Exception {
        authenticate(null, null);

        resolver(noHuman, null).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(operators, never()).operatorCredentialActive(any(), any());
    }

    @Test
    void theHumanOnlyResolverNeverConsultsOperators() throws Exception {
        authenticate("ops-agent", null);

        new PrincipalTenantResolver(noHuman, denials::add).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(operators, never()).operatorCredentialActive(any(), any());
    }
}
