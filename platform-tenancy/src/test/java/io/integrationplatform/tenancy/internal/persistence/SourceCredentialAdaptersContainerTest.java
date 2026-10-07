package io.integrationplatform.tenancy.internal.persistence;

import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.ISSUER;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_A;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_B;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CLIENT_A;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CLIENT_A_PENDING;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CLIENT_A_REVOKED;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CLIENT_C;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CRED_A;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CUSTOMER_A;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.SYSTEM_A;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.SYSTEM_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.tenancy.NoTenantBoundException;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.internal.rls.RlsTestDatabase;
import io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures;
import io.integrationplatform.tenancy.port.ClientBinding;
import io.integrationplatform.tenancy.port.SourceCredentialStore.PendingDisable;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-098 adapters on PostgreSQL as the restricted login, through the stamping transaction manager: the
 * {@code ClientBootstrap} JDBC side (pre-tenant, like {@link JdbcLoginBootstrap}) and the {@code SourceCredentialStore}
 * lane B calls in wave 1 for the revoke order of ADR-007 Decision 3 (row revoked first; then {@code idp_disable_pending}
 * when the identity provider could not be reached; the reconciliation finds the pending rows).
 */
@Tag("container")
class SourceCredentialAdaptersContainerTest {

    private final AtomicReference<UUID> bound = new AtomicReference<>();
    private final TenantContext tenantContext = () -> Optional.ofNullable(bound.get());

    private HikariDataSource pool;
    private TransactionTemplate transaction;
    private JdbcClientBootstrap clients;
    private JdbcSourceCredentialStore store;

    @BeforeEach
    void pool() throws SQLException {
        SourceCredentialFixtures.seed();
        pool = RlsTestDatabase.get().appPool(1);
        transaction = new TransactionTemplate(new TenantStampingTransactionManager(pool, tenantContext));
        clients = new JdbcClientBootstrap(new JdbcTemplate(pool));
        store = new JdbcSourceCredentialStore(JdbcClient.create(pool), tenantContext);
    }

    @AfterEach
    void close() {
        bound.set(null);
        if (pool != null) {
            pool.close();
        }
    }

    /** One stamped transaction; a no-argument lambda keeps assertThat's overloads unambiguous. */
    private <T> T inTenantTx(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    @Test
    @DisplayName("S2: the client bootstrap adapter binds the credential's source system, customer and tenant")
    void theClientBootstrapResolvesAnActiveCredential() {
        assertThat(clients.resolve(ISSUER, CLIENT_A)).contains(new ClientBinding(CRED_A, "ACTIVE", SYSTEM_A,
                CUSTOMER_A, TENANT_A, "tenant-a", "ACTIVE", ZoneId.of("Asia/Kolkata")));
    }

    @Test
    @DisplayName("S2 @negative: pending, revoked, suspended-tenant, unknown and blank clients resolve to nothing")
    void theClientBootstrapResolvesNothingForAnInactiveCredential() {
        assertThat(clients.resolve(ISSUER, CLIENT_A_PENDING)).isEmpty();
        assertThat(clients.resolve(ISSUER, CLIENT_A_REVOKED)).isEmpty();
        assertThat(clients.resolve(ISSUER, CLIENT_C)).isEmpty();
        assertThat(clients.resolve(ISSUER, "no-such-client")).isEmpty();
        assertThat(clients.resolve(ISSUER, " ")).isEmpty();
        assertThat(clients.resolve(null, CLIENT_A)).isEmpty();
        assertThat(clients.resolve(ISSUER, null)).isEmpty();
    }

    @Test
    @DisplayName("Revoke order: markRevoked bites at once - the bootstrap refuses the credential from that commit on")
    void markRevokedTakesEffectAtOnce() throws SQLException {
        UUID id = UUID.randomUUID();
        String client = "revoke-" + id;
        SourceCredentialFixtures.insertCredential(id, ISSUER, TENANT_A, SYSTEM_A, CUSTOMER_A, client, "ACTIVE");
        assertThat(clients.resolve(ISSUER, client)).isPresent();

        bound.set(TENANT_A);
        assertThat(inTenantTx(() -> store.markRevoked(id))).isTrue();

        assertThat(clients.resolve(ISSUER, client)).as("refused from the revoke's commit on").isEmpty();
        assertThat(inTenantTx(() -> store.markRevoked(id))).as("already revoked: no effect").isFalse();
    }

    @Test
    @DisplayName("@negative @tenant: another tenant's credential cannot be revoked or flagged (0 rows, false)")
    void anotherTenantsCredentialIsNeverChanged() throws SQLException {
        UUID id = UUID.randomUUID();
        String client = "foreign-" + id;
        SourceCredentialFixtures.insertCredential(id, ISSUER, TENANT_B, SYSTEM_B, "CUSTOMER-B", client, "ACTIVE");

        bound.set(TENANT_A);
        assertThat(inTenantTx(() -> store.markRevoked(id))).isFalse();
        assertThat(inTenantTx(() -> store.setIdpDisablePending(id, true))).isFalse();
        assertThat(clients.resolve(ISSUER, client)).as("still active for tenant B").isPresent();
    }

    @Test
    @DisplayName("Revoke order: idp_disable_pending is set on a revoked row, found by the reconciliation, then cleared")
    void idpDisablePendingIsSetFoundAndCleared() throws SQLException {
        UUID id = UUID.randomUUID();
        String client = "pending-" + id;
        SourceCredentialFixtures.insertCredential(id, ISSUER, TENANT_A, SYSTEM_A, CUSTOMER_A, client, "ACTIVE");
        bound.set(TENANT_A);

        assertThat(inTenantTx(() -> store.setIdpDisablePending(id, true)))
                .as("only a REVOKED credential can wait for the identity provider").isFalse();
        inTenantTx(() -> store.markRevoked(id));
        assertThat(inTenantTx(() -> store.setIdpDisablePending(id, true))).isTrue();

        assertThat(inTenantTx(() -> store.findIdpDisablePending()))
                .contains(new PendingDisable(id, ISSUER, client))
                .allSatisfy(pending -> assertThat(pending.clientId()).isNotEqualTo(CLIENT_A));

        bound.set(TENANT_B);
        assertThat(inTenantTx(() -> store.findIdpDisablePending()))
                .as("tenant B's reconciliation step never sees tenant A's rows")
                .noneMatch(pending -> pending.credentialId().equals(id));

        bound.set(TENANT_A);
        assertThat(inTenantTx(() -> store.setIdpDisablePending(id, false))).isTrue();
        assertThat(inTenantTx(() -> store.findIdpDisablePending()))
                .noneMatch(pending -> pending.credentialId().equals(id));
    }

    @Test
    @DisplayName("R11: the store refuses to run with no bound tenant")
    void theStoreFailsClosedWithoutABoundTenant() {
        assertThatThrownBy(() -> inTenantTx(() -> store.markRevoked(CRED_A)))
                .isInstanceOf(NoTenantBoundException.class);
    }
}
