package io.integrationplatform.ingest.internal.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.tenancy.NoTenantBoundException;
import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * T-010 contract matrix (E2-F1-S1: "a crash after publish and before the mark publishes the row again" must be
 * detected, not doubled). Fail-closed rules are checked without a database - a DataSource that refuses to be touched
 * proves no statement can have run; the real PostgreSQL proofs are in {@link DuplicateGuardContainerTest} and the
 * app-role proofs of the storage in {@link IdempotencyKeyRlsContainerTest}.
 *
 * <pre>
 * &#64;negative
 * Scenario: Claim with no tenant bound is refused before any statement
 *   Given no tenant is bound in the TenantContext
 *   When the guard is asked about an idempotency key
 *   Then NoTenantBoundException is thrown and no connection is taken
 *
 * &#64;negative
 * Scenario: Claim outside a transaction is refused before any statement
 *   Given tenant A is bound and no transaction is active
 *   When the guard is asked about an idempotency key
 *   Then the access is refused (a claim must commit or roll back with the consumer - R11)
 *
 * &#64;negative
 * Scenario: A blank key is refused before any statement
 *   When the guard is asked about a null or blank idempotency key
 *   Then IllegalArgumentException - a blank key could only ever match nothing, which is a caller bug
 * </pre>
 */
class DuplicateGuardContractTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");

    /** The guard of the "no statement reached the database" steps: any attempt to connect fails the test. */
    private static final class ForbiddenConnectDataSource extends AbstractDataSource {

        private boolean touched;

        private void assertUntouched() {
            assertThat(touched).as("no connection was taken").isFalse();
        }

        @Override
        public java.sql.Connection getConnection() {
            touched = true;
            throw new UnsupportedOperationException("the guard must fail closed before touching the database");
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) {
            return getConnection();
        }
    }

    @Test
    void claimWithNoTenantBoundThrowsNoTenantBoundBeforeTouchingTheDatabase() {
        ForbiddenConnectDataSource dataSource = new ForbiddenConnectDataSource();
        JdbcDuplicateGuard guard = new JdbcDuplicateGuard(JdbcClient.create(dataSource), () -> Optional.empty());

        assertThatThrownBy(() -> guard.isDuplicate("outbox:1"))
                .isInstanceOf(NoTenantBoundException.class);

        dataSource.assertUntouched();
    }

    @Test
    void claimOutsideATransactionIsRefusedBeforeTouchingTheDatabase() {
        ForbiddenConnectDataSource dataSource = new ForbiddenConnectDataSource();
        TenantContext tenantA = () -> Optional.of(TENANT_A);
        JdbcDuplicateGuard guard = new JdbcDuplicateGuard(JdbcClient.create(dataSource), tenantA);

        assertThatThrownBy(() -> guard.isDuplicate("outbox:1"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NoTenantBoundException.class)
                .hasMessageContaining("transaction");

        dataSource.assertUntouched();
    }

    @Test
    void blankKeyIsRejectedBeforeTouchingTheDatabase() {
        ForbiddenConnectDataSource dataSource = new ForbiddenConnectDataSource();
        TenantContext tenantA = () -> Optional.of(TENANT_A);
        JdbcDuplicateGuard guard = new JdbcDuplicateGuard(JdbcClient.create(dataSource), tenantA);

        assertThatThrownBy(() -> guard.isDuplicate(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.isDuplicate("   ")).isInstanceOf(IllegalArgumentException.class);

        dataSource.assertUntouched();
    }
}
