package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.tenancy.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * T-149 (E16-F1-S2), no containers: the endpoint row's stored DB registry JSON maps to the send target; a row we
 * cannot read is refused as the configuration bug it is, and no row (or no DB system behind the endpoint) is empty.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class JdbcDbTargetsTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ENDPOINT_ID = UUID.randomUUID();

    private final ResultSet row = mock(ResultSet.class);
    private final JdbcClient client = mock(JdbcClient.class);
    private final JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
    private final TenantContext tenant = mock(TenantContext.class);

    @BeforeEach
    void chain() {
        when(client.sql(anyString())).thenReturn(statement);
        when(statement.params(anyMap())).thenReturn(statement);
        when(statement.query(any(RowMapper.class))).thenAnswer(onCall -> {
            RowMapper<String> rowMapper = (RowMapper<String>) onCall.getArgument(0);
            return new JdbcClient.MappedQuerySpec<String>() {
                public List<String> list() {
                    List<String> values = new ArrayList<>();
                    try {
                        String value = rowMapper.mapRow(row, 0);
                        if (value != null) {
                            values.add(value);
                        }
                    } catch (SQLException broken) {
                        throw new IllegalStateException(broken);
                    }
                    return values;
                }

                public java.util.stream.Stream<String> stream() {
                    return list().stream();
                }
            };
        });
        when(tenant.requireTenantId()).thenReturn(TENANT);
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void txEnd() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void theRowMapsToTheSettingsAndTheRestrictedAccountPassword() throws Exception {
        when(row.getString("v")).thenReturn("{\"host\":\"db.example\",\"port\":5432,\"database\":\"orders\","
                + "\"user\":\"restricted\",\"table\":\"orders_in\",\"approvedTables\":[\"orders_in\"]}");

        Optional<DbTargets.DbTarget> target = targets(id -> Optional.of("db-pass".toCharArray()))
                .byEndpointId(ENDPOINT_ID);

        assertThat(target.get().settings()).isEqualTo(new DbSettings("db.example", 5432, "orders", "restricted",
                "orders_in", List.of("orders_in")));
        assertThat(target.get().password()).isEqualTo("db-pass".toCharArray());
    }

    @Test
    void noRowForTheEndpointIsEmpty() throws Exception {
        when(row.getString("v")).thenReturn(null);

        assertThat(targets(id -> Optional.empty()).byEndpointId(ENDPOINT_ID)).isEmpty();
    }

    @Test
    void anUnreadableRowRefusesTheSendAsASettingsFault() throws Exception {
        when(row.getString("v")).thenReturn("not json");

        assertThatThrownBy(() -> targets(id -> Optional.of("pw".toCharArray())).byEndpointId(ENDPOINT_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    private JdbcDbTargets targets(JdbcDbTargets.Password password) {
        return new JdbcDbTargets(client, tenant, password);
    }
}