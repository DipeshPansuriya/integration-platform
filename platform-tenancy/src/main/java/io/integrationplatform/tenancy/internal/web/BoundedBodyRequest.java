package io.integrationplatform.tenancy.internal.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Reads the first {@link TenantFieldScanner#BODY_LIMIT} bytes of the body for inspection and replays them, followed by
 * the unread rest, to the controller - the body is inspected without being consumed.
 */
final class BoundedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] prefix;
    private final ServletInputStream body;

    BoundedBodyRequest(HttpServletRequest request) throws IOException {
        this(request, TenantFieldScanner.BODY_LIMIT);
    }

    /** Inspects the first {@code limit} bytes instead (the source customer cross-check reads a whole bulk body). */
    BoundedBodyRequest(HttpServletRequest request, int limit) throws IOException {
        super(request);
        ServletInputStream original = request.getInputStream();
        this.prefix = original.readNBytes(limit);
        this.body = new ReplayStream(new SequenceInputStream(new ByteArrayInputStream(prefix), original));
    }

    byte[] prefix() {
        return prefix;
    }

    @Override
    public ServletInputStream getInputStream() {
        return body;
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(body, charset));
    }

    private static final class ReplayStream extends ServletInputStream {

        private final InputStream in;
        private boolean finished;

        ReplayStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            finished = b < 0;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            finished = n < 0;
            return n;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            // ponytail: the API is synchronous (blocking MVC); asynchronous body reads are not used.
            throw new UnsupportedOperationException("asynchronous reads are not supported");
        }
    }
}
