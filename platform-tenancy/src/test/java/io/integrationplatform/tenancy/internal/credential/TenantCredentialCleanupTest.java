package io.integrationplatform.tenancy.internal.credential;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.api.CredentialCleanup;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Row 327 T158 (E14 T-203; ADR-007 Decision 3): one clean-up pass of the BOUND tenant - first the two sweeps
 * (unfinished issues, rotations past their grace window) revoke at the platform and flag the client, then the
 * identity-provider reconciliation disables every flagged client, so a row revoked by this pass is disabled in it.
 *
 * <pre>
 *   Given a stale PENDING credential, an expired rotation and an older idp_disable_pending row
 *   When  the clean-up runs for the bound tenant
 *   Then  it sweeps first and reconciles last, and reports every count
 *
 *   Given no identity-provider admin client on this instance
 *   When  the clean-up runs
 *   Then  the sweeps still run and the flagged rows stay pending for an instance that has one
 * </pre>
 */
class TenantCredentialCleanupTest {

    private static final String ISSUER = "https://idp.test/realms/platform";

    @Test
    void sweepsFirstThenDisablesEveryFlaggedClient() {
        Store store = new Store();
        Provider provider = new Provider();
        CredentialCleanup cleanup = new TenantCredentialCleanup(lifecycle(store), Optional.of(new IdpDisableReconciliation(
                store, TransactionOperations.withoutTransaction(), provider, Clock.systemUTC(), Duration.ofSeconds(30),
                Duration.ofMinutes(15))));

        CredentialCleanup.Result result = cleanup.cleanUpBoundTenant();

        assertThat(store.calls).containsExactly("abandonStalePending:PT15M", "revokeExpiredRotations",
                "findIdpDisablePending");
        assertThat(provider.disabled).containsExactlyInAnyOrder("src-stale", "src-rotated", "src-older");
        assertThat(store.pending).isEmpty();
        assertThat(result).isEqualTo(new CredentialCleanup.Result(1, 1, 3, 0, 0));
    }

    @Test
    void withoutAnIdentityProviderAdminTheSweepsRunAndTheFlagsStay() {
        Store store = new Store();
        CredentialCleanup cleanup = new TenantCredentialCleanup(lifecycle(store), Optional.empty());

        CredentialCleanup.Result result = cleanup.cleanUpBoundTenant();

        assertThat(store.calls).containsExactly("abandonStalePending:PT15M", "revokeExpiredRotations");
        assertThat(store.pending).hasSize(3);
        assertThat(result).isEqualTo(new CredentialCleanup.Result(1, 1, 0, 0, 0));
    }

    private static SourceCredentialLifecycle lifecycle(Store store) {
        return new SourceCredentialLifecycle(store, TransactionOperations.withoutTransaction(), Optional.empty(),
                Duration.ofDays(7), Duration.ofMinutes(15));
    }

    /** The bound tenant's rows; each sweep flags its row like the JDBC store's REVOKE_FOR_RECONCILIATION. */
    private static final class Store implements SourceCredentialStore {

        private final List<String> calls = new ArrayList<>();
        private final Map<UUID, PendingDisable> pending = new LinkedHashMap<>();

        Store() {
            flag("src-older");
        }

        @Override
        public int abandonStalePending(Duration olderThan) {
            calls.add("abandonStalePending:" + olderThan);
            flag("src-stale");
            return 1;
        }

        @Override
        public int revokeExpiredRotations() {
            calls.add("revokeExpiredRotations");
            flag("src-rotated");
            return 1;
        }

        @Override
        public boolean markRevoked(UUID credentialId) {
            return true;
        }

        @Override
        public boolean setIdpDisablePending(UUID credentialId, boolean flagged) {
            if (!flagged) {
                return pending.remove(credentialId) != null;
            }
            return true;
        }

        @Override
        public List<PendingDisable> findIdpDisablePending() {
            calls.add("findIdpDisablePending");
            return List.copyOf(pending.values());
        }

        private void flag(String clientId) {
            UUID id = UUID.randomUUID();
            pending.put(id, new PendingDisable(id, ISSUER, clientId));
        }
    }

    private static final class Provider implements IdentityProviderAdmin {

        private final List<String> disabled = new ArrayList<>();

        @Override
        public String issuer() {
            return ISSUER;
        }

        @Override
        public CreatedClient createConfidentialClient(String clientId) {
            throw new UnsupportedOperationException("the clean-up never creates a client");
        }

        @Override
        public void disableClient(String clientId) {
            disabled.add(clientId);
        }
    }
}
