package io.integrationplatform.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * L15 / S5-A1: the admin API refuses a request body over 2 MB with a 413 and the static error body, before the body is
 * read into memory (Content-Length known) or as it is counted (chunked), and nothing is written.
 */
class AdminRequestSizeFilterTest {

    private static final int LIMIT = 2 * 1024 * 1024;
    private static final String SECRET = "SECRET-VALUE-4711";
    private static final String PATH = "/api/v1/admin/routes/11111111-1111-1111-1111-111111111111/mapping";

    private final AdminRequestSizeFilter filter = new AdminRequestSizeFilter(LIMIT);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AdminRequestSizeFilter.class);

    @BeforeEach
    void captureLog() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(logged);
    }

    /** The controller under test: reads the whole body (as the JSON converter does) and "writes" when it succeeds. */
    private static class Controller implements FilterChain {
        final AtomicBoolean invoked = new AtomicBoolean();
        final AtomicBoolean wrote = new AtomicBoolean();
        final AtomicInteger bytesRead = new AtomicInteger();
        final boolean mapFailureTo400;

        Controller(boolean mapFailureTo400) {
            this.mapFailureTo400 = mapFailureTo400;
        }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                throws IOException {
            invoked.set(true);
            try {
                bytesRead.set(request.getInputStream().readAllBytes().length);
                wrote.set(true);
                ((MockHttpServletResponse) response).setStatus(200);
            } catch (IOException unreadable) {
                if (!mapFailureTo400) {
                    throw unreadable;
                }
                // what ConfigApiExceptionHandler does with an unreadable body: its own 400 with its own body
                ((MockHttpServletResponse) response).setStatus(400);
                response.getWriter().write("{\"error\":\"the request body could not be read\"}");
            }
        }
    }

    /** The mock always reports the length of its content; the container reports what the client declared, or -1. */
    private static final class Declared extends MockHttpServletRequest {
        private final long declared;

        Declared(long declared) {
            super("PUT", PATH);
            this.declared = declared;
        }

        @Override
        public long getContentLengthLong() {
            return declared;
        }

        @Override
        public int getContentLength() {
            return (int) declared;
        }
    }

    private static MockHttpServletRequest put(byte[] body, boolean declareLength) {
        return put(body, declareLength ? body.length : -1);
    }

    private static MockHttpServletRequest put(byte[] body, long declared) {
        MockHttpServletRequest request = new Declared(declared);
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    /** Valid-looking JSON of exactly {@code size} bytes that starts with the secret value. */
    private static byte[] body(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 'A');
        byte[] head = ("{\"note\":\"" + SECRET).getBytes(StandardCharsets.UTF_8);
        System.arraycopy(head, 0, bytes, 0, head.length);
        return bytes;
    }

    private void assertRefusedCleanly(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).isEqualTo(AdminRequestSizeFilter.BODY);
        assertThat(response.getContentAsString()).doesNotContain(SECRET);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(logged.list).isNotEmpty();
        assertThat(logged.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(SECRET));
    }

    @Test
    void a_body_exactly_at_the_limit_with_content_length_passes() throws Exception {
        Controller controller = new Controller(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(put(body(LIMIT), true), response, controller);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(controller.bytesRead).hasValue(LIMIT);
        assertThat(logged.list).isEmpty();
    }

    @Test
    void a_body_one_byte_over_with_content_length_is_413_and_never_read() throws Exception {
        Controller controller = new Controller(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(put(body(LIMIT + 1), true), response, controller);
        assertRefusedCleanly(response);
        assertThat(controller.invoked).as("the controller never ran: no read, no write").isFalse();
        assertThat(controller.wrote).isFalse();
    }

    @Test
    void a_chunked_body_exactly_at_the_limit_passes() throws Exception {
        Controller controller = new Controller(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(put(body(LIMIT), false), response, controller); // no Content-Length header: chunked
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(controller.bytesRead).hasValue(LIMIT);
    }

    @Test
    void a_chunked_body_over_the_limit_is_413_even_when_the_controller_maps_the_read_failure_to_400() throws Exception {
        Controller controller = new Controller(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(put(body(LIMIT + 1), false), response, controller);
        assertRefusedCleanly(response);
        assertThat(controller.wrote).as("nothing was written").isFalse();
    }

    @Test
    void a_chunked_body_over_the_limit_is_413_when_the_failure_propagates() throws Exception {
        Controller controller = new Controller(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(put(body(LIMIT + 1), false), response, controller);
        assertRefusedCleanly(response);
        assertThat(controller.wrote).isFalse();
    }

    @Test
    void a_declared_length_that_lies_low_is_still_bounded_by_the_count() throws Exception {
        MockHttpServletRequest request = put(body(LIMIT + 1), 10L);
        Controller controller = new Controller(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, controller);
        assertRefusedCleanly(response);
        assertThat(controller.wrote).isFalse();
    }

    @Test
    void the_reader_is_counted_too() throws Exception {
        FilterChain readsText = (request, response) -> {
            request.getReader().transferTo(java.io.Writer.nullWriter());
            ((MockHttpServletResponse) response).setStatus(200);
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(put(body(LIMIT + 1), false), response, readsText);
        assertRefusedCleanly(response);
    }

    @Test
    void a_big_body_read_in_small_pieces_is_cut_off_at_the_limit() throws Exception {
        AtomicInteger total = new AtomicInteger();
        FilterChain slowReader = (request, response) -> {
            byte[] chunk = new byte[8192];
            int n;
            try {
                while ((n = request.getInputStream().read(chunk)) > 0) {
                    total.addAndGet(n);
                }
            } catch (IOException ignored) {
                // the controller swallows it
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(put(body(LIMIT * 3), false), response, slowReader);
        assertRefusedCleanly(response);
        assertThat(total.get()).isLessThanOrEqualTo(LIMIT);
    }

    @Test
    void a_get_and_a_body_less_delete_are_untouched() throws Exception {
        for (String method : new String[] {"GET", "DELETE"}) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, PATH);
            Controller controller = new Controller(true);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, controller);
            assertThat(response.getStatus()).as(method).isEqualTo(200);
            assertThat(controller.invoked).isTrue();
        }
    }

    @Test
    void the_registration_covers_the_admin_api_after_the_security_chain_and_not_intake() {
        FilterRegistrationBean<AdminRequestSizeFilter> registration =
                new AdminRequestSizeConfiguration().adminRequestSizeFilter(LIMIT);
        assertThat(registration.getUrlPatterns()).containsExactly("/api/v1/admin/*");
        assertThat(registration.getOrder())
                .as("after the security chain (order -100): an unauthenticated call is refused first")
                .isGreaterThan(-100);
        assertThat(registration.getFilter()).isInstanceOf(AdminRequestSizeFilter.class);
    }

    @Test
    void a_limit_that_is_not_positive_is_refused_at_start() {
        assertThatThrownBy(() -> new AdminRequestSizeFilter(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminRequestSizeFilter(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
