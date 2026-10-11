package io.integrationplatform.config.destination;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The guarded reachability check of one HTTP(S) destination (T-104 test-connection; T-124 reuses it): every hop goes
 * through the {@link DestinationGuard}, the connection is made to the address the guard pinned (no second lookup), and
 * redirects are followed by hand, each hop re-checked. Nothing is sent to a refused destination.
 *
 * <p>R13 (documents/rule.md) on the outgoing call: a connect and a read timeout; transient-only retry (connect failure,
 * 502/503/504) with exponential backoff, jitter and a cap; one circuit breaker per destination ({@code scheme://host:port}),
 * opening after {@code breakerFailureThreshold} failed checks in a row; a fallback - every failure is a clean
 * {@link Result}, never an exception; metrics ({@value #METRIC} by outcome and reason, resilience4j breaker and retry
 * meters) and an observation ({@value #OBSERVATION}, the span when tracing is on).
 *
 * <p>The request is a bare {@code GET} with no body, no credentials and no tenant data: only the status line and the
 * headers of the answer are read, the body never is.
 */
public final class OutboundHttpCheck {

    public static final String METRIC = "integration.destination.checks";
    public static final String OBSERVATION = "integration.destination.check";

    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Set<Integer> TRANSIENT_STATUS = Set.of(502, 503, 504);
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    public enum Outcome { SUCCESS, REFUSED, FAILED }

    /**
     * @param httpStatus     the final answer's status, when one came
     * @param responseTimeMs the final hop's time from connect to the answer's headers, on SUCCESS only
     * @param reason         {@code null} on SUCCESS; a {@link Refusal} name on REFUSED; on FAILED one of UNRESOLVED,
     *                       UNREACHABLE, TIMEOUT, TLS_FAILED, BAD_RESPONSE, SERVER_ERROR, CIRCUIT_OPEN,
     *                       TOO_MANY_REDIRECTS, BAD_REDIRECT
     * @param detail         the refused range's name for REFUSED_ADDRESS, else {@code null}
     */
    public record Result(Outcome outcome, Integer httpStatus, Long responseTimeMs, String reason, String detail) {

        static Result success(int status, long millis) {
            return new Result(Outcome.SUCCESS, status, millis, null, null);
        }

        static Result refused(Refusal refusal, String detail) {
            return new Result(Outcome.REFUSED, null, null, refusal.name(), detail);
        }

        static Result failed(String reason, Integer status) {
            return new Result(Outcome.FAILED, status, null, reason, null);
        }
    }

    /** The R13 knobs of the check. */
    public record Settings(Duration connectTimeout, Duration readTimeout, int maxAttempts, int maxRedirects,
            Duration initialBackoff, double backoffMultiplier, double jitter, Duration maxBackoff,
            int breakerFailureThreshold, Duration breakerOpenDuration) {

        public Settings {
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(readTimeout, "readTimeout");
            Objects.requireNonNull(initialBackoff, "initialBackoff");
            Objects.requireNonNull(maxBackoff, "maxBackoff");
            Objects.requireNonNull(breakerOpenDuration, "breakerOpenDuration");
            if (maxAttempts < 1 || maxRedirects < 0 || breakerFailureThreshold < 1) {
                throw new IllegalArgumentException("attempts and the breaker threshold are at least 1, redirects 0");
            }
        }

        /** 5 s connect, 10 s read, 3 attempts from 200 ms doubling to 2 s with 50 % jitter, 5 redirects, breaker 5/30 s. */
        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(5), Duration.ofSeconds(10), 3, 5, Duration.ofMillis(200), 2.0, 0.5,
                    Duration.ofSeconds(2), 5, Duration.ofSeconds(30));
        }
    }

    /** One request/answer on one hop; never thrown, so retry and breaker decide on the value. */
    private record Exchange(Integer status, String location, long millis, String failure, boolean transientFailure) {

        static Exchange answered(int status, String location, long millis) {
            return new Exchange(status, location, millis, null, TRANSIENT_STATUS.contains(status));
        }

        static Exchange failed(String reason, boolean transientFailure) {
            return new Exchange(null, null, 0, reason, transientFailure);
        }

        boolean breakerFailure() {
            return failure != null || status >= 500;
        }
    }

    private final DestinationGuard guard;
    private final SocketDialer dialer;
    private final Settings settings;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;

    public OutboundHttpCheck(DestinationGuard guard, SocketDialer dialer, Settings settings, MeterRegistry meters,
            ObservationRegistry observations) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.dialer = Objects.requireNonNull(dialer, "dialer");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.breakerFailureThreshold())
                .minimumNumberOfCalls(settings.breakerFailureThreshold())
                .failureRateThreshold(100f) // opens after `threshold` failed checks in a row
                .waitDurationInOpenState(settings.breakerOpenDuration())
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
        this.retries = RetryRegistry.of(RetryConfig.<Exchange>custom()
                .maxAttempts(settings.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(settings.initialBackoff(),
                        settings.backoffMultiplier(), settings.jitter(), settings.maxBackoff()))
                .retryOnResult(Exchange::transientFailure)
                .build());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meters);
        TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(meters);
    }

    /**
     * Checks {@code address}: SUCCESS when any HTTP answer arrives (a 4xx is an answer - the destination is reachable),
     * REFUSED when the guard refuses a hop, FAILED otherwise.
     *
     * @param followRedirects follow 3xx answers (each hop re-checked); off, the 3xx answer is the result
     * @param readTimeout     the per-attempt read timeout, or {@code null} for the settings' default
     */
    public Result check(URI address, boolean followRedirects, Duration readTimeout) {
        Observation observation = Observation.createNotStarted(OBSERVATION, observations).start();
        Result result;
        try {
            result = follow(address, followRedirects, readTimeout == null ? settings.readTimeout() : readTimeout);
        } catch (RuntimeException unexpected) {
            observation.error(unexpected);
            result = Result.failed("UNREACHABLE", null);
        }
        String reason = result.reason() == null ? "NONE" : result.reason();
        observation.lowCardinalityKeyValue("outcome", result.outcome().name());
        observation.lowCardinalityKeyValue("reason", reason);
        observation.stop();
        Counter.builder(METRIC)
                .description("Guarded outbound destination checks by outcome and reason")
                .tag("outcome", result.outcome().name())
                .tag("reason", reason)
                .register(meters)
                .increment();
        return result;
    }

    private Result follow(URI first, boolean followRedirects, Duration readTimeout) {
        URI current = first;
        for (int hop = 0; ; hop++) {
            CheckedDestination destination;
            try {
                destination = guard.check(current);
            } catch (DestinationRefusedException refused) {
                return Result.refused(refused.refusal(), refused.detail());
            } catch (UnknownHostException unresolved) {
                return Result.failed("UNRESOLVED", null);
            }
            Exchange exchange = guarded(destination, readTimeout);
            if (exchange == null) {
                return Result.failed("CIRCUIT_OPEN", null);
            }
            if (exchange.failure() != null) {
                return Result.failed(exchange.failure(), null);
            }
            int status = exchange.status();
            if (status >= 500) {
                return Result.failed("SERVER_ERROR", status);
            }
            if (!followRedirects || !REDIRECTS.contains(status) || exchange.location() == null) {
                return Result.success(status, exchange.millis());
            }
            if (hop >= settings.maxRedirects()) {
                return Result.failed("TOO_MANY_REDIRECTS", status);
            }
            try {
                current = current.resolve(new URI(exchange.location().trim()));
            } catch (Exception badLocation) {
                return Result.failed("BAD_REDIRECT", status);
            }
        }
    }

    /** One hop through the destination's breaker and retry; {@code null} when the breaker is open. */
    private Exchange guarded(CheckedDestination destination, Duration readTimeout) {
        CircuitBreaker breaker = breakers.circuitBreaker(destination.key());
        if (!breaker.tryAcquirePermission()) {
            return null;
        }
        long started = breaker.getCurrentTimestamp();
        Retry retry = retries.retry(destination.key());
        Exchange exchange;
        try {
            exchange = retry.executeCheckedSupplier(() -> exchange(destination, readTimeout));
        } catch (Throwable unexpected) {
            breaker.onError(breaker.getCurrentTimestamp() - started, breaker.getTimestampUnit(), unexpected);
            return Exchange.failed("UNREACHABLE", false);
        }
        long elapsed = breaker.getCurrentTimestamp() - started;
        if (exchange.breakerFailure()) {
            breaker.onError(elapsed, breaker.getTimestampUnit(), new DestinationFailure(exchange));
        } else {
            breaker.onSuccess(elapsed, breaker.getTimestampUnit());
        }
        return exchange;
    }

    /** The breaker's record of a failed hop (no address, no body - only the reason). */
    private static final class DestinationFailure extends RuntimeException {

        DestinationFailure(Exchange exchange) {
            super(exchange.failure() != null ? exchange.failure() : "HTTP " + exchange.status(), null, false, false);
        }
    }

    /** Connects the pinned address, sends a bare GET, reads the status line and headers. Never throws. */
    private Exchange exchange(CheckedDestination destination, Duration readTimeout) {
        long started = System.nanoTime();
        Socket socket;
        try {
            socket = dialer.dial(new InetSocketAddress(destination.pinnedAddress(), destination.port()),
                    millis(settings.connectTimeout()));
        } catch (IOException connectFailed) {
            return Exchange.failed("UNREACHABLE", true); // nothing was sent: safe to retry
        }
        try (Socket raw = socket) {
            raw.setSoTimeout(millis(readTimeout));
            Socket io = destination.tls() ? tls(raw, destination) : raw;
            OutputStream out = io.getOutputStream();
            out.write(request(destination).getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = io.getInputStream();
            List<String> head = readHead(in);
            int status = parseStatus(head.get(0));
            String location = null;
            for (String line : head.subList(1, head.size())) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("location")) {
                    location = line.substring(colon + 1).trim();
                }
            }
            return Exchange.answered(status, location, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (SocketTimeoutException slow) {
            return Exchange.failed("TIMEOUT", false); // the request was sent: never retried
        } catch (SSLException tlsFailed) {
            return Exchange.failed("TLS_FAILED", false);
        } catch (IOException | RuntimeException broken) {
            return Exchange.failed("BAD_RESPONSE", false);
        }
    }

    private static String request(CheckedDestination destination) {
        URI uri = destination.uri();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        int defaultPort = destination.tls() ? 443 : 80;
        String host = destination.host().contains(":") && !destination.host().startsWith("[")
                ? "[" + destination.host() + "]" : destination.host();
        String hostHeader = destination.port() == defaultPort ? host : host + ":" + destination.port();
        return "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "User-Agent: integration-platform-test-connection\r\n"
                + "Accept: */*\r\n"
                + "Connection: close\r\n\r\n";
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

    /** The status line and header lines, up to the blank line (bounded; the body is never read). */
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

    private static int millis(Duration duration) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, duration.toMillis()));
    }
}
