package io.integrationplatform.tenancy.internal.web;

import static io.integrationplatform.tenancy.internal.web.TenantFixtures.AUDIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * L19 / Karan ruling 2026-10-23 section 4: the tenancy chain permits anonymously EXACTLY {@code /actuator/health},
 * {@code /actuator/health/readiness} and {@code /actuator/health/liveness} - no {@code /actuator/health/**} wildcard,
 * and every other actuator path stays denied. Boot's real endpoints are not on this module's classpath, so a stub
 * answers the paths with the status-only body the real health endpoint gives while {@code show-details} and
 * {@code show-components} stay unset (neither is set in any {@code application*.yml}); what this pins is the chain's
 * decision per path. The real body is proved live by {@code SecurityGateS4IdentityContainerTest}.
 */
@WebMvcTest(
        controllers = ActuatorExactPermitTest.ActuatorStub.class,
        properties = {
            "spring.config.import=optional:classpath:config/tenancy-security.yml",
            "OIDC_ISSUER_URI=" + TenantFromIdentityOnlyTest.ISSUER,
            "OIDC_AUDIENCE=" + AUDIENCE
        })
@Import({TenancySecurityConfiguration.class, ActuatorExactPermitTest.Bootstrap.class,
        ActuatorExactPermitTest.ActuatorStub.class})
class ActuatorExactPermitTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Bootstrap {

        @Bean
        TenantFixtures.FakeLoginBootstrap fakeLoginBootstrap() {
            return new TenantFixtures.FakeLoginBootstrap(TenantFromIdentityOnlyTest.ISSUER);
        }
    }

    /** Every path an anonymous caller might try; the permitted three answer status only, the rest would leak. */
    @RestController
    static class ActuatorStub {

        @GetMapping({"/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness"})
        Map<String, String> health() {
            return Map.of("status", "UP");
        }

        @GetMapping({"/actuator/env", "/actuator/beans", "/actuator/info", "/actuator/heapdump",
            "/actuator/health/targetCredentialKeys", "/actuator/health/readiness/extra", "/actuator/health/db",
            "/actuator/health/readiness/"})
        Map<String, String> leak() {
            return Map.of("status", "UP", "details", "would leak");
        }
    }

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness"})
    void theThreeExactPathsAnswerAnonymouslyWithStatusOnly(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/info", "/actuator/heapdump",
        "/actuator/health/targetCredentialKeys", "/actuator/health/db", "/actuator/health/readiness/extra"})
    void everyOtherActuatorPathStaysDeniedAnonymously(String path) throws Exception {
        int code = mvc.perform(get(path)).andReturn().getResponse().getStatus();
        assertThat(code).as(path).isIn(401, 403);
    }

    @Test
    void noWildcardUnderHealthIsPermitted() throws Exception {
        int code = mvc.perform(get("/actuator/health/anything-else")).andReturn().getResponse().getStatus();
        assertThat(code).isIn(401, 403);
    }
}
