package io.integrationplatform.app.composition;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.routing.adapter.api.ApiTargetBody;
import io.integrationplatform.routing.adapter.db.DbRows;
import io.integrationplatform.routing.stage2.Stage2Send;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** E16 content wiring: the DB rows are the transformed payload - an object is one row, an array of objects is many. */
class TransformedDbRowsTest {

    private static final Stage2Send SEND =
            new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "k", UUID.randomUUID(), false);

    private static DbRows rowsOf(String transformOutput) {
        ApiTargetBody body = send -> transformOutput == null ? Optional.empty()
                : Optional.of(new ApiTargetBody.ApiWireBody("application/json",
                        transformOutput.getBytes(StandardCharsets.UTF_8)));
        return new CompositionConfiguration().transformedDbRows(body);
    }

    private static Map<String, Object> cells(Map<String, ?> row) {
        return new java.util.LinkedHashMap<>(row);
    }

    @Test
    void anObjectIsOneRowWithTheMappingsTargetFieldsAsColumns() {
        Optional<List<Map<String, ?>>> rows = rowsOf("{\"customer_code\":\"C-1\",\"total_amount\":1250.5}").rowsFor(SEND);

        assertThat(rows).isPresent();
        assertThat(rows.get()).hasSize(1);
        assertThat(cells(rows.get().get(0))).containsEntry("customer_code", "C-1")
                .containsEntry("total_amount", new BigDecimal("1250.5"));
        assertThat(rows.get().get(0).keySet()).containsExactly("customer_code", "total_amount");
    }

    @Test
    void anArrayOfObjectsIsManyRowsInOrder() {
        List<Map<String, ?>> rows = rowsOf("[{\"a\":1,\"b\":true},{\"a\":2,\"b\":null}]").rowsFor(SEND).orElseThrow();

        assertThat(rows).hasSize(2);
        assertThat(cells(rows.get(0))).containsEntry("a", 1L).containsEntry("b", true);
        assertThat(cells(rows.get(1))).containsEntry("a", 2L).containsEntry("b", null);
    }

    @Test
    void noTransformResultIsNoRows() {
        assertThat(rowsOf(null).rowsFor(SEND)).isEmpty();
    }

    @Test
    void whatIsNotARowSetIsNoRows() {
        assertThat(rowsOf("[]").rowsFor(SEND)).as("empty array").isEmpty();
        assertThat(rowsOf("\"text\"").rowsFor(SEND)).as("scalar").isEmpty();
        assertThat(rowsOf("[1,2]").rowsFor(SEND)).as("array of scalars").isEmpty();
        assertThat(rowsOf("{\"a\":{\"b\":1}}").rowsFor(SEND)).as("nested value in a cell").isEmpty();
        assertThat(rowsOf("{\"a\":[1]}").rowsFor(SEND)).as("array in a cell").isEmpty();
        assertThat(rowsOf("not json").rowsFor(SEND)).as("malformed").isEmpty();
    }
}
