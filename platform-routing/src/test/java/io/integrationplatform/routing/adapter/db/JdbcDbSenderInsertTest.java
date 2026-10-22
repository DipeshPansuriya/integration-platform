package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

/**
 * E16 leftovers: the multi-row insert bug (Karan `2026-10-22-e16-reattack.md`, "observation outside security",
 * CWE-1288 class). Every row of a multi-row delivery carried the SAME {@code delivery_id}, so
 * {@code ON CONFLICT (delivery_id) DO NOTHING} silently discarded row 2 onward while the call still reported
 * SUCCESS. The fix drops the conflict clause and checks the pre-state in the same transaction: a delivery id that
 * is already stored skips the insert whole (idempotent redelivery), a fresh delivery inserts EVERY row. No
 * containers.
 */
class JdbcDbSenderInsertTest {

    static final UUID DELIVERY = UUID.randomUUID();

    static DbMessage message() {
        return new DbMessage(new DbSettings("db.partner.example", 5432, "orders", "restricted", "orders_in",
                List.of("orders_in")), "restricted", "pw".toCharArray(), "orders_in", List.of("awb", "pieces"),
                List.of(List.of("A1", 1), List.of("A2", 2)));
    }

    /** Stubs around one connection whose pre-state check answers {@code alreadyStored}. */
    static final class Stubs {
        final Connection connection = mock(Connection.class);
        final PreparedStatement check = mock(PreparedStatement.class);
        final PreparedStatement insert = mock(PreparedStatement.class);
        final ResultSet found = mock(ResultSet.class);

        Stubs(boolean alreadyStored) throws Exception {
            when(found.next()).thenReturn(alreadyStored);
            when(check.executeQuery()).thenReturn(found);
            when(connection.prepareStatement(anyString())).thenAnswer((Answer<PreparedStatement>) invocation ->
                    invocation.getArgument(0, String.class).startsWith("SELECT") ? check : insert);
        }
    }

    @Test
    void aMultiRowDeliveryRunsAParameterisedPreStateCheckAndThenInsertsEveryRow() throws Exception {
        Stubs stubs = new Stubs(false);

        new JdbcDbSender(Duration.ofSeconds(5)).insert(stubs.connection, message(), DELIVERY);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(stubs.connection, times(2)).prepareStatement(sql.capture());
        assertThat(sql.getAllValues().get(0)).as("the pre-state check, delivery id a parameter")
                .isEqualTo(JdbcDbSender.selectDelivery("orders_in"));
        verify(stubs.check).setObject(1, DELIVERY);
        assertThat(sql.getAllValues().get(1)).as("no ON CONFLICT: every row of the delivery is stored")
                .isEqualTo(JdbcDbSender.insert(message())).doesNotContain("ON CONFLICT");
        verify(stubs.insert, times(2)).executeUpdate();
        verify(stubs.insert, times(2)).setObject(3, DELIVERY); // columns + the delivery id slot, per row
        verify(stubs.connection).commit();
    }

    @Test
    void aDeliveryIdAlreadyStoredIsSkippedWholeSoARedeliveryStoresNothingTwice() throws Exception {
        Stubs stubs = new Stubs(true);

        new JdbcDbSender(Duration.ofSeconds(5)).insert(stubs.connection, message(), DELIVERY);

        verify(stubs.connection, times(1)).prepareStatement(JdbcDbSender.selectDelivery("orders_in"));
        verify(stubs.connection, never()).prepareStatement(startsWith("INSERT"));
        verify(stubs.connection).commit();
    }
}