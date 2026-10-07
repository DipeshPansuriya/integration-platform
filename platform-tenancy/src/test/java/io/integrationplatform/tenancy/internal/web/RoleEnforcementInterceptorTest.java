package io.integrationplatform.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.Role;
import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.internal.principal.SourceBoundAuthentication;
import io.integrationplatform.tenancy.internal.principal.TenantBoundAuthentication;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import io.integrationplatform.tenancy.port.ApiOperationRules.Rule;
import io.integrationplatform.tenancy.port.ClientBinding;
import io.integrationplatform.tenancy.port.RoleGrants;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * T-099 without a database: the interceptor that enforces the role matrix on every {@code /api} handler of BOTH the
 * bearer chain and the console session chain (both bind a {@link TenantBoundAuthentication}). A source system's
 * machine principal holds no human role: 403 on every role-gated operation.
 */
class RoleEnforcementInterceptorTest {

    private static final String FLOW = "/api/v1/admin/flows/{flowId}";

    private final List<UUID> roleReads = new ArrayList<>();
    private Set<Role> granted = EnumSet.noneOf(Role.class);

    private final RoleGrants grants = userId -> {
        roleReads.add(userId);
        return granted;
    };

    private final ApiOperationRules rules = () -> List.of(
            new Rule("PUT", FLOW, Operation.EDIT_CONFIGURATION),
            new Rule("GET", "/api/v1/me", Operation.READ_OWN_IDENTITY),
            new Rule("POST", "/api/v1/messages", Operation.SUBMIT_MESSAGE));

    private final RoleEnforcementInterceptor interceptor =
            new RoleEnforcementInterceptor(List.of(rules), grants, TransactionOperations.withoutTransaction());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("A human holding an allowed role passes; the roles are read for the bound user")
    void anAllowedRolePasses() throws Exception {
        granted = EnumSet.of(Role.CONFIGURATOR);
        bindHuman();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("PUT", FLOW), response, handler())).isTrue();
        assertThat(roleReads).containsExactly(TenantFixtures.USER_ALICE);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A human without an allowed role gets 403 ROLE_NOT_GRANTED and the handler never runs")
    void aMissingRoleIs403() throws Exception {
        granted = EnumSet.of(Role.SUPPORT, Role.READ_ONLY);
        bindHuman();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("PUT", FLOW), response, handler())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo("{\"refusal\":\"ROLE_NOT_GRANTED\"}");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A machine (source-bound) principal holds no human role: 403 on an admin endpoint, no role read")
    void aSourceSystemIsRefusedAdminEndpoints() throws Exception {
        bindSource();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("PUT", FLOW), response, handler())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(roleReads).isEmpty();
        MockHttpServletResponse me = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("GET", "/api/v1/me"), me, handler())).isFalse();
        assertThat(me.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("Source-system-only operations admit a source and refuse every human, whatever its roles")
    void theCommonApiIsForSourcesOnly() throws Exception {
        bindSource();
        assertThat(interceptor.preHandle(request("POST", "/api/v1/messages"), new MockHttpServletResponse(),
                handler())).isTrue();
        granted = EnumSet.allOf(Role.class);
        bindHuman();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("POST", "/api/v1/messages"), response, handler())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("/api/v1/me needs only a bound human - no role, no role read")
    void readingOwnIdentityNeedsNoRole() throws Exception {
        bindHuman();
        assertThat(interceptor.preHandle(request("GET", "/api/v1/me"), new MockHttpServletResponse(), handler()))
                .isTrue();
        assertThat(roleReads).isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("No bound principal on a rule-bearing handler: 403 (the security chain answers 401 before this)")
    void noPrincipalIsRefused() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("PUT", FLOW), response, handler())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("A handler no main source declares (a test probe) has no rule: the completeness test guards main sources")
    void aHandlerWithoutARuleIsNotJudgedHere() throws Exception {
        assertThat(interceptor.preHandle(request("GET", "/api/test/rows"), new MockHttpServletResponse(), handler()))
                .isTrue();
        assertThat(interceptor.preHandle(request("GET", "/static"), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    @Test
    @Tag("negative")
    @DisplayName("Two rule sets declaring one endpoint is a start-up failure, never a silent pick")
    void duplicateRulesFailAtStartUp() {
        ApiOperationRules again = () -> List.of(new Rule("PUT", FLOW, Operation.READ_CONFIGURATION));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RoleEnforcementInterceptor(
                        List.of(rules, again), grants, TransactionOperations.withoutTransaction()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUT " + FLOW);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static MockHttpServletRequest request(String method, String pattern) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, pattern.replace("{flowId}", "f-1"));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        return request;
    }

    private static HandlerMethod handler() throws NoSuchMethodException {
        Method method = RoleEnforcementInterceptorTest.class.getDeclaredMethod("handler");
        return new HandlerMethod(new RoleEnforcementInterceptorTest(), method);
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("s")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static void bindHuman() {
        SecurityContextHolder.getContext().setAuthentication(new TenantBoundAuthentication(
                new JwtAuthenticationToken(jwt(Map.of())), TenantFixtures.ALICE_BINDING));
    }

    private static void bindSource() {
        ClientBinding binding = new ClientBinding(UUID.randomUUID(), "ACTIVE", UUID.randomUUID(), "CUSTOMER-A",
                TenantFixtures.TENANT_A, "tenant-a", "ACTIVE", java.time.ZoneId.of("Etc/UTC"));
        SecurityContextHolder.getContext().setAuthentication(new SourceBoundAuthentication(
                new JwtAuthenticationToken(jwt(Map.of("azp", "src-a"))), binding));
    }
}
