package io.integrationplatform.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.integrationplatform.app.message.MessageTrackingController;
import io.integrationplatform.app.message.MessageTrackingService;
import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import java.time.ZoneId;
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

/**
 * T208 / O1 (row 372): the app module's error seam over the REAL tenancy chain (MockMvc + {@code jwt()}), on the real
 * {@link MessageTrackingController}. Red first (advice absent): an authenticated bad request was an exception with no
 * handler, answered by the container's error dispatch; a {@code ConfigApiException} thrown by the shared
 * {@code ConfigActors} (403 NO_BOUND_USER) was not handled either, because the config advice is scoped to the config
 * package. Now the 4xx is the static JSON body, the config refusal keeps its status and body, a fault is a fixed 500,
 * and a request with no token is still the chain's 401.
 */
@WebMvcTest(properties = {
    "spring.config.import=optional:classpath:config/tenancy-security.yml",
    "OIDC_ISSUER_URI=" + AppApiExceptionHandlerTest.ISSUER,
    "OIDC_AUDIENCE=platform-api"
})
@Import({AppApiExceptionHandlerTest.TenancyChain.class, AppApiExceptionHandlerTest.Wiring.class,
    MessageTrackingController.class, AppApiExceptionHandler.class})
class AppApiExceptionHandlerTest {

    static final String ISSUER = "http://127.0.0.1:9/issuer";
    private static final String MESSAGES = "/api/v1/admin/messages";
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

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
    private MessageTrackingService service;

    @MockitoBean
    private ConfigActors actors;

    private static RequestPostProcessor signedIn() {
        return jwt().jwt(b -> b.claim("iss", ISSUER).subject("alice").audience(List.of("platform-api")));
    }

    @Test
    void noTokenIsStill401FromTheChainNotFromTheAdvice() throws Exception {
        mvc.perform(get(MESSAGES + "/failures")).andExpect(status().isUnauthorized());
        mvc.perform(get(MESSAGES + "/not-a-uuid/timeline")).andExpect(status().isUnauthorized());
    }

    @Test
    void aValueOfTheWrongTypeIs400WithTheStaticBody() throws Exception {
        mvc.perform(get(MESSAGES + "/not-a-uuid/timeline").with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(content().json("{\"error\":\"the request was not accepted\"}", true));
    }

    @Test
    void aConfigRefusalFromTheSharedActorsKeepsItsStatusAndBody() throws Exception {
        when(actors.requireActor()).thenThrow(ConfigApiException.refusal(403, "NO_BOUND_USER"));

        mvc.perform(get(MESSAGES + "/failures").with(signedIn()))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"refusal\":\"NO_BOUND_USER\"}", true));
    }

    @Test
    void aServerFaultIsAFixed500WithoutTheExceptionText() throws Exception {
        when(service.timeline(any())).thenThrow(new IllegalStateException("secret-row-value-42"));

        String body = mvc.perform(get(MESSAGES + "/" + ID + "/timeline").with(signedIn()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"error\":\"the request failed\"}", true))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret-row-value-42");
    }
}
