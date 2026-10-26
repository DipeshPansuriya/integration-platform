package io.integrationplatform.delivery.web;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.integrationplatform.delivery.port.DeliveryDailyAggregates;
import io.integrationplatform.delivery.report.DeliveryReportController;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * T208 / O1 (row 372): the delivery module's error seam, over the REAL tenancy security chain (MockMvc +
 * spring-security-test {@code jwt()}). Reproduction of O1 (written red first, with the advice absent): an
 * authenticated bad request to {@code GET /api/v1/reports/delivery} (DeliveryReportController.java:75-86) was an
 * exception with no handler - answered by the container's error dispatch (the framework's default body; a 403 with an
 * empty body whenever the chain does not open the ERROR dispatch, TenancySecurityConfiguration.java:90-92). Now the
 * 4xx is the module's static JSON body, a server fault is a fixed 500 body without the exception text, and the
 * chain's own answers are untouched: no token is still 401.
 */
@WebMvcTest(properties = {
    "spring.config.import=optional:classpath:config/tenancy-security.yml",
    "OIDC_ISSUER_URI=" + DeliveryApiExceptionHandlerTest.issuerUri,
    "OIDC_AUDIENCE=platform-api"
})
@Import({DeliveryApiExceptionHandlerTest.TenancyChain.class, DeliveryApiExceptionHandlerTest.Wiring.class,
    DeliveryReportController.class, DeliveryApiExceptionHandler.class})
class DeliveryApiExceptionHandlerTest {

    /** Never contacted: {@code jwt()} puts an already-validated token in the security context. */
    static final String issuerUri = "http://issuer.invalid/realm";
    private static final String REPORT = "/api/v1/reports/delivery";
    private static final String CLIENT_BODY = "{\"error\":\"the request was not accepted\"}";

    @SpringBootConfiguration
    static class Slice {
    }

    /** The real chain is package-private in platform-tenancy; it is imported by name, exactly as the app scans it. */
    static class TenancyChain implements ImportSelector {
        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            return new String[] {"io.integrationplatform.tenancy.internal.web.TenancySecurityConfiguration"};
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {
        @Bean
        LoginBootstrap loginBootstrap() {
            return (issuer, subject) -> Optional.of(new LoginBinding(UUID.randomUUID(), "ACTIVE", UUID.randomUUID(),
                    "tenant-a", "ACTIVE", ZoneId.of("UTC")));
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DeliveryDailyAggregates aggregates;

    @MockitoBean
    private PlatformTransactionManager transactions;

    private static RequestPostProcessor signedIn() {
        return jwt().jwt(b -> b.claim("iss", issuerUri).subject("alice").audience(List.of("platform-api")));
    }

    @Test
    void noTokenIsStill401FromTheChainNotFromTheAdvice() throws Exception {
        mvc.perform(get(REPORT).param("day", "2026-10-23")).andExpect(status().isUnauthorized());
        mvc.perform(get(REPORT).param("day", "not-a-date")).andExpect(status().isUnauthorized());
    }

    @Test
    void aValueOfTheWrongTypeIs400WithTheStaticBody() throws Exception {
        mvc.perform(get(REPORT).param("day", "not-a-date").with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(content().json(CLIENT_BODY, true));
    }

    @Test
    void aMissingParameterIs400WithTheStaticBody() throws Exception {
        mvc.perform(get(REPORT).with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(CLIENT_BODY, true));
    }

    @Test
    void aDayOutsideTheWindowKeepsItsStatusButNotItsReasonText() throws Exception {
        String future = LocalDate.now(ZoneOffset.UTC).plusDays(5).toString();

        mvc.perform(get(REPORT).param("day", future).with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(CLIENT_BODY, true));
    }

    /** Limit, by design of a scoped advice: no handler matched, so no controller type to scope by - the framework answers. */
    @Test
    void aWrongMethodIsRefusedBeforeAnyHandlerSoTheFrameworkKeepsItsOwn405() throws Exception {
        mvc.perform(post(REPORT).param("day", "2026-10-23").with(signedIn()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"));
    }

    @Test
    void aServerFaultIsAFixed500WithoutTheExceptionText() throws Exception {
        when(aggregates.aggregated(any())).thenThrow(new IllegalStateException("secret-sql-value-42"));
        String today = LocalDate.now(ZoneOffset.UTC).toString();

        String body = mvc.perform(get(REPORT).param("day", today).with(signedIn()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"error\":\"the request failed\"}", true))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("secret-sql-value-42");
    }

    @Test
    void aThrownAccessDeniedIsSpringSecuritys403NotTheFixed500() throws Exception {
        when(aggregates.aggregated(any())).thenThrow(new AccessDeniedException("denied-42"));
        String body = mvc.perform(get(REPORT).param("day", LocalDate.now(ZoneOffset.UTC).toString()).with(signedIn()))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("the request failed");
    }

    @Test
    void aThrownAuthenticationExceptionIsSpringSecuritys401NotTheFixed500() throws Exception {
        when(aggregates.aggregated(any())).thenThrow(new BadCredentialsException("bad-42"));
        String body = mvc.perform(get(REPORT).param("day", LocalDate.now(ZoneOffset.UTC).toString()).with(signedIn()))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("the request failed");
    }
}
