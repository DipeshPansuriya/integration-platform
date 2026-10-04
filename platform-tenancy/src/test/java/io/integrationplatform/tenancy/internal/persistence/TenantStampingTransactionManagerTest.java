package io.integrationplatform.tenancy.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import io.integrationplatform.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/** ADR-007 Decision 6 rules 1 and 2, bare (no container): what the transaction manager sends at transaction start. */
class TenantStampingTransactionManagerTest {

    private static final String STAMP = "select set_config('app.tenant_id', ?, true)";
    private static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-00000000000b");

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final AtomicReference<UUID> bound = new AtomicReference<>();
    private final TenantContext tenantContext = () -> Optional.ofNullable(bound.get());

    @BeforeEach
    void connection() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
    }

    @Test
    void stampsTheBoundTenantTransactionLocallyAtTheStartOfEveryTransaction() throws SQLException {
        TenantStampingTransactionManager manager = new TenantStampingTransactionManager(dataSource, tenantContext);

        bound.set(TENANT_A);
        TransactionStatus first = manager.getTransaction(TransactionDefinition.withDefaults());
        InOrder order = inOrder(connection, statement);
        order.verify(connection).setAutoCommit(false);
        order.verify(connection).prepareStatement(STAMP);
        order.verify(statement).setString(1, TENANT_A.toString());
        order.verify(statement).execute();
        order.verify(statement).close();
        manager.commit(first);

        bound.set(TENANT_B);
        manager.commit(manager.getTransaction(TransactionDefinition.withDefaults()));
        verify(statement).setString(1, TENANT_B.toString());
    }

    @Test
    void stampsNothingWhenNoTenantIsBound() throws SQLException {
        TenantStampingTransactionManager manager = new TenantStampingTransactionManager(dataSource, tenantContext);

        manager.commit(manager.getTransaction(TransactionDefinition.withDefaults()));

        verify(connection, never()).prepareStatement(anyString());
    }

    @Test
    void aFailedStampFailsTheTransactionStartAndReleasesTheConnection() throws SQLException {
        when(statement.execute()).thenThrow(new SQLException("stamp refused"));
        TenantStampingTransactionManager manager = new TenantStampingTransactionManager(dataSource, tenantContext);
        bound.set(TENANT_A);

        assertThatThrownBy(() -> manager.getTransaction(TransactionDefinition.withDefaults()))
                .isInstanceOf(CannotCreateTransactionException.class);
        verify(connection).close();
    }

    @Test
    void isTheDeclaredBoundaryCrossingOfAdr007Decision6() {
        assertThat(TenantStampingTransactionManager.class.getAnnotation(TenantBoundaryCrossing.class).value())
                .isEqualTo("ADR-007 Decision 6");
    }
}
