package io.integrationplatform.tenancy.internal.persistence;

import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.ISSUER;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.OTHER_ISSUER;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_A;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.TENANT_B;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.USERS_OF_A;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.USERS_OF_B;
import static io.integrationplatform.tenancy.internal.rls.RlsTestDatabase.USER_ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.internal.rls.RlsTestDatabase;
import io.integrationplatform.tenancy.port.LoginBinding;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-157 on the application path (E1-F2-S3, ADR-007 Decision 6 rules 1, 2 and 4): the JDBC bootstrap adapter and the
 * stamping transaction manager against PostgreSQL as the restricted login, through a pool of size 1. The tenant
 * context is a test fake, as lane B's principal resolver will bind it.
 */
@Tag("container")
class TenantBoundTransactionsContainerTest {

    private final AtomicReference<UUID> bound = new AtomicReference<>();
    private final TenantContext tenantContext = () -> Optional.ofNullable(bound.get());

    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private JdbcLoginBootstrap bootstrap;

    @BeforeEach
    void pool() {
        pool = RlsTestDatabase.get().appPool(1);
        jdbc = new JdbcTemplate(pool);
        transaction = new TransactionTemplate(new TenantStampingTransactionManager(pool, tenantContext));
        bootstrap = new JdbcLoginBootstrap(jdbc);
    }

    @AfterEach
    void close() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @DisplayName("S3: the resolver binds the tenant from the directory; the statements that follow run under it")
    void resolverBindsTheTenantFromTheDirectory() {
        Optional<LoginBinding> binding = transaction.execute(status -> bootstrap.resolve(ISSUER, "alice"));

        assertThat(binding).contains(new LoginBinding(
                USER_ALICE, "ACTIVE", TENANT_A, "tenant-a", "ACTIVE", ZoneId.of("Asia/Kolkata")));

        bound.set(binding.orElseThrow().tenantId());
        transaction.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("select current_setting('app.tenant_id')", String.class))
                    .isEqualTo(TENANT_A.toString());
            assertThat(jdbc.queryForList("select tenant_id from app_user", UUID.class))
                    .hasSize(USERS_OF_A)
                    .containsOnly(TENANT_A);
            assertThat(jdbc.queryForList("select id from tenant", UUID.class)).containsExactly(TENANT_A);
            assertThat(jdbc.queryForObject("select current_setting('app.tenant_id')", String.class))
                    .isEqualTo(TENANT_A.toString());
        });
    }

    @Test
    @DisplayName("S3 @negative: foreign, unknown, disabled-user and suspended-tenant subjects resolve to nothing")
    void bootstrapResolvesNothingForAForeignOrInactiveSubject() {
        assertThat(bootstrap.resolve(OTHER_ISSUER, "alice")).isEmpty();
        assertThat(bootstrap.resolve(ISSUER, "mallory")).isEmpty();
        assertThat(bootstrap.resolve(ISSUER, "carol")).isEmpty();
        assertThat(bootstrap.resolve(ISSUER, "dave")).isEmpty();
    }

    @Test
    @DisplayName("D6 rule 4 / S3 @negative: one pooled connection serves tenant A then B and never leaks the setting")
    void settingNeverLeaksAcrossPooledConnections() {
        bound.set(TENANT_A);
        Integer backendA = transaction.execute(status -> {
            assertThat(jdbc.queryForList("select tenant_id from app_user", UUID.class))
                    .hasSize(USERS_OF_A)
                    .containsOnly(TENANT_A);
            return jdbc.queryForObject("select pg_backend_pid()", Integer.class);
        });

        bound.set(TENANT_B);
        transaction.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("select pg_backend_pid()", Integer.class))
                    .as("the same pooled connection")
                    .isEqualTo(backendA);
            assertThat(jdbc.queryForObject("select current_setting('app.tenant_id')", String.class))
                    .isEqualTo(TENANT_B.toString());
            assertThat(jdbc.queryForList("select tenant_id from app_user", UUID.class))
                    .hasSize(USERS_OF_B)
                    .containsOnly(TENANT_B);
            assertThat(jdbc.queryForList("select id from tenant", UUID.class)).containsExactly(TENANT_B);
        });

        bound.set(null);
        transaction.executeWithoutResult(status -> assertThat(
                        jdbc.queryForObject("select current_setting('app.tenant_id', true)", String.class))
                .as("an unbound transaction inherits nothing")
                .isEmpty());
        assertThatThrownBy(() -> transaction.executeWithoutResult(
                        status -> jdbc.queryForList("select id from app_user", UUID.class)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("D6 rule 2: outside a transaction nothing is stamped, so a tenant table read fails closed")
    void aReadOutsideATransactionFailsClosedEvenWithATenantBound() {
        bound.set(TENANT_A);

        assertThatThrownBy(() -> jdbc.queryForList("select id from app_user", UUID.class))
                .isInstanceOf(DataAccessException.class);
    }
}
