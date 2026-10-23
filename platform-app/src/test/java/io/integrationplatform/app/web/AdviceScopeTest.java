package io.integrationplatform.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.integrationplatform.config.adapter.in.ConfigApiExceptionHandler;
import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T208 (row 372): the config advice answers for config controllers only. A controller in an app package that throws
 * does not get the config error body: with only the config advice registered nothing handles it at all, and with the
 * app advice registered beside it the answer is the app's own contract - never "the configuration write failed ...".
 */
class AdviceScopeTest {

    @RestController
    static class StubAppController {
        @GetMapping("/stub/fault")
        String fault() {
            throw new IllegalStateException("boom");
        }

        @GetMapping("/stub/refusal")
        String refusal() {
            throw ConfigApiException.conflict("a config conflict");
        }
    }

    private static ConfigApiExceptionHandler configAdvice() {
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        return new ConfigApiExceptionHandler(none.getBeanProvider(TenantDenialRecorder.class),
                none.getBeanProvider(TenantContext.class));
    }

    @Test
    void theConfigAdviceDoesNotAnswerForAnAppController() {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StubAppController())
                .setControllerAdvice(configAdvice()).build();

        assertThatThrownBy(() -> mvc.perform(get("/stub/fault")))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> mvc.perform(get("/stub/refusal")))
                .hasRootCauseInstanceOf(ConfigApiException.class);
    }

    @Test
    void anAppControllerGetsTheAppBodyNotTheConfigBodyWhenBothAdvicesAreRegistered() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StubAppController())
                .setControllerAdvice(configAdvice(), new AppApiExceptionHandler()).build();

        String body = mvc.perform(get("/stub/fault"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"error\":\"the request failed\"}", true))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("configuration write failed").doesNotContain("rolled back");
    }
}
