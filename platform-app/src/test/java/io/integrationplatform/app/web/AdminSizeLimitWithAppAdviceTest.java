package io.integrationplatform.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * L20 cross-lane check (L15 filter x L18 {@code @Order(0)} app advice): the advice answers an unreadable body with its
 * own 400 JSON; the size filter, which wraps the whole dispatch, must still replace that with the 413 and the static
 * body, never leave a 400 or a 500. Chunked (no declared length) is the path where the advice sees the failure first.
 */
class AdminSizeLimitWithAppAdviceTest {

    private static final int LIMIT = 1024;
    private static final String PATH = "/api/v1/admin/probe";

    private final AtomicInteger controllerRuns = new AtomicInteger();
    private MockMvc mvc;

    @RestController
    class Probe {
        @PostMapping(PATH)
        Map<String, String> take(@RequestBody Map<String, String> body) {
            controllerRuns.incrementAndGet();
            return Map.of("ok", "yes");
        }
    }

    /** Reports no declared length, as a chunked request does. */
    private static final class Chunked extends MockHttpServletRequest {
        Chunked(jakarta.servlet.ServletContext context) {
            super(context, "POST", PATH);
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public int getContentLength() {
            return -1;
        }
    }

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new Probe()).setControllerAdvice(new AppApiExceptionHandler())
                .addFilters(new AdminRequestSizeFilter(LIMIT)).build();
    }

    private static byte[] json(int bytes) {
        char[] filler = new char[bytes];
        Arrays.fill(filler, 'a');
        return ("{\"k\":\"" + new String(filler) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private MockHttpServletResponse chunked(byte[] body) throws Exception {
        return mvc.perform(servletContext -> {
            Chunked request = new Chunked(servletContext);
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent(body);
            return request;
        }).andReturn().getResponse();
    }

    @Test
    void chunkedOverLimitIsThe413NotTheAdvices400() throws Exception {
        MockHttpServletResponse response = chunked(json(LIMIT * 4));
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).isEqualTo(AdminRequestSizeFilter.BODY);
        assertThat(controllerRuns).hasValue(0);
    }

    @Test
    void declaredOverLimitIsThe413WithoutReachingTheAdvice() throws Exception {
        MockHttpServletResponse response = mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(json(LIMIT * 4))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).isEqualTo(AdminRequestSizeFilter.BODY);
        assertThat(controllerRuns).hasValue(0);
    }

    @Test
    void underTheLimitStillReachesTheController() throws Exception {
        MockHttpServletResponse response = chunked(json(16));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(controllerRuns).hasValue(1);
    }

    @Test
    void garbageUnderTheLimitStaysTheAdvices400() throws Exception {
        MockHttpServletResponse response = mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PATH)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("the request was not accepted");
    }
}
