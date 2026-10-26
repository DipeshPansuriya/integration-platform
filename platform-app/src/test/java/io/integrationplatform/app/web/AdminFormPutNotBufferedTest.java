package io.integrationplatform.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.integrationplatform.tenancy.port.LoginBinding;
import io.integrationplatform.tenancy.port.LoginBootstrap;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.Ordered;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Karan M1 (row 372): Boot's FormContentFilter (order about -9900) reads a form-encoded PUT/PATCH/DELETE body in full,
 * before authentication and before {@link AdminRequestSizeFilter}. The admin API is JSON-only, so
 * {@code spring.mvc.formcontent.filter.enabled=false} (platform-app application.yml, loaded by this slice). The probe
 * is a counting wrapper installed in front of every filter: it measures the bytes any filter pulled out of the body.
 * Anonymous: 401 and zero bytes. Authenticated, 3 MB chunked body over the 2 MB limit: 415 (media type refused unread) or 413, and at most limit + 1 bytes.
 * The JSON-only 415 and the 405 are unchanged.
 */
@WebMvcTest(properties = {
    "spring.config.import=optional:classpath:config/tenancy-security.yml",
    "OIDC_ISSUER_URI=" + AdminFormPutNotBufferedTest.issuerUri,
    "OIDC_AUDIENCE=platform-api"
})
@Import({AdminFormPutNotBufferedTest.TenancyChain.class, AdminFormPutNotBufferedTest.Wiring.class,
    AdminFormPutNotBufferedTest.Probe.class, AdminRequestSizeConfiguration.class, AppApiExceptionHandler.class})
class AdminFormPutNotBufferedTest {

    static final String issuerUri = "http://issuer.invalid/realm";
    private static final String PATH = "/api/v1/admin/probe";
    private static final long LIMIT = 2097152;
    static final AtomicLong BYTES_READ = new AtomicLong();

    @SpringBootConfiguration
    static class Slice {
    }

    static class TenancyChain implements ImportSelector {
        @Override
        public String[] selectImports(AnnotationMetadata importingClassMetadata) {
            return new String[] {"io.integrationplatform.tenancy.internal.web.TenancySecurityConfiguration"};
        }
    }

    @RestController
    static class Probe {
        @PutMapping(value = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> put(@RequestBody Map<String, String> body) {
            return Map.of("ok", "yes");
        }

        @GetMapping(PATH + "-get")
        Map<String, String> get() {
            return Map.of("ok", "yes");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {
        @Bean
        LoginBootstrap loginBootstrap() {
            return (issuer, subject) -> Optional.of(new LoginBinding(UUID.randomUUID(), "ACTIVE", UUID.randomUUID(),
                    "tenant-a", "ACTIVE", ZoneId.of("UTC")));
        }

        /** In front of every other filter: counts what anything downstream reads from the body. */
        @Bean
        FilterRegistrationBean<OncePerRequestFilter> bodyReadCounter() {
            FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(
                    new OncePerRequestFilter() {
                        @Override
                        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                FilterChain chain) throws ServletException, IOException {
                            chain.doFilter(new HttpServletRequestWrapper(request) {
                                @Override
                                public ServletInputStream getInputStream() throws IOException {
                                    return counting(super.getInputStream());
                                }
                            }, response);
                        }
                    });
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }

        static ServletInputStream counting(ServletInputStream in) {
            return new ServletInputStream() {
                @Override public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0) {
                        BYTES_READ.incrementAndGet();
                    }
                    return b;
                }
                @Override public int read(byte[] buf, int off, int len) throws IOException {
                    int n = in.read(buf, off, len);
                    if (n > 0) {
                        BYTES_READ.addAndGet(n);
                    }
                    return n;
                }
                @Override public boolean isFinished() { return in.isFinished(); }
                @Override public boolean isReady() { return in.isReady(); }
                @Override public void setReadListener(ReadListener listener) { in.setReadListener(listener); }
            };
        }
    }

    /** A request with no declared length, as a chunked one. */
    private static final class Chunked extends MockHttpServletRequest {
        Chunked(jakarta.servlet.ServletContext context, String method, String path) {
            super(context, method, path);
        }

        @Override public long getContentLengthLong() { return -1; }
        @Override public int getContentLength() { return -1; }
    }

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void reset() {
        BYTES_READ.set(0);
    }

    private static RequestPostProcessor signedIn() {
        return jwt().jwt(b -> b.claim("iss", issuerUri).subject("alice").audience(List.of("platform-api")));
    }

    private static byte[] form(int bytes) {
        char[] filler = new char[bytes];
        Arrays.fill(filler, 'b');
        return ("a=" + new String(filler)).getBytes(StandardCharsets.UTF_8);
    }

    private MockHttpServletResponse chunkedFormPut(boolean authenticated, int bytes) throws Exception {
        return mvc.perform(servletContext -> {
            Chunked request = new Chunked(servletContext, "PUT", PATH);
            request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
            request.setContent(form(bytes));
            return authenticated ? signedIn().postProcessRequest(request) : request;
        }).andReturn().getResponse();
    }

    @Test
    void anAnonymousChunkedFormPutIsRefusedBeforeTheBodyIsTouched() throws Exception {
        MockHttpServletResponse response = chunkedFormPut(false, 3 * 1024 * 1024);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(BYTES_READ).as("no filter may buffer an anonymous body").hasValue(0);
    }

    @Test
    void anAuthenticatedChunkedFormPutOverTheLimitReadsAtMostTheLimit() throws Exception {
        MockHttpServletResponse response = chunkedFormPut(true, 3 * 1024 * 1024);

        // 415: the JSON-only handler refuses the media type without reading; 413 if anything reads past the limit
        assertThat(response.getStatus()).isIn(413, 415);
        assertThat(BYTES_READ.get()).as("no filter (FormContentFilter) buffers the body in full")
                .isLessThanOrEqualTo(LIMIT + 1);
    }

    @Test
    void theAdminApiStaysJsonOnlyAndKeepsItsOwn405() throws Exception {
        assertThat(mvc.perform(put(PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED).content("a=b")
                .with(signedIn())).andReturn().getResponse().getStatus()).isEqualTo(415);
        assertThat(mvc.perform(put(PATH + "-get").contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(signedIn())).andReturn().getResponse().getStatus()).isEqualTo(405);
    }
}
