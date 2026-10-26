package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.routing.adapter.mq.CsvContract.Column;
import io.integrationplatform.routing.adapter.mq.CsvContract.ColumnType;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** E10-F1-S2 byte-for-byte: columns, order, delimiter, quoting, encoding and line ending come from the contract. */
class CsvRendererTest {

    private static final CsvContract CONTRACT = new CsvContract(
            List.of(new Column("awb", ColumnType.TEXT), new Column("pieces", ColumnType.NUMBER),
                    new Column("remark", ColumnType.TEXT)),
            ';', '"', false, StandardCharsets.ISO_8859_1, "\r\n", true);

    @Test
    void rendersTheContractByteForByte() {
        byte[] csv = CsvRenderer.render(CONTRACT, List.of(
                Map.of("remark", "fragile; \"top\"", "pieces", 3, "awb", "125-1234 5675"),
                Map.of("awb", "125-0000 0001", "pieces", "12.50", "remark", "Zürich")));

        byte[] expected = ("awb;pieces;remark\r\n"
                + "125-1234 5675;3;\"fragile; \"\"top\"\"\"\r\n"
                + "125-0000 0001;12.50;Zürich\r\n").getBytes(StandardCharsets.ISO_8859_1);
        assertThat(csv).isEqualTo(expected);
        assertThat(csv).contains((byte) 0xFC); // u-umlaut is one ISO-8859-1 byte, not UTF-8's two
    }

    @Test
    void quoteAllAndNoHeaderAndLfAreHonoured() {
        CsvContract contract = new CsvContract(List.of(new Column("a", ColumnType.TEXT),
                new Column("b", ColumnType.TEXT)), ',', '\'', true, StandardCharsets.UTF_8, "\n", false);
        Map<String, Object> row = new HashMap<>();
        row.put("a", "x");
        row.put("b", null);

        assertThat(new String(CsvRenderer.render(contract, List.of(row)), StandardCharsets.UTF_8))
                .isEqualTo("'x',''\n");
    }

    @Test
    void textInANumericColumnIsAContractFailure() {
        assertThatThrownBy(() -> CsvRenderer.render(CONTRACT,
                List.of(Map.of("awb", "125-1", "pieces", "three", "remark", "-"))))
                .isInstanceOf(CsvContractException.class)
                .hasMessageContaining("pieces");
    }

    @Test
    void aCharacterTheContractCharsetCannotEncodeIsAContractFailure() {
        assertThatThrownBy(() -> CsvRenderer.render(CONTRACT,
                List.of(Map.of("awb", "125-1", "pieces", 1, "remark", "cost €5"))))
                .isInstanceOf(CsvContractException.class)
                .hasMessageContaining("ISO-8859-1");
    }

    @Test
    void aLineBreakInAValueIsQuoted() {
        assertThat(new String(CsvRenderer.render(CONTRACT,
                List.of(Map.of("awb", "a", "pieces", 1, "remark", "two\r\nlines"))), StandardCharsets.ISO_8859_1))
                .endsWith("a;1;\"two\r\nlines\"\r\n");
    }
}
