package io.integrationplatform.tenancy.internal.persistence;

import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.ISSUER;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_A;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_B;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.CUSTOMER_A;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.SYSTEM_A;
import static io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures.SYSTEM_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.internal.rls.RlsTestDatabase;
import io.integrationplatform.tenancy.internal.rls.SourceCredentialFixtures;
import io.integrationplatform.tenancy.port.SourceCredentialStore.Credential;
import io.integrationplatform.tenancy.port.SourceCredentialStore.NewCredential;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
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
 * E5 T-103 (ADR-007 Decision 3) on PostgreSQL as the restricted login, through the stamping transaction manager: the
 * lifecycle half of the source-credential store - issue writes PENDING and then ACTIVE, rotation schedules the old
 * credential's stop ({@code revoke_after}, V2_3_6), and the reconciliation's two sweeps (a PENDING row older than 15
 * minutes; a rotation whose grace window is over) revoke with {@code idp_disable_pending}. The client bootstrap
 * (V2_3_6 replaces V2_3_5's function, same signature and definer attributes) refuses a credential whose
 * {@code revoke_after} has passed - the grace window ends at the platform even before the job runs.
 */
@Tag("container")
class E5SourceCredentialStoreContainerTest {

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

    private <T> T inTenantTx(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private NewCredential fresh(UUID system) {
        UUID id = UUID.randomUUID();
        return new NewCredential(id, system, CUSTOMER_A, ISSUER, "src-" + id);
    }

    @Test
    @DisplayName("Issue order: insertPending writes PENDING (bootstrap refuses it), markActive makes it usable")
    void pendingThenActive() {
        bound.set(TENANT_A);
        NewCredential credential = fresh(SYSTEM_A);
        inTenantTx(() -> {
            store.insertPending(credential);
            return null;
        });
        assertThat(inTenantTx(() -> store.find(credential.id()))).get()
                .satisfies(c -> {
                    assertThat(c.status()).isEqualTo("PENDING");
                    assertThat(c.sourceSystemId()).isEqualTo(SYSTEM_A);
                    assertThat(c.customerId()).isEqualTo(CUSTOMER_A);
                    assertThat(c.clientId()).isEqualTo(credential.clientId());
                    assertThat(c.createdAt()).isNotNull();
                    assertThat(c.revokeAfter()).isNull();
                });
        assertThat(clients.resolve(ISSUER, credential.clientId())).as("a pending credential is not usable").isEmpty();

        assertThat(inTenantTx(() -> store.markActive(credential.id()))).isTrue();
        assertThat(inTenantTx(() -> store.markActive(credential.id()))).as("only PENDING -> ACTIVE").isFalse();
        assertThat(clients.resolve(ISSUER, credential.clientId())).isPresent();
        assertThat(inTenantTx(() -> store.findBySourceSystem(SYSTEM_A)))
                .extracting(Credential::id).contains(credential.id());
    }

    @Test
    @Tag("negative")
    @DisplayName("@tenant: a pending row cannot name another tenant's system; tenant B sees none of tenant A's rows")
    void tenantScope() {
        bound.set(TENANT_A);
        NewCredential foreign = fresh(SYSTEM_B);
        assertThatThrownBy(() -> inTenantTx(() -> {
            store.insertPending(foreign);
            return null;
        })).as("the composite key onto public.system refuses it").isInstanceOf(RuntimeException.class);

        NewCredential own = fresh(SYSTEM_A);
        inTenantTx(() -> {
            store.insertPending(own);
            return null;
        });
        bound.set(TENANT_B);
        assertThat(inTenantTx(() -> store.find(own.id()))).isEmpty();
        assertThat(inTenantTx(() -> store.markActive(own.id()))).isFalse();
        assertThat(inTenantTx(() -> store.scheduleRevoke(own.id(), Duration.ofDays(7)))).isFalse();
        assertThat(inTenantTx(() -> store.findBySourceSystem(SYSTEM_A))).isEmpty();
    }

    @Test
    @DisplayName("Rotation: revoke_after is set once on an ACTIVE row; both work in the grace window; past it the bootstrap refuses")
    void rotationGraceWindowEndsAtThePlatform() throws SQLException {
        bound.set(TENANT_A);
        NewCredential old = fresh(SYSTEM_A);
        inTenantTx(() -> {
            store.insertPending(old);
            return store.markActive(old.id());
        });

        assertThat(inTenantTx(() -> store.scheduleRevoke(old.id(), Duration.ofDays(7)))).isTrue();
        assertThat(inTenantTx(() -> store.scheduleRevoke(old.id(), Duration.ofDays(7)))).as("set once").isFalse();
        Instant revokeAfter = inTenantTx(() -> store.find(old.id())).orElseThrow().revokeAfter();
        assertThat(revokeAfter).isBetween(Instant.now().plus(Duration.ofDays(7)).minusSeconds(60),
                Instant.now().plus(Duration.ofDays(7)).plusSeconds(60));
        assertThat(clients.resolve(ISSUER, old.clientId())).as("valid during the grace window").isPresent();

        moveRevokeAfterIntoThePast(old.id());
        assertThat(clients.resolve(ISSUER, old.clientId()))
                .as("the grace window is over: refused at once, before any job runs").isEmpty();

        assertThat(inTenantTx(() -> store.revokeExpiredRotations())).isEqualTo(1);
        assertThat(inTenantTx(() -> store.find(old.id()))).get().satisfies(c -> {
            assertThat(c.status()).isEqualTo("REVOKED");
            assertThat(c.revokedAt()).isNotNull();
            assertThat(c.idpDisablePending()).isTrue();
        });
        assertThat(inTenantTx(() -> store.findIdpDisablePending()))
                .anyMatch(pending -> pending.credentialId().equals(old.id()));
    }

    @Test
    @DisplayName("A PENDING row older than the timeout is revoked with idp_disable_pending; a fresh one is left alone")
    void stalePendingRowsAreAbandoned() throws SQLException {
        bound.set(TENANT_A);
        NewCredential stale = fresh(SYSTEM_A);
        NewCredential recent = fresh(SYSTEM_A);
        inTenantTx(() -> {
            store.insertPending(stale);
            store.insertPending(recent);
            return null;
        });
        ageCreatedAt(stale.id(), Duration.ofMinutes(20));

        assertThat(inTenantTx(() -> store.abandonStalePending(Duration.ofMinutes(15)))).isEqualTo(1);
        assertThat(inTenantTx(() -> store.find(stale.id()))).get().satisfies(c -> {
            assertThat(c.status()).isEqualTo("REVOKED");
            assertThat(c.idpDisablePending()).isTrue();
        });
        assertThat(inTenantTx(() -> store.find(recent.id()))).get()
                .satisfies(c -> assertThat(c.status()).isEqualTo("PENDING"));
    }

    @Test
    @Tag("security")
    @DisplayName("V2_3_6: the replaced bootstrap keeps its definer attributes; the app may set revoke_after, nothing wider")
    void bootstrapAttributesAndGrantsStayNarrow() throws SQLException {
        try (Connection owner = RlsTestDatabase.get().ownerConnection();
                PreparedStatement attrs = owner.prepareStatement("select p.prosecdef, p.provolatile,"
                        + " array_to_string(p.proconfig, ';') from pg_proc p"
                        + " where p.proname = 'ipaas_client_bootstrap'");
                ResultSet rs = attrs.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1)).as("SECURITY DEFINER").isTrue();
            assertThat(rs.getString(2)).as("STABLE").isEqualTo("s");
            assertThat(rs.getString(3)).isEqualTo("search_path=pg_catalog, public");
            assertThat(rs.next()).as("one function, same signature (replaced, not overloaded)").isFalse();
        }
        try (Connection owner = RlsTestDatabase.get().ownerConnection();
                PreparedStatement grants = owner.prepareStatement("select string_agg(column_name, ',' order by"
                        + " column_name) from information_schema.column_privileges where table_name ="
                        + " 'source_credential' and grantee = 'ipaas_app' and privilege_type = 'UPDATE'");
                ResultSet rs = grants.executeQuery()) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("idp_disable_pending,revoke_after,revoked_at,status");
        }
    }

    private static void moveRevokeAfterIntoThePast(UUID id) throws SQLException {
        try (Connection owner = RlsTestDatabase.get().ownerConnection(); PreparedStatement update =
                owner.prepareStatement("update public.source_credential set revoke_after = now() - interval '1 second'"
                        + " where id = ?::uuid")) {
            update.setString(1, id.toString());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private static void ageCreatedAt(UUID id, Duration age) throws SQLException {
        try (Connection owner = RlsTestDatabase.get().ownerConnection(); PreparedStatement update =
                owner.prepareStatement("update public.source_credential set created_at = now() - make_interval("
                        + "secs => ?) where id = ?::uuid")) {
            update.setLong(1, age.toSeconds());
            update.setString(2, id.toString());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }
}
