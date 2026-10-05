package io.integrationplatform.ingest.internal.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.ingest.port.OutboxEntry;
import io.integrationplatform.ingest.port.OutboxTopic;
import io.integrationplatform.tenancy.NoTenantBoundException;
import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * T-007 contract matrix (E2-F1-S1 tasks; ADR-005 D11; ADR-007; R08, R11). The fail-closed rules are checked without
 * a database (a DataSource that refuses to be touched proves no statement can have run); the real PostgreSQL proofs
 * are in {@link OutboxWriterContainerTest} and the app-role RLS proofs in {@link OutboxRlsContainerTest}.
 *
 * <pre>
 * &#64;negative
 * Scenario: Append with no tenant bound is refused before any statement
 *   Given no tenant is bound in the TenantContext
 *   When the outbox writer appends an entry
 *   Then NoTenantBoundException is thrown and no connection is taken
 *
 * &#64;negative
 * Scenario: Append outside a transaction is refused before any statement
 *   Given tenant A is bound and no transaction is active
 *   When the outbox writer appends an entry
 *   Then the access is refused (the row must commit or roll back with the caller - R11)
 * </pre>
 */
class JdbcOutboxWriterContractTest {

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
            throw new UnsupportedOperationException("the writer must fail closed before touching the database");
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) {
            return getConnection();
        }
    }

    private static OutboxEntry entry() {
        return new OutboxEntry(OutboxTopic.MESSAGE_ACCEPTED, TENANT_A, "tenant-a/order-1", null, null);
    }

    @Test
    void appendWithNoTenantBoundThrowsNoTenantBoundBeforeTouchingTheDatabase() {
        ForbiddenConnectDataSource dataSource = new ForbiddenConnectDataSource();
        JdbcOutboxWriter writer = new JdbcOutboxWriter(JdbcClient.create(dataSource), () -> Optional.empty());

        assertThatThrownBy(() -> writer.append(entry()))
                .isInstanceOf(NoTenantBoundException.class);

        dataSource.assertUntouched();
    }

    @Test
    void appendOutsideATransactionIsRefusedBeforeTouchingTheDatabase() {
        ForbiddenConnectDataSource dataSource = new ForbiddenConnectDataSource();
        TenantContext tenantA = () -> Optional.of(TENANT_A);
        JdbcOutboxWriter writer = new JdbcOutboxWriter(JdbcClient.create(dataSource), tenantA);

        assertThatThrownBy(() -> writer.append(entry()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NoTenantBoundException.class)
                .hasMessageContaining("transaction");

        dataSource.assertUntouched();
    }

    @Test
    void keyBucketIsDeterministicForOneRecordKey() {
        assertThat(KeyBuckets.of("tenant-a/order-1"))
                .isEqualTo(KeyBuckets.of("tenant-a/order-1"))
                .isEqualTo(KeyBuckets.of(new String("tenant-a/order-1".toCharArray())));
    }

    @Test
    void keyBucketIsAlwaysWithinTheMigrationCheck0To63() {
        for (int i = 0; i < 20_000; i++) {
            int bucket = KeyBuckets.of("key-" + i + "/" + (i % 97));
            assertThat(bucket).as("bucket of key %d", i).isBetween(0, 63);
        }
        assertThat(KeyBuckets.of("")).isBetween(0, 63);
    }

    @Test
    void keyBucketsSpreadAcrossTheWholeRegister() {
        // Deterministic modulo must not pin everything into a few buckets: a 5k-key sample must touch both ends.
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < 5_000; i++) {
            int bucket = KeyBuckets.of("interface-7/business-key-" + i);
            min = Math.min(min, bucket);
            max = Math.max(max, bucket);
        }
        assertThat(min).isZero();
        assertThat(max).isEqualTo(63);
    }
}
