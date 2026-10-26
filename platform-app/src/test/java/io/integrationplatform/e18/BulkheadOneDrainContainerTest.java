package io.integrationplatform.e18;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.identity.KeycloakRealmContainer.ClientCredentials;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * T-177 follow-up proof (drain fix, ADR-010 section 2 addendum): with a per-target bulkhead of ONE and a partner
 * that holds each answer, two messages posted together make the second delivery defer (BULKHEAD_FULL); it must then
 * be re-driven the moment the first send frees the slot, not by the 60 s retry job. The measured drain is printed
 * ("E18 NOTE bulkhead=1 drain ...") and bounded far below one job period.
 */
@TestPropertySource(properties = "TARGET_BULKHEAD_MAX_CONCURRENT_SENDS=1")
@DisplayName("E18 bulkhead=1: one deferral drains by the slot-free re-drive, not the 60 s retry job (T-177)")
class BulkheadOneDrainContainerTest extends E18Base {

    @Test
    @DisplayName("two concurrent messages, one slot: the deferred delivery is delivered within seconds")
    void oneDeferralDrainsWithoutTheRetryJob() throws Exception {
        grantAppRoleOnce();
        String path = "/bulkhead-one/orders";
        ClientCredentials source = newSource("e18-bh1");
        String url = "http://api.e18.test:" + PARTNER.port() + path;
        E18Seed.Tenant tenant;
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            tenant = E18Seed.onboard(owner, "bh1", "CUST-BH1", source.clientId(), KEYCLOAK.issuerUri(), "API",
                    "{\"baseUrl\":\"" + url + "\"}", url, true);
        }
        PARTNER.up();
        PARTNER.holdAnswers(); // L14: held until the deferral is observed - no 600 ms overlap guess
        try {
            String token = KEYCLOAK.clientCredentialsToken(source.clientId(), source.secret());
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> post(token, 1));
            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> post(token, 2));
            CompletableFuture.allOf(first, second).get(30, java.util.concurrent.TimeUnit.SECONDS);
            // the first send holds the only slot, so the second delivery must defer - wait for that fact, then free the slot
            await("the second delivery defers on the full bulkhead", Duration.ofSeconds(40),
                    () -> ownerLong("select count(*) from public.delivery_event where tenant_id = ?::uuid and"
                            + " type = 'BULKHEAD_FULL'", tenant.id()) > 0);
            long start = System.nanoTime();
            PARTNER.releaseAnswers();
            await("both deliveries settle", Duration.ofSeconds(40),
                    () -> ownerLong("select count(*) from public.delivery where tenant_id = ?::uuid and state ="
                            + " 'DELIVERED'", tenant.id()) == 2);
            long drainMillis = (System.nanoTime() - start) / 1_000_000L;
            long deferrals = ownerLong("select count(*) from public.delivery_event where tenant_id = ?::uuid and"
                    + " type = 'BULKHEAD_FULL'", tenant.id());
            long slotFree = ownerLong("select count(*) from public.delivery_event where tenant_id = ?::uuid and"
                    + " type = 'REDRIVE' and detail = 'slot-free'", tenant.id());
            System.out.println("E18 NOTE bulkhead=1 drain " + drainMillis + " ms after both posts accepted; "
                    + "BULKHEAD_FULL=" + deferrals + " slot-free REDRIVE=" + slotFree);

            assertThat(deferrals).as("the second delivery really deferred (else this proof is vacuous)").isGreaterThan(0);
            assertThat(slotFree).as("re-driven by the freed slot").isGreaterThan(0);
            assertThat(drainMillis).as("far below the 60 s retry job period").isLessThan(15_000);
            assertThat(PARTNER.acceptedKeysOn(path)).hasSize(2).doesNotHaveDuplicates();
        } finally {
            PARTNER.releaseAnswers();
            PARTNER.delayAnswers(0);
        }
    }

    private void post(String token, int n) {
        try {
            HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(
                            backend().resolve("/api/v1/interfaces/" + E18Seed.INTERFACE_CODE + "/messages"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("X-Source-Contract-Version", E18Seed.SOURCE_CONTRACT_LABEL)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"customerId\":\"CUST-BH1\",\"orderId\":\"ORD-BH1-"
                            + n + "\",\"amount\":" + n + "}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(answer.statusCode()).as(answer.body()).isEqualTo(202);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
