package io.integrationplatform.routing.stage1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import io.integrationplatform.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** E16 consider C5, no containers: the reconciler's read of stuck ACCEPTED messages is bounded by a LIMIT. */
@SuppressWarnings({"unchecked", "rawtypes"})
class JdbcStage1ReadsLimitTest {

    private final JdbcClient client = mock(JdbcClient.class);
    private final JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
    private final TenantContext tenant = mock(TenantContext.class);

    @BeforeEach
    void chain() {
        when(client.sql(anyString())).thenReturn(statement);
        when(statement.params(anyMap())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenReturn(new JdbcClient.MappedQuerySpec<UUID>() {
            public List<UUID> list() {
                return List.of();
            }

            public java.util.stream.Stream<UUID> stream() {
                return java.util.stream.Stream.empty();
            }
        });
        when(tenant.requireTenantId()).thenReturn(UUID.randomUUID());
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void txEnd() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void theStuckMessageReadIsLimitedTo500OldestFirstAndStaysTenantScoped() {
        new JdbcStage1Reads(client, tenant).acceptedBefore(Instant.parse("2026-10-22T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(client).sql(sql.capture());
        assertThat(sql.getValue().toLowerCase()).contains("limit 500").contains("order by")
                .contains("tenant_id = :tenant_id::uuid");
    }
}
