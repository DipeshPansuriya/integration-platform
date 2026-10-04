package io.integrationplatform.tenancy.internal.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.integrationplatform.tenancy.NoTenantBoundException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * T-004 without Docker: the tenant-scoped base composes the tenant predicate, takes the tenant only from the
 * TenantContext and refuses - before any statement is created - when no tenant is bound, when no transaction is active
 * (R11) or when a caller tries to supply the tenant itself. The container test proves the same against PostgreSQL.
 */
class TenantScopedRepositoryTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");

    private final AtomicReference<UUID> boundTenant = new AtomicReference<>();
    private final JdbcClient jdbcClient = mock(JdbcClient.class);
    private final StatementSpec statement = mock(StatementSpec.class, RETURNS_DEEP_STUBS);
    private final LaneAFixtureNoteRepository repository =
            new LaneAFixtureNoteRepository(jdbcClient, () -> Optional.ofNullable(boundTenant.get()));

    @BeforeEach
    void inTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void noBoundTenantIsRefusedBeforeAnyStatement() {
        assertThatThrownBy(repository::titles).isInstanceOf(NoTenantBoundException.class);
        assertThatThrownBy(() -> repository.retitle(TENANT_A, "x")).isInstanceOf(NoTenantBoundException.class);

        verifyNoInteractions(jdbcClient);
    }

    @Test
    void accessOutsideATransactionIsRefusedBeforeAnyStatement() {
        boundTenant.set(TENANT_A);
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatThrownBy(repository::titles)
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(NoTenantBoundException.class)
                .hasMessageContaining("transaction");

        verifyNoInteractions(jdbcClient);
    }

    @Test
    void aCallerSuppliedTenantIsRefused() {
        boundTenant.set(TENANT_A);

        assertThatThrownBy(() -> repository.titlesClaimingTenant(TENANT_B))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant_id");

        verifyNoInteractions(jdbcClient);
    }

    @Test
    void queryAddsTheTenantPredicateWithAnExplicitUuidCastAndBindsTheBoundTenant() {
        boundTenant.set(TENANT_A);
        stubStatement();

        repository.titles();

        verify(jdbcClient).sql("select title from lane_a_fixture_note WHERE tenant_id = :tenant_id::uuid");
        assertThat(boundParameters()).containsExactlyEntriesOf(Map.of("tenant_id", TENANT_A.toString()));
    }

    @Test
    void theCallerConditionIsAndedInsideParentheses() {
        boundTenant.set(TENANT_B);
        stubStatement();

        repository.titlesStartingWith("b");

        verify(jdbcClient).sql(
                "select title from lane_a_fixture_note WHERE tenant_id = :tenant_id::uuid AND (title like :prefix)");
        assertThat(boundParameters()).containsEntry("tenant_id", TENANT_B.toString()).containsEntry("prefix", "b%");
    }

    @Test
    void updateAddsTheTenantPredicate() {
        boundTenant.set(TENANT_A);
        stubStatement();
        UUID id = UUID.randomUUID();

        repository.retitle(id, "new");

        verify(jdbcClient).sql(
                "update lane_a_fixture_note set title = :title WHERE tenant_id = :tenant_id::uuid AND (id = :id)");
        assertThat(boundParameters()).containsEntry("tenant_id", TENANT_A.toString()).containsEntry("id", id)
                .containsEntry("title", "new");
    }

    private void stubStatement() {
        when(jdbcClient.sql(anyString())).thenReturn(statement);
        when(statement.params(anyMap())).thenReturn(statement);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> boundParameters() {
        ArgumentCaptor<Map<String, ?>> parameters = ArgumentCaptor.forClass(Map.class);
        verify(statement).params(parameters.capture());
        return (Map<String, Object>) parameters.getValue();
    }
}
