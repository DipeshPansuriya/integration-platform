package io.integrationplatform.routing.adapter.api;

import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.config.destination.SocketDialer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The real {@link HttpSend}: one HTTP/1.1 request on the connection the {@link SocketDialer} opens to the address
 * the guard pinned - no second DNS lookup can happen, so the guard's refusals are un-bypassable (the wire shape
 * follows the reachability check's, {@code OutboundHttpCheck}, which is GET-only and stays untouched). One call
 * per invocation (the layer-1 route owns redelivery); TLS with the ORIGINAL host for SNI and the certificate
 * check; the endpoint's whole-call timeout bounds connect and read; the answer body is read bounded (cap 1 MB)
 * from Content-Length or connection close - a chunked answer is refused, not half-read.
 */
public class SocketHttpSend implements HttpSend {

    /** The response body cap: a bigger answer is a broken contract, not a delivery. */
    static final int RESPONSE_BODY_LIMIT = 1_048_576;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final SocketDialer dialer;

    public SocketHttpSend(SocketDialer dialer) {
        this.dialer = java.util.Objects.requireNonNull(dialer, "dialer");
    }

    @Override
    public Response send(CheckedDestination destination, String method, Map<String, String> headers, byte[] body,
            int timeoutMs) throws IOException {
        // no default ports guessed here: the guard resolved and checked the exact address, including its port
        Socket socket = dialer.dial(new InetSocketAddress(destination.pinnedAddress(), destination.port()), timeoutMs);
        try (Socket raw = socket) {
            raw.setSoTimeout(timeoutMs);
            Socket io = destination.tls() ? tls(raw, destination) : raw;
            write(io.getOutputStream(), destination, method, headers, body);
            // a SocketTimeoutException during the read propagates: the request went out and the caller
            // classifies the timeout (ofTimeout), never retries blind
            return read(io.getInputStream());
        }
    }

    private void write(OutputStream out, CheckedDestination destination, String method, Map<String, String> headers,
            byte[] body) throws IOException {
        out.write((method + " " + requestPath(destination.uri()) + " HTTP/1.1\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(("Host: " + hostHeader(destination) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write("Connection: close\r\n".getBytes(StandardCharsets.ISO_8859_1));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            out.write((header.getKey() + ": " + header.getValue() + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }
        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String requestPath(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private static String hostHeader(CheckedDestination destination) {
        String host = destination.host().contains(":") && !destination.host().startsWith("[")
                ? "[" + destination.host() + "]" : destination.host();
        int defaultPort = destination.tls() ? 443 : 80;
        return destination.port() == defaultPort ? host : host + ":" + destination.port();
    }

    /** TLS over the pinned connection, with the ORIGINAL name for SNI and the certificate check. */
    private static Socket tls(Socket raw, CheckedDestination destination) throws IOException {
        SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(raw, destination.host(), destination.port(), false);
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!destination.host().contains(":") && !IPV4.matcher(destination.host()).matches()) { // SNI: names only
            parameters.setServerNames(List.of(new SNIHostName(destination.host())));
        }
        ssl.setSSLParameters(parameters);
        ssl.startHandshake();
        return ssl;
    }

    private static Response read(InputStream in) throws IOException {
        List<String> head = readHead(in);
        int status = parseStatus(head.get(0));
        Integer contentLength = null;
        String contentType = null;
        Duration retryAfter = null;
        for (String line : head.subList(1, head.size())) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("content-length")) {
                contentLength = Integer.parseInt(value);
            } else if (name.equals("content-type")) {
                contentType = value;
            } else if (name.equals("retry-after")) {
                retryAfter = retryAfter(value);
            } else if (name.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                throw new IOException("the target answered chunked - the endpoint contract sends Content-Length");
            }
        }
        return new Response(status, contentLength == null ? readToClose(in) : readBounded(in, contentLength),
                contentType, retryAfter);
    }

    /** RFC 9110 section 10.2.3: delta-seconds or an HTTP-date; anything else is ignored, never a failure. */
    static Duration retryAfter(String value) {
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value)));
        } catch (NumberFormatException notSeconds) {
            try {
                Duration until = Duration.between(Instant.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return until.isNegative() ? Duration.ZERO : until;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }

    private static byte[] readBounded(InputStream in, int contentLength) throws IOException {
        if (contentLength < 0 || contentLength > RESPONSE_BODY_LIMIT) {
            throw new IOException("the target's answer body of " + contentLength + " bytes exceeds the "
                    + RESPONSE_BODY_LIMIT + " byte limit");
        }
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int next = in.read(body, read, contentLength - read);
            if (next < 0) {
                throw new IOException("the target's answer ended after " + read + " of " + contentLength + " bytes");
            }
            read += next;
        }
        return body;
    }

    private static byte[] readToClose(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > RESPONSE_BODY_LIMIT) {
                // close-delimited answers are read bounded like Content-Length ones, never unbounded
                throw new IOException("the target's answer exceeds the " + RESPONSE_BODY_LIMIT + " byte limit"
                        + " before the connection closed");
            }
        }
        return buffer.toByteArray();
    }

    private static List<String> readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last4 = 0;
        while (true) {
            int next = in.read();
            if (next < 0) {
                break;
            }
            head.write(next);
            last4 = (last4 << 8) | next;
            if (last4 == 0x0d0a0d0a || (last4 & 0xffff) == 0x0a0a) {
                break;
            }
            if (head.size() > MAX_HEADER_BYTES) {
                throw new IOException("answer headers too large");
            }
        }
        List<String> lines = head.toString(StandardCharsets.ISO_8859_1).lines().filter(l -> !l.isEmpty()).toList();
        if (lines.isEmpty()) {
            throw new IOException("empty answer");
        }
        return lines;
    }

    private static int parseStatus(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].toUpperCase(Locale.ROOT).startsWith("HTTP/")) {
            throw new IOException("not an HTTP status line");
        }
        try {
            int status = Integer.parseInt(parts[1]);
            if (status < 100 || status > 599) {
                throw new IOException("status out of range");
            }
            return status;
        } catch (NumberFormatException e) {
            throw new IOException("not an HTTP status", e);
        }
    }
}