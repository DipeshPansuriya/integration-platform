package io.integrationplatform.tenancy.internal.credential;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * T-186 (ADR-007 Decision 3, S-j) without containers: the revoke order - the platform row FIRST (with
 * {@code idp_disable_pending} written ahead in the same transaction), THEN the identity provider - and the credential
 * reconciliation that retries every pending row with backoff until the provider confirms. The end-to-end proof with a
 * real Keycloak and PostgreSQL is {@code MachineClientAudienceAndRevokeContainerTest}.
 */
class RevokeOrderTest {

    private static final String ISSUER = "http://idp.test/realms/platform";

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final FakeStore store = new FakeStore();
    private final FakeProvider provider = new FakeProvider();

    @Test
    @DisplayName("Revoke: the row is revoked first, then the provider disables the client, then the pending flag clears")
    void revokeIsPlatformFirst() {
        UUID id = store.active("src-a");

        SourceCredentialRevocation.Outcome outcome =
                new SourceCredentialRevocation(store, TransactionOperations.withoutTransaction(), provider).revoke(id);

        assertThat(outcome).isEqualTo(SourceCredentialRevocation.Outcome.REVOKED);
        assertThat(events).containsExactly("markRevoked " + id, "pending " + id + "=true", "disable src-a",
                "pending " + id + "=false");
        assertThat(store.rows.get(id).pending).isFalse();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Revoke with the provider down: the row stays revoked AND idp_disable_pending - never rolled back")
    void revokeWithTheProviderDownLeavesThePendingFlag() {
        UUID id = store.active("src-a");
        provider.down = true;

        SourceCredentialRevocation.Outcome outcome =
                new SourceCredentialRevocation(store, TransactionOperations.withoutTransaction(), provider).revoke(id);

        assertThat(outcome).isEqualTo(SourceCredentialRevocation.Outcome.IDP_DISABLE_PENDING);
        assertThat(events).containsExactly("markRevoked " + id, "pending " + id + "=true", "disable src-a FAILED");
        assertThat(store.rows.get(id).revoked).isTrue();
        assertThat(store.rows.get(id).pending).isTrue();
    }

    @Test
    @Tag("negative")
    @DisplayName("Revoke of an unknown or already revoked credential changes nothing and calls no provider")
    void revokeOfAnUnknownCredential() {
        SourceCredentialRevocation.Outcome outcome = new SourceCredentialRevocation(store,
                TransactionOperations.withoutTransaction(), provider).revoke(UUID.randomUUID());

        assertThat(outcome).isEqualTo(SourceCredentialRevocation.Outcome.NOT_FOUND);
        assertThat(events).noneMatch(event -> event.startsWith("disable"));
    }

    @Test
    @DisplayName("Reconciliation: retried with backoff until the provider confirms, then the flag clears")
    void reconciliationRetriesWithBackoff() {
        UUID id = store.active("src-a");
        provider.down = true;
        new SourceCredentialRevocation(store, TransactionOperations.withoutTransaction(), provider).revoke(id);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
        IdpDisableReconciliation reconciliation = new IdpDisableReconciliation(store,
                TransactionOperations.withoutTransaction(), provider, clock, Duration.ofSeconds(30),
                Duration.ofMinutes(15));

        assertThat(reconciliation.reconcile()).isEqualTo(new IdpDisableReconciliation.Result(0, 1, 0));
        assertThat(reconciliation.reconcile()).as("inside the backoff: not tried again")
                .isEqualTo(new IdpDisableReconciliation.Result(0, 0, 1));
        clock.advance(Duration.ofSeconds(31));
        assertThat(reconciliation.reconcile()).as("still down: tried, backoff doubles")
                .isEqualTo(new IdpDisableReconciliation.Result(0, 1, 0));
        clock.advance(Duration.ofSeconds(31));
        assertThat(reconciliation.reconcile()).as("second wait is 60 s").isEqualTo(
                new IdpDisableReconciliation.Result(0, 0, 1));

        provider.down = false;
        clock.advance(Duration.ofSeconds(30));
        assertThat(reconciliation.reconcile()).isEqualTo(new IdpDisableReconciliation.Result(1, 0, 0));
        assertThat(store.rows.get(id).pending).isFalse();
        assertThat(provider.disabled).containsExactly("src-a");
        assertThat(reconciliation.reconcile()).isEqualTo(new IdpDisableReconciliation.Result(0, 0, 0));
    }

    @Test
    @DisplayName("Reconciliation: the backoff is capped")
    void theBackoffIsCapped() {
        assertThat(IdpDisableReconciliation.backoff(1, Duration.ofSeconds(30), Duration.ofMinutes(15)))
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(IdpDisableReconciliation.backoff(3, Duration.ofSeconds(30), Duration.ofMinutes(15)))
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(IdpDisableReconciliation.backoff(40, Duration.ofSeconds(30), Duration.ofMinutes(15)))
                .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @Tag("negative")
    @DisplayName("A pending row of ANOTHER issuer is never sent to this provider (same client id, different client)")
    void aRowOfAnotherIssuerIsLeftPending() {
        UUID id = UUID.randomUUID();
        store.rows.put(id, new Row(id, "https://other.test/realms/x", "src-a"));
        store.rows.get(id).revoked = true;
        store.rows.get(id).pending = true;

        IdpDisableReconciliation.Result result = new IdpDisableReconciliation(store,
                TransactionOperations.withoutTransaction(), provider, Clock.systemUTC(), Duration.ofSeconds(30),
                Duration.ofMinutes(15)).reconcile();

        assertThat(result).isEqualTo(new IdpDisableReconciliation.Result(0, 1, 0));
        assertThat(provider.disabled).isEmpty();
        assertThat(store.rows.get(id).pending).isTrue();
    }

    // ---- fakes ----------------------------------------------------------------------------------------------------

    private static final class Row {
        final UUID id;
        final String issuer;
        final String clientId;
        boolean revoked;
        boolean pending;

        Row(UUID id, String issuer, String clientId) {
            this.id = id;
            this.issuer = issuer;
            this.clientId = clientId;
        }
    }

    private final class FakeStore implements SourceCredentialStore {

        final Map<UUID, Row> rows = new LinkedHashMap<>();

        UUID active(String clientId) {
            UUID id = UUID.randomUUID();
            rows.put(id, new Row(id, ISSUER, clientId));
            return id;
        }

        @Override
        public boolean markRevoked(UUID credentialId) {
            Row row = rows.get(credentialId);
            if (row == null || row.revoked) {
                return false;
            }
            events.add("markRevoked " + credentialId);
            row.revoked = true;
            return true;
        }

        @Override
        public boolean setIdpDisablePending(UUID credentialId, boolean pending) {
            Row row = rows.get(credentialId);
            if (row == null || !row.revoked) {
                return false;
            }
            events.add("pending " + credentialId + "=" + pending);
            row.pending = pending;
            return true;
        }

        @Override
        public List<PendingDisable> findIdpDisablePending() {
            List<PendingDisable> pending = new ArrayList<>();
            rows.values().stream().filter(row -> row.pending)
                    .forEach(row -> pending.add(new PendingDisable(row.id, row.issuer, row.clientId)));
            return pending;
        }
    }

    private final class FakeProvider implements IdentityProviderAdmin {

        boolean down;
        final List<String> disabled = new ArrayList<>();

        @Override
        public String issuer() {
            return ISSUER;
        }

        @Override
        public CreatedClient createConfidentialClient(String clientId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void disableClient(String clientId) {
            if (down) {
                events.add("disable " + clientId + " FAILED");
                throw new IdentityProviderAdminException("the identity provider is unavailable", null);
            }
            events.add("disable " + clientId);
            disabled.add(clientId);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
