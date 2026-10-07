package io.integrationplatform.tenancy.internal.web;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * A minimal OpenID provider on the loopback interface for the token-validation tests (no Keycloak in E1, ADR-007
 * Decision 1): it serves the discovery document and the JWKS of one RSA and one EC key, and signs test tokens. No
 * network outside the test JVM, no container. For the R13 fault tests it counts the requests per endpoint and answers a
 * programmed status sequence (for example 503, 503, then 200).
 */
final class LocalIssuer implements AutoCloseable {

    private static final String REALM_PATH = "/realms/platform";

    private final HttpServer server;
    private final RSAKey rsa;
    private final ECKey ec;
    private final RSAKey stranger;
    private final RSAKey unpublished;
    private volatile Duration keysDelay = Duration.ZERO;
    private final Endpoint discovery = new Endpoint();
    private final Endpoint keys = new Endpoint();

    /** One provider endpoint: a request counter, arrival times and the statuses it answers. */
    private static final class Endpoint {
        final AtomicInteger requests = new AtomicInteger();
        final List<Long> arrivals = new CopyOnWriteArrayList<>();
        final Queue<Integer> script = new ConcurrentLinkedQueue<>();
        volatile int status = 200;

        int next() {
            requests.incrementAndGet();
            arrivals.add(System.nanoTime());
            Integer scripted = script.poll();
            return scripted != null ? scripted : status;
        }
    }

    private LocalIssuer() throws Exception {
        rsa = new RSAKeyGenerator(2048).keyID("rsa-1").generate();
        ec = new ECKeyGenerator(Curve.P_256).keyID("ec-1").generate();
        stranger = new RSAKeyGenerator(2048).keyID("rsa-1").generate();
        unpublished = new RSAKeyGenerator(2048).keyID("rsa-unpublished").generate();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "local-issuer");
            t.setDaemon(true);
            return t;
        }));
        server.createContext(REALM_PATH + "/.well-known/openid-configuration", exchange -> answer(exchange, discovery, Duration.ZERO,
                "{\"issuer\":\"" + issuer() + "\",\"jwks_uri\":\"" + issuer() + "/protocol/openid-connect/certs\","
                        + "\"subject_types_supported\":[\"public\"],"
                        // E4 (T-097): the console's authorization code endpoints (never called by the bare tests).
                        + "\"authorization_endpoint\":\"" + issuer() + "/protocol/openid-connect/auth\","
                        + "\"token_endpoint\":\"" + issuer() + "/protocol/openid-connect/token\","
                        + "\"end_session_endpoint\":\"" + issuer() + "/protocol/openid-connect/logout\","
                        + "\"id_token_signing_alg_values_supported\":[\"RS256\",\"ES256\"]}"));
        server.createContext(REALM_PATH + "/protocol/openid-connect/certs", exchange -> answer(exchange, keys, keysDelay,
                new JWKSet(List.of(rsa.toPublicJWK(), ec.toPublicJWK())).toString()));
        server.start();
    }

    static LocalIssuer start() {
        try {
            return new LocalIssuer();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + REALM_PATH;
    }

    /** The JWKS answer waits this long first - the JWKS fetch timeout test. */
    void delayKeys(Duration delay) {
        this.keysDelay = delay;
    }

    /** The next discovery answers, in order (for example 503, 503); afterwards {@link #discoveryStatus}. */
    void scriptDiscovery(Integer... statuses) {
        discovery.script.addAll(List.of(statuses));
    }

    /** The next JWKS answers, in order (for example 503, 503); afterwards {@link #keysStatus}. */
    void scriptKeys(Integer... statuses) {
        keys.script.addAll(List.of(statuses));
    }

    /** Every later discovery answer has this status (200 = the document). */
    void discoveryStatus(int status) {
        discovery.status = status;
    }

    /** Every later JWKS answer has this status (200 = the key set; 503 = provider down). */
    void keysStatus(int status) {
        keys.status = status;
    }

    int discoveryRequests() {
        return discovery.requests.get();
    }

    int keyRequests() {
        return keys.requests.get();
    }

    /** Gaps between consecutive JWKS requests, in arrival order. */
    List<Duration> keyRequestGaps() {
        List<Long> at = List.copyOf(keys.arrivals);
        return IntStream.range(1, at.size())
                .mapToObj(i -> Duration.ofNanos(at.get(i) - at.get(i - 1))).toList();
    }

    /** Claims of a token that passes every check; {@code changes} edits them per test. */
    JWTClaimsSet claims(String subject, Consumer<JWTClaimsSet.Builder> changes) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject(subject)
                .audience(TenantFixtures.AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
        changes.accept(builder);
        return builder.build();
    }

    String rs256(JWTClaimsSet claims) {
        return sign(() -> new RSASSASigner(rsa), JWSAlgorithm.RS256, rsa.getKeyID(), claims);
    }

    String es256(JWTClaimsSet claims) {
        return sign(() -> new ECDSASigner(ec), JWSAlgorithm.ES256, ec.getKeyID(), claims);
    }

    /** Signed with a key the issuer never published, under a published key id. */
    String rs256WithUnknownKey(JWTClaimsSet claims) {
        return sign(() -> new RSASSASigner(stranger), JWSAlgorithm.RS256, stranger.getKeyID(), claims);
    }

    /** Signed with a key whose key id the issuer never published. */
    String rs256WithUnpublishedKeyId(JWTClaimsSet claims) {
        return sign(() -> new RSASSASigner(unpublished), JWSAlgorithm.RS256, unpublished.getKeyID(), claims);
    }

    String hs256(JWTClaimsSet claims) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return sign(() -> new MACSigner(secret), JWSAlgorithm.HS256, null, claims);
    }

    String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }

    private interface SignerFactory {
        JWSSigner create() throws Exception;
    }

    private static String sign(SignerFactory signer, JWSAlgorithm alg, String keyId, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(alg).type(JOSEObjectType.JWT).keyID(keyId).build(), claims);
            jwt.sign(signer.create());
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void answer(HttpExchange exchange, Endpoint endpoint, Duration delay, String json)
            throws IOException {
        int status = endpoint.next();
        if (status != 200) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
