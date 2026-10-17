package io.integrationplatform.tenancy.internal.credential;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.integrationplatform.tenancy.api.CredentialCleanup;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Row 327 T158 (E14 T-203; resilient-integration, ADR-007 Decision 4): the identity-provider call the credential
 * clean-up job makes - {@link CredentialCleanup} wired exactly as the application wires it (the lifecycle and the
 * admin configurations, the shared {@code identity-provider} breaker and retry) - against a local stub provider,
 * never a live one. Proves four of the five controls on THIS path; the fifth, the 5 s admin timeout on
 * {@code disableClient}, is KeycloakAdminAdapterTest#anAdminCallTimesOutAfterFiveSeconds.
 *
 * <pre>
 *   Given the provider answers 503 to every admin token request and the breaker threshold is 2
 *   When  the clean-up tries to disable three flagged clients
 *   Then  two calls reach the provider, the third is refused by the open breaker without a call (circuit breaker),
 *         all three rows stay pending and the job is not failed (fallback), and every call is observed with its
 *         outcome and the breaker's state is a meter (observability)
 *
 *   Given the client lookup (GET) answers 503 once and the disable (PUT) answers 503
 *   When  the clean-up disables the client
 *   Then  the GET is retried and the PUT is sent exactly once (transient-only retry), and the row stays pending
 *
 *   Given the provider confirms
 *   Then  the client is disabled with enabled=false and the row's flag is cleared
 * </pre>
 */
class IdpDisableControlsTest {

    private static final String REALM_PATH = "/realms/platform";
    private static final String TOKEN_PATH = REALM_PATH + "/protocol/openid-connect/token";
    private static final String CLIENTS_PATH = "/admin" + REALM_PATH + "/clients";

    private HttpServer provider;
    private final Map<String, AtomicInteger> requests = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<String> putBodies = Collections.synchronizedList(new ArrayList<>());
    private volatile int tokenStatus = 200;
    private volatile int lookupFailuresLeft;
    private volatile int putStatus = 204;

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final TestObservationRegistry observations = TestObservationRegistry.create();

    @BeforeEach
    void startProvider() throws IOException {
        provider = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        provider.createContext("/", this::answer);
        provider.start();
    }

    @AfterEach
    void stopProvider() {
        provider.stop(0);
    }

    @Test
    void theBreakerOpensAfterTheThresholdAndTheRestFallBackToPendingWithoutFailingTheJob() {
        tokenStatus = 503;
        Store store = new Store("src-1", "src-2", "src-3");

        runner(store).run(context -> {
            CredentialCleanup.Result result = context.getBean(CredentialCleanup.class).cleanUpBoundTenant();

            assertThat(result.cleared()).isZero();
            assertThat(result.stillPending()).isEqualTo(3);
            assertThat(count("POST " + TOKEN_PATH)).as("the third disable never reached the provider").isEqualTo(2);
            assertThat(store.pending).hasSize(3);
            TestObservationRegistryAssert.assertThat(observations)
                    .hasAnObservation(o -> o.hasNameEqualTo("identity.provider.call")
                            .hasLowCardinalityKeyValue("operation", "token")
                            .hasLowCardinalityKeyValue("outcome", "breaker_open"))
                    .hasAnObservation(o -> o.hasNameEqualTo("identity.provider.admin")
                            .hasLowCardinalityKeyValue("operation", "disable_client")
                            .hasLowCardinalityKeyValue("outcome", "error"));
            assertThat(meters.get("resilience4j.circuitbreaker.state").tag("name", "identity-provider")
                    .tag("state", "open").gauge().value()).isEqualTo(1.0);
        });
    }

    @Test
    void onlyTheIdempotentLookupIsRetriedAndTheDisableIsSentOnce() {
        lookupFailuresLeft = 1;
        putStatus = 503;
        Store store = new Store("src-1");

        runner(store).run(context -> {
            CredentialCleanup.Result result = context.getBean(CredentialCleanup.class).cleanUpBoundTenant();

            assertThat(count("GET " + CLIENTS_PATH)).as("503 to the GET lookup is retried").isEqualTo(2);
            assertThat(count("PUT " + CLIENTS_PATH + "/kc-src-1")).as("503 to the PUT is never retried").isEqualTo(1);
            assertThat(result.stillPending()).isEqualTo(1);
            assertThat(store.pending).hasSize(1);
            assertThat(meters.get("resilience4j.retry.calls").tag("name", "identity-provider")
                    .tag("kind", "successful_with_retry").functionCounter().count()).isEqualTo(1.0);
        });
    }

