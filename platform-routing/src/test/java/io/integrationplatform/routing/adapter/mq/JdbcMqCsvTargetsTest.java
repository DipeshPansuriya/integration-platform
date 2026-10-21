package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.routing.adapter.mq.MqCsvTargets.MqCsvTarget;
import io.integrationplatform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * T-125 (E10-S2), no containers: the endpoint row's stored registry JSON maps to the send target - the settings
 * text, the JSON's optional {@code user}, the {@code contract} section parsed by {@code CsvContractSettings}, and
 * the queue-manager password from the seam. A row the target cannot express refuses as a permanent contract
 * failure, with the held password zeroed.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class JdbcMqCsvTargetsTest {

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
        // the captured row mapper runs at list() time against the row stubbed by each test; a null row is no row
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
    void theRowMapsToSettingsUserContractAndPassword() throws Exception {
        returnsRow(stored());

        Optional<MqCsvTarget> target = targets(id -> Optional.of("mq-pass".toCharArray()))
                .byEndpointId(ENDPOINT_ID);

        assertThat(target.get().settings()).isEqualTo(new MqSettings("broker.example", 61616, "t125.csv"));
        assertThat(target.get().user()).isEqualTo("mq-conn");
        assertThat(target.get().password()).isEqualTo("mq-pass".toCharArray());
        CsvContract contract = target.get().contract();
        assertThat(contract.columns()).extracting(CsvContract.Column::name).containsExactly("awb", "pieces");
        assertThat(contract.delimiter()).isEqualTo(';');
        assertThat(contract.quote()).isEqualTo('"');
        assertThat(contract.quoteAll()).isFalse();
        assertThat(contract.charset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(contract.lineEnding()).isEqualTo("\r\n");
        assertThat(contract.header()).isFalse();
    }

    @Test
    void aRowWithoutAStoredContractIsEmpty() throws Exception {
        returnsRow("{\"host\":\"broker.example\",\"port\":61616,\"queue\":\"q\"}");

        assertThat(targets(id -> Optional.empty()).byEndpointId(ENDPOINT_ID)).isEmpty();
    }

    @Test
    void noRowForTheEndpointIsEmpty() throws Exception {
        when(row.getString("v")).thenReturn(null);

        assertThat(targets(id -> Optional.empty()).byEndpointId(ENDPOINT_ID)).isEmpty();
    }

    @Test
    void anUnreadableRowRefusesTheSendAsAContractFailure() throws Exception {
        returnsRow("not json");

        assertThatThrownBy(() -> targets(id -> Optional.of("pw".toCharArray())).byEndpointId(ENDPOINT_ID))
                .isInstanceOf(CsvContractException.class);
    }

    @Test
    void aRowThatBreaksTheSettingsShapeZeroesTheHeldPasswordAndRefusesPermanently() throws Exception {
        returnsRow("{\"host\":\"broker.example\",\"port\":70000,\"queue\":\"q\",\"contract\":"
                + MqCsvSendWiringTest.STORED_CONTRACT + "}");
        char[] opened = "secret".toCharArray();

        assertThatThrownBy(() -> targets(id -> Optional.of(opened)).byEndpointId(ENDPOINT_ID))
                .isInstanceOf(CsvContractException.class);
        assertThat(opened).containsOnly('\0');
    }

    @Test
    void aMalformedContractSectionRefusesPermanently() throws Exception {
        returnsRow("{\"host\":\"broker.example\",\"port\":61616,\"queue\":\"q\",\"contract\":{}}");

        assertThatThrownBy(() -> targets(id -> Optional.of("pw".toCharArray())).byEndpointId(ENDPOINT_ID))
                .isInstanceOf(CsvContractException.class);
    }

    private void returnsRow(String settingsJson) throws Exception {
        when(row.getString("v")).thenReturn(settingsJson);
    }

    /** The seam the send's credential slice fills; here a plain lambda (the vault leg is wired, not re-proven). */
    private JdbcMqCsvTargets targets(Function<UUID, Optional<char[]>> password) {
        return new JdbcMqCsvTargets(client, tenant, password);
    }

    private static String stored() {
        return "{\"host\":\"broker.example\",\"port\":61616,\"queue\":\"t125.csv\",\"user\":\"mq-conn\","
                + "\"contract\":" + MqCsvSendWiringTest.STORED_CONTRACT + "}";
    }
}