package io.integrationplatform.tenancy.internal.web;

import static io.integrationplatform.tenancy.internal.web.TenantFixtures.ALICE;
import static io.integrationplatform.tenancy.internal.web.TenantFixtures.AUDIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T208 / O1 (row 372): the tenancy module's error seam over the real tenancy chain. {@code MeController} is the
 * module's one real controller; the probe below throws a server fault with a secret in its message. A request with no
 * token is still answered by the chain (401); a signed-in bad request gets the static 4xx body, a fault the fixed
 * 500 body with no exception text.
 */
@WebMvcTest(
        controllers = TenancyApiExceptionHandlerTest.BoomController.class,
        includeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX, pattern = "io\\.integrationplatform\\.tenancy\\.internal\\.web\\.MeController"),
        properties = {
            "spring.config.import=optional:classpath:config/tenancy-security.yml",
            "OIDC_ISSUER_URI=" + TenancyApiExceptionHandlerTest.ISSUER,
            "OIDC_AUDIENCE=" + AUDIENCE
        })
@Import({TenancySecurityConfiguration.class, TenancyApiExceptionHandler.class, TenancyApiExceptionHandlerTest.BoomController.class,
    TenancyApiExceptionHandlerTest.FakeBootstrapConfig.class})
class TenancyApiExceptionHandlerTest {

    static final String ISSUER = "http://127.0.0.1:9/realms/platform";

    @RestController
    @RequestMapping("/api/test/boom")
    static class BoomController {
        @GetMapping
        String boom() {
            throw new IllegalStateException("secret-value-42 jdbc:postgresql://db/internal");
        }

        @GetMapping("/typed/{n}")
        String typed(@org.springframework.web.bind.annotation.PathVariable("n") int n) {
            return "n" + n;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeBootstrapConfig {
        @Bean
        TenantFixtures.FakeLoginBootstrap fakeLoginBootstrap() {
            return new TenantFixtures.FakeLoginBootstrap(ISSUER);
        }
    }

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor signedIn() {
        return jwt().jwt(b -> b.claim("iss", ISSUER).subject(ALICE).audience(List.of(AUDIENCE)));
    }

    @Test
    void noTokenIsStill401FromTheChain() throws Exception {
        mvc.perform(get("/api/test/boom")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/test/boom/typed/abc")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void aValueOfTheWrongTypeIs400WithTheStaticBody() throws Exception {
        mvc.perform(get("/api/test/boom/typed/abc").with(signedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(content().json("{\"error\":\"the request was not accepted\"}", true));
    }

    @Test
    void aServerFaultIsAFixed500WithoutTheExceptionText() throws Exception {
        String body = mvc.perform(get("/api/test/boom").with(signedIn()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"error\":\"the request failed\"}", true))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret-value-42").doesNotContain("jdbc");
    }

    @Test
    void theRealMeEndpointStillAnswersItsOwnSuccess() throws Exception {
        mvc.perform(get("/api/v1/me").with(signedIn()))
                .andExpect(status().isOk());
    }

    /** Limit, by design of a scoped advice: no handler matched, so the framework answers (405 + Allow). */
    @Test
    void aWrongMethodStaysTheFrameworksOwn405() throws Exception {
        mvc.perform(post("/api/v1/me").with(signedIn()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"));
    }
}
