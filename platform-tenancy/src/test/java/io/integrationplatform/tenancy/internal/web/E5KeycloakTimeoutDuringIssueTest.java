package io.integrationplatform.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.integrationplatform.tenancy.e5.credential.InMemorySourceCredentialStore;
import io.integrationplatform.tenancy.internal.credential.SourceCredentialLifecycle;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin.IdentityProviderAdminException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestTemplate;

/**
 * E5-F1-S3 (T-103; R13; ADR-007 Decisions 3 and 4) on the REAL Keycloak admin adapter and its real JDK client - only
 * the provider is a local HTTP server that answers the admin token at once and then never answers the client create.
 *
 * <pre>
 * &#64;negative
 * Scenario: Keycloak unavailable during issue
 *   Given the Keycloak admin API times out
 *   When a credential is issued
 *   Then the action fails cleanly with a clear message, nothing half-created is left, and the call carried timeout,
 *        transient-only retry, breaker, fallback and metrics
 * </pre>
 *
 * The five controls, each with its own evidence: (1) timeout - the issue fails after the 5 s admin timeout, not after
 * the provider's 20 s hang; (2) transient-only retry - the create reached the provider exactly once (a read timeout
 * after sending is never retried); (3) breaker - the shared {@code identity-provider} breaker counted the failed call;
 * (4) fallback - a clear {@link IdentityProviderAdminException}, no ACTIVE or PENDING row; (5) metrics - the
 * {@code identity.provider.admin} observation ended with outcome=error, and the breaker meters are registered. Not a
 * container test: no Docker, about 5 s.
 */
class E5KeycloakTimeoutDuringIssueTest {

    private HttpServer keycloak;
    private final AtomicInteger creates = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void hangingKeycloak() throws Exception {
        keycloak = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        keycloak.createContext("/realms/platform/protocol/openid-connect/token", exchange -> {
            byte[] body = "{\"access_token\":\"admin-token\",\"token_type\":\"Bearer\",\"expires_in\":60}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        keycloak.createContext("/admin/realms/platform/clients", exchange -> {
            creates.incrementAndGet();
            try {
                release.await(20, TimeUnit.SECONDS); // the provider hangs far past the 5 s admin timeout
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        keycloak.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        keycloak.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        keycloak.stop(0);
    }

    @Test
    @Tag("negative")
    @DisplayName("Scenario: Keycloak unavailable during issue - clear failure after the timeout, 5 controls, nothing half-created")
    void keycloakTimesOutDuringIssue() {
        String issuer = "http://127.0.0.1:" + keycloak.getAddress().getPort() + "/realms/platform";
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TestObservationRegistry observations = TestObservationRegistry.create();
        RestTemplate rest = KeycloakAdminAdapter.restTemplate(IdentityProviderSettings.defaults(), meters,
                observations);
        String adminSecret = UUID.randomUUID().toString();
        IdentityProviderAdmin adapter = new KeycloakAdminAdapter(issuer, "platform-admin", () -> adminSecret, rest,
                observations);
        InMemorySourceCredentialStore store = new InMemorySourceCredentialStore(Clock.systemUTC());
        SourceCredentialLifecycle lifecycle = new SourceCredentialLifecycle(store,
                TransactionOperations.withoutTransaction(), Optional.of(adapter), Duration.ofDays(7),
                Duration.ofMinutes(15));
        UUID system = UUID.randomUUID();

        long started = System.nanoTime();
        assertThatThrownBy(() -> lifecycle.issue(system, "CUSTOMER-1"))
                .isInstanceOf(IdentityProviderAdminException.class)
                .hasMessageContaining("did not create the client")
                .hasMessageContaining("try again later")
                .hasMessageNotContaining(adminSecret);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        // (1) timeout: the 5 s admin timeout bit, not the provider's 20 s hang
        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(4_500)).isLessThan(Duration.ofSeconds(12));
        // (2) transient-only retry: a create that timed out after sending is never sent again
        assertThat(creates).hasValue(1);
        // (3) breaker: the application's one identity-provider breaker counted the failure
        assertThat(meters.find("resilience4j.circuitbreaker.calls").tag("name", "identity-provider")
                .tag("kind", "failed").timer()).as("breaker failure meter").isNotNull()
                .satisfies(timer -> assertThat(timer.count()).isEqualTo(1));
        // (5) metrics + trace span: the admin observation ended in error
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(KeycloakAdminAdapter.OBSERVATION)
                .that().hasLowCardinalityKeyValue("operation", "create_client")
                .hasLowCardinalityKeyValue("outcome", "error");
        // (4) fallback: nothing half-created - no ACTIVE or PENDING row; the dead row waits for the client disable
        assertThat(store.rows.values()).singleElement().satisfies(row -> {
            assertThat(row.status).isEqualTo("REVOKED");
            assertThat(row.idpDisablePending).isTrue();
        });
    }
}
