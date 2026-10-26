package io.integrationplatform.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * T209 item 2: the admin API's body limit (L15 / S5-A1) for a chunked body, over a real embedded Tomcat on a real
 * socket. The unit tests drive the filter with a mock request that merely reports "no length"; here the client really
 * sends {@code Transfer-Encoding: chunked} (no Content-Length) and the container's own input stream is counted. The
 * registration comes from {@link AdminRequestSizeConfiguration}, so its URL pattern and order are exercised too.
 */
@SpringBootTest(
        classes = AdminChunkedBodyRealServerTest.Server.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "platform.admin-api.max-request-body-bytes=" + AdminChunkedBodyRealServerTest.LIMIT)
class AdminChunkedBodyRealServerTest {

    static final int LIMIT = 1024;
    private static final String PATH = "/api/v1/admin/probe";

    static final AtomicInteger CONTROLLER_RUNS = new AtomicInteger();
    /** The Transfer-Encoding / Content-Length the container presented, recorded before the size filter runs. */
    static final CopyOnWriteArrayList<String> FRAMING = new CopyOnWriteArrayList<>();

    @RestController
    static class Probe {
        @PostMapping(PATH)
        Map<String, String> take(@RequestBody Map<String, String> body) {
            CONTROLLER_RUNS.incrementAndGet();
            return Map.of("ok", "yes");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class})
    @Import({AdminRequestSizeConfiguration.class, AppApiExceptionHandler.class, Probe.class})
    static class Server {
        @Bean
        FilterRegistrationBean<OncePerRequestFilter> framingRecorder() {
            FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(
                    new OncePerRequestFilter() {
                        @Override
                        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                FilterChain chain) throws ServletException, IOException {
                            FRAMING.add(request.getHeader("Transfer-Encoding") + "|" + request.getContentLengthLong());
                            chain.doFilter(request, response);
                        }
                    });
            registration.setOrder(AdminRequestSizeConfiguration.AFTER_SECURITY_CHAIN - 1);
            return registration;
        }
    }

    @Value("${local.server.port}")
    private int port;

    @BeforeEach
    void reset() {
        CONTROLLER_RUNS.set(0);
        FRAMING.clear();
    }

    private static byte[] json(int fillerBytes) {
        char[] filler = new char[fillerBytes];
        Arrays.fill(filler, 'a');
        return ("{\"k\":\"" + new String(filler) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /** An input-stream publisher has no known length, so the JDK client sends it chunked. */
    private HttpResponse<String> postChunked(byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aChunkedBodyOverTheLimitIsThe413WithTheStaticBodyAndNeverReachesTheController() throws Exception {
        HttpResponse<String> response = postChunked(json(LIMIT * 4));

        assertThat(FRAMING).as("the request really was chunked, with no declared length").containsExactly("chunked|-1");
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).isEqualTo(AdminRequestSizeFilter.BODY);
        assertThat(CONTROLLER_RUNS).hasValue(0);
    }

    @Test
    void aChunkedBodyUnderTheLimitStillReachesTheController() throws Exception {
        HttpResponse<String> response = postChunked(json(16));

        assertThat(FRAMING).containsExactly("chunked|-1");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(CONTROLLER_RUNS).hasValue(1);
    }
}
