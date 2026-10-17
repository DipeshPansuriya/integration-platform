package io.integrationplatform.config.publishrefusal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.integrationplatform.config.adapter.in.ConfigApiExceptionHandler;
import io.integrationplatform.routing.templates.RouteConfigReader;
import io.integrationplatform.routing.templates.RouteConfigRow;
import io.integrationplatform.routing.templates.RouteInstanceFactory;
import io.integrationplatform.routing.templates.RouteTemplate;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Row 278 T106, Kavita F15: a publish refused by the routing module (an unapproved endpoint URI scheme, an unknown
 * target type) answers through the REAL configuration API error seam as a 400 that names the refused scheme or type
 * - never the catch-all 500, and never the endpoint URI (a URI can carry userinfo). The stub controller lives in an
 * {@code io.integrationplatform.config.*} package so the advice's {@code basePackages} applies, and it drives the
 * real {@link RouteInstanceFactory#publishInterface} (MockMvc standaloneSetup + setControllerAdvice).
 */
@Tag("e9-t116")
class PublishRefusalAnswerTest {

    private static final String SECRET_URI = "exec://admin:s3cret@host/bin/echo";
    private static final String LONG_TYPE = "FTP" + "X".repeat(40);
    private static final String CRLF_TYPE = "FTP\r\nX-Injected: yes";

    private DefaultCamelContext camel;
    private MockMvc mvc;

    @BeforeEach
    void theConfigApi() throws Exception {
        camel = new DefaultCamelContext();
        camel.start();
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        ConfigApiExceptionHandler handler = new ConfigApiExceptionHandler(
                none.getBeanProvider(TenantDenialRecorder.class), none.getBeanProvider(TenantContext.class));
        mvc = MockMvcBuilders.standaloneSetup(new RefusingPublishController(camel))
                .setControllerAdvice(handler).build();
    }

    @AfterEach
    void stop() throws Exception {
        camel.stop();
    }

    @Test
    void anUnapprovedSchemeAnswers400NamingTheSchemeButNotTheUri() throws Exception {
        mvc.perform(post("/publish/scheme"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("'exec'")))
                .andExpect(content().string(not(containsString(SECRET_URI))))
                .andExpect(content().string(not(containsString("s3cret"))));
    }

    @Test
    void anUnknownTargetTypeAnswers400NamingTheType() throws Exception {
        mvc.perform(post("/publish/type"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("'FTP'")));
    }

    @Test
    void textBeforeTheColonThatIsNotASchemeIsNeverEchoed() throws Exception {
        mvc.perform(post("/publish/malformed-scheme"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("(not a URI scheme)")))
                .andExpect(content().string(not(containsString("token@host"))));
    }

    @Test
    void anOverLongTypeAnswersTheFixedText() throws Exception {
        mvc.perform(post("/publish/long-type"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("(not a target type)")))
                .andExpect(content().string(not(containsString(LONG_TYPE))));
    }

    @Test
    void aCrLfTypeAnswersTheFixedText() throws Exception {
        mvc.perform(post("/publish/crlf-type"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("(not a target type)")))
                .andExpect(content().string(not(containsString("X-Injected"))));
    }

    /** A publish endpoint of the configuration API whose routing check refuses the interface's row. */
    @RestController
    static class RefusingPublishController {

        private final DefaultCamelContext camel;

        RefusingPublishController(DefaultCamelContext camel) {
            this.camel = camel;
        }

        @PostMapping("/publish/scheme")
        String refusedScheme() {
            return publish(new RouteConfigRow(seed("route-exec"), seed("endpoint-exec"), "API", SECRET_URI, null, null));
        }

        @PostMapping("/publish/type")
        String refusedType() {
            return publish(new RouteConfigRow(seed("route-ftp"), seed("endpoint-ftp"), "FTP",
                    "https://target.example/v1", null, null));
        }

        @PostMapping("/publish/malformed-scheme")
        String refusedMalformedScheme() {
            return publish(new RouteConfigRow(seed("route-at"), seed("endpoint-at"), "API", "token@host:8443/x",
                    null, null));
        }

        @PostMapping("/publish/long-type")
        String refusedLongType() {
            return publish(new RouteConfigRow(seed("route-long"), seed("endpoint-long"), LONG_TYPE,
                    "https://target.example/v1", null, null));
        }

        @PostMapping("/publish/crlf-type")
        String refusedCrLfType() {
            return publish(new RouteConfigRow(seed("route-crlf"), seed("endpoint-crlf"), CRLF_TYPE,
                    "https://target.example/v1", null, null));
        }

        private String publish(RouteConfigRow row) {
            RouteConfigReader reader = (tenant, iface) -> List.of(row);
            RouteTemplate api = new RouteTemplate() {
                @Override
                public String targetType() {
                    return "API";
                }

                @Override
                public org.apache.camel.Processor build(RouteConfigRow built) {
                    return exchange -> { };
                }
            };
            new RouteInstanceFactory(reader, camel, List.of(api), Set.of("https"), Duration.ofMinutes(30),
                    () -> Instant.parse("2026-10-17T12:00:00Z")).publishInterface(seed("tenant"), seed("iface"));
            return "published";
        }
    }

    private static UUID seed(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