    @Test
    void aConfirmedDisableClearsTheFlag() {
        Store store = new Store("src-1");

        runner(store).run(context -> {
            CredentialCleanup.Result result = context.getBean(CredentialCleanup.class).cleanUpBoundTenant();

            assertThat(result.cleared()).isEqualTo(1);
            assertThat(store.pending).isEmpty();
            assertThat(putBodies).singleElement().asString().contains("\"enabled\":false");
            TestObservationRegistryAssert.assertThat(observations)
                    .hasAnObservation(o -> o.hasNameEqualTo("identity.provider.admin")
                            .hasLowCardinalityKeyValue("operation", "disable_client")
                            .hasLowCardinalityKeyValue("outcome", "success"));
        });
    }

    /** The application's wiring: the lifecycle configuration and the (package-private) admin configuration. */
    private ApplicationContextRunner runner(Store store) {
        String issuer = "http://127.0.0.1:" + provider.getAddress().getPort() + REALM_PATH;
        return new ApplicationContextRunner()
                .withUserConfiguration(SourceCredentialLifecycleConfiguration.class,
                        type("io.integrationplatform.tenancy.internal.web.IdentityProviderAdminConfiguration"))
                .withBean(SourceCredentialStore.class, () -> store)
                .withBean(PlatformTransactionManager.class, NoTransactions::new)
                .withBean(MeterRegistry.class, () -> meters)
                .withBean(ObservationRegistry.class, () -> observations)
                .withPropertyValues(
                        // R07: the admin secret's variable NAME switches the admin wiring on; a throw-away value
                        "PLATFORM_ADMIN_CLIENT_SECRET=stub-" + UUID.randomUUID(),
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuer,
                        "platform.tenancy.identity-provider.breaker-failure-threshold=2",
                        "platform.tenancy.identity-provider.breaker-wait-in-open=1m",
                        "platform.tenancy.identity-provider.retry-initial-backoff=10ms",
                        "platform.tenancy.identity-provider.retry-max-backoff=20ms");
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private int count(String request) {
        AtomicInteger counter = requests.get(request);
        return counter == null ? 0 : counter.get();
    }

    /** The stub provider: the admin token, the client lookup by clientId and the disable PUT. */
    private void answer(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        requests.computeIfAbsent(method + " " + path, key -> new AtomicInteger()).incrementAndGet();
        byte[] body = exchange.getRequestBody().readAllBytes();
        try (exchange) {
            if ("POST".equals(method) && TOKEN_PATH.equals(path)) {
                if (tokenStatus != 200) {
                    exchange.sendResponseHeaders(tokenStatus, -1);
                    return;
                }
                json(exchange, "{\"access_token\":\"stub-token\",\"token_type\":\"Bearer\",\"expires_in\":60}");
            } else if ("GET".equals(method) && CLIENTS_PATH.equals(path)) {
                if (lookupFailuresLeft > 0) {
                    lookupFailuresLeft--;
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                String clientId = exchange.getRequestURI().getQuery().replace("clientId=", "");
                json(exchange, "[{\"id\":\"kc-" + clientId + "\",\"clientId\":\"" + clientId + "\"}]");
            } else if ("PUT".equals(method) && path.startsWith(CLIENTS_PATH + "/")) {
                putBodies.add(new String(body, StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(putStatus, -1);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** The bound tenant's flagged rows of the stub's issuer; nothing to sweep. */
    private final class Store implements SourceCredentialStore {

        private final Map<UUID, PendingDisable> pending = Collections.synchronizedMap(new LinkedHashMap<>());

        Store(String... clientIds) {
            String issuer = "http://127.0.0.1:" + provider.getAddress().getPort() + REALM_PATH;
            for (String clientId : clientIds) {
                UUID id = UUID.randomUUID();
                pending.put(id, new PendingDisable(id, issuer, clientId));
            }
        }

        @Override
        public int abandonStalePending(Duration olderThan) {
            return 0;
        }

        @Override
        public int revokeExpiredRotations() {
            return 0;
        }

        @Override
        public boolean markRevoked(UUID credentialId) {
            return true;
        }

        @Override
        public boolean setIdpDisablePending(UUID credentialId, boolean flagged) {
            return flagged || pending.remove(credentialId) != null;
        }

        @Override
        public List<PendingDisable> findIdpDisablePending() {
            return List.copyOf(pending.values());
        }
    }

    /** The store fake needs no transaction; the wiring needs a manager to build its templates. */
    private static final class NoTransactions implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
