package io.integrationplatform.app.composition;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.routing.adapter.api.ApiTargetBody;
import io.integrationplatform.routing.adapter.mq.MqCsvRows;
import io.integrationplatform.routing.stage2.Stage2Send;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import java.util.Optional;

/**
 * E18 finding: the MQ-CSV outbound needs the delivery's mapped rows. They come from the same transformed body the
 * API and plain MQ targets use: a JSON object is one row, a JSON array of objects is several; anything else is no rows.
 */
class TransformedMqCsvRowsTest {

    private final Stage2Send send =
            new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), "k", UUID.randomUUID(), false);

    private MqCsvRows rowsOf(String json) {
        ApiTargetBody body = s -> json == null ? Optional.empty()
                : Optional.of(new ApiTargetBody.ApiWireBody("application/json", json.getBytes(StandardCharsets.UTF_8)));
        return new CompositionConfiguration().transformedMqCsvRows(body);
    }

    @Test
    void objectIsOneRowKeyedByColumnName() {
        assertThat(rowsOf("{\"awb\":\"ABX1\",\"pieces\":2}").rowsFor(send)).hasValueSatisfying(rows -> {
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("awb")).isEqualTo("ABX1");
            assertThat(rows.get(0).get("pieces").toString()).isEqualTo("2");
        });
    }

    @Test
    void arrayOfObjectsIsSeveralRowsInOrder() {
        assertThat(rowsOf("[{\"awb\":\"A\"},{\"awb\":\"B\"}]").rowsFor(send)).hasValueSatisfying(rows ->
                assertThat(rows.stream().map(r -> String.valueOf(r.get("awb"))).toList()).containsExactly("A", "B"));
    }

    @Test
    void noBodyOrANonRowShapeIsNoRows() {
        assertThat(rowsOf(null).rowsFor(send)).isEmpty();
        assertThat(rowsOf("\"text\"").rowsFor(send)).isEmpty();
        assertThat(rowsOf("[1,2]").rowsFor(send)).isEmpty();
        assertThat(rowsOf("{not json").rowsFor(send)).isEmpty();
    }
}
