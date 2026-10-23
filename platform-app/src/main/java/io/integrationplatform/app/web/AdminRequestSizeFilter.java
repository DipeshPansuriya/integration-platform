package io.integrationplatform.app.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * L15, security gate S5-A1: an admin API request whose body is over the limit (default 2 MB) is refused with a 413 and a
 * static body, and nothing is written. Runs after the security chain (so only an authenticated caller reaches it) and
 * before any controller reads the body:
 *
 * <ul>
 *   <li>a declared {@code Content-Length} over the limit is refused without reading a byte;</li>
 *   <li>a body without a length (chunked) or with a length that lies is counted as it is read; the read that crosses the
 *       limit fails, so the JSON converter never builds more than the limit, and whatever the controller or the
 *       exception handler then answered is replaced by the 413.</li>
 * </ul>
 *
 * The answer never echoes the body, and the log line carries the method, path and limit only.
 */
final class AdminRequestSizeFilter extends OncePerRequestFilter {

    /** The static error body, same shape as the config API's errors. */
    static final String BODY = "{\"error\":\"the request body is too large\"}";

    private static final Logger LOG = LoggerFactory.getLogger(AdminRequestSizeFilter.class);
    private static final Set<String> WITH_BODY = Set.of("POST", "PUT", "PATCH");

    private final long limit;

    AdminRequestSizeFilter(long limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("the admin request body limit must be positive, got " + limit);
        }
        this.limit = limit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !WITH_BODY.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > limit) {
            refuse(request, response);
            return;
        }
        CountingRequest counted = new CountingRequest(request, limit);
        try {
            chain.doFilter(counted, response);
        } catch (ServletException | IOException | RuntimeException failure) {
            if (!counted.exceeded()) {
                throw failure;
            }
        }
        if (counted.exceeded()) {
            if (response.isCommitted()) {
                LOG.warn("admin request body over the {}-byte limit, answer already committed: {} {}", limit,
                        request.getMethod(), request.getRequestURI());
                return;
            }
            refuse(request, response);
        }
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        LOG.warn("admin request body over the {}-byte limit refused: {} {}", limit, request.getMethod(),
                request.getRequestURI());
        response.reset();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setHeader("Connection", "close");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }

    /** Counts the bytes taken from the body; the read that would pass the limit fails and every later read fails too. */
    private static final class CountingRequest extends HttpServletRequestWrapper {
        private final long limit;
        private long total;
        private boolean exceeded;
        private ServletInputStream stream;

        CountingRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        boolean exceeded() {
            return exceeded;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new Counting(super.getInputStream());
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String name = getCharacterEncoding();
            Charset charset = name == null ? StandardCharsets.ISO_8859_1 : Charset.forName(name);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        private final class Counting extends ServletInputStream {
            private final ServletInputStream in;

            Counting(ServletInputStream in) {
                this.in = in;
            }

            @Override
            public int read() throws IOException {
                refuseAfterExceeded();
                int b = in.read();
                if (b >= 0) {
                    counted(1);
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                refuseAfterExceeded();
                // never take more than one byte past the limit into memory
                int n = in.read(buffer, offset, (int) Math.min(length, limit - total + 1));
                if (n > 0) {
                    counted(n);
                }
                return n;
            }

            private void refuseAfterExceeded() throws IOException {
                if (exceeded) {
                    throw new IOException("request body over the limit");
                }
            }

            private void counted(int n) throws IOException {
                total += n;
                if (total > limit) {
                    exceeded = true;
                    throw new IOException("request body over the limit");
                }
            }

            @Override
            public boolean isFinished() {
                return in.isFinished();
            }

            @Override
            public boolean isReady() {
                return in.isReady();
            }

            @Override
            public void setReadListener(ReadListener listener) {
                in.setReadListener(listener);
            }

            @Override
            public void close() throws IOException {
                in.close();
            }
        }
    }
}
