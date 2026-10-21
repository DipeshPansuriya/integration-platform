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

/** T-125 golden bytes: each expected array is literal byte values, so no charset or line ending can drift unnoticed. */
class CsvContractGoldenBytesTest {

    private static final Column TEXT_AWB = new Column("awb", ColumnType.TEXT);
    private static final Column NUMBER_PIECES = new Column("pieces", ColumnType.NUMBER);

    private static CsvContract utf8Lf(char delimiter, boolean quoteAll, boolean header) {
        return new CsvContract(List.of(TEXT_AWB, NUMBER_PIECES), delimiter, '"', quoteAll,
                StandardCharsets.UTF_8, "\n", header);
    }

    @Test
    void basicRowIsExactlyTheContractBytes() {
        byte[] actual = CsvRenderer.render(utf8Lf(',', false, false), List.of(Map.of("awb", "ABX1", "pieces", 3)));

        byte[] expected = { 'A', 'B', 'X', '1', ',', '3', '\n' };
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void headerRowComesFirstWhenTheContractAsksForOne() {
        byte[] actual = CsvRenderer.render(utf8Lf(',', false, true), List.of(Map.of("awb", "ABX1", "pieces", 3)));

        byte[] expected = { 'a', 'w', 'b', ',', 'p', 'i', 'e', 'c', 'e', 's', '\n',
                'A', 'B', 'X', '1', ',', '3', '\n' };
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void fieldHoldingDelimiterAndQuoteIsQuotedWithTheQuoteDoubled() {
        CsvContract contract = new CsvContract(List.of(TEXT_AWB), ',', '"', false, StandardCharsets.UTF_8, "\n", false);
        byte[] actual = CsvRenderer.render(contract, List.of(Map.of("awb", "a,\"b")));

        byte[] expected = { '"', 'a', ',', '"', '"', 'b', '"', '\n' };
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void crlfContractEndsEveryRowWithCarriageReturnThenLineFeed() {
        CsvContract crlf = new CsvContract(List.of(TEXT_AWB, NUMBER_PIECES), ';', '"', false,
                StandardCharsets.UTF_8, "\r\n", false);
        byte[] actual = CsvRenderer.render(crlf, List.of(Map.of("awb", "ABX1", "pieces", 3)));

        byte[] expected = { 'A', 'B', 'X', '1', ';', '3', '\r', '\n' };
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void emptyFieldStillTakesItsDelimiterSlot() {
        Map<String, Object> row = new HashMap<>();
        row.put("awb", null);
        row.put("pieces", 7);

        byte[] expected = { ',', '7', '\n' };
        assertThat(CsvRenderer.render(utf8Lf(',', false, false), List.of(row))).isEqualTo(expected);
    }

    @Test
    void utf8EncodingIsPinnedToItsBytesForANonAsciiValue() {
        CsvContract contract = new CsvContract(List.of(TEXT_AWB), ',', '"', false, StandardCharsets.UTF_8, "\n", false);
        byte[] actual = CsvRenderer.render(contract, List.of(Map.of("awb", "É")));

        byte[] expected = { (byte) 0xC3, (byte) 0x89, '\n' };
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void textInANumericColumnFailsBeforeAnyByteIsProduced() {
        assertThatThrownBy(() -> CsvRenderer.render(utf8Lf(',', false, false),
                List.of(Map.of("awb", "ABX1", "pieces", "three"))))
                .isInstanceOf(CsvContractException.class)
                .hasMessageContaining("pieces");
    }

    @Test
    void contractParsesFromRegistryJsonIntoTheSameContract() {
        CsvContract parsed = CsvContractSettings.fromJson("""
                {"columns":[{"name":"awb","type":"TEXT"},{"name":"pieces","type":"NUMBER"}],
                 "delimiter":",","quote":"\\"","quoteAll":false,"encoding":"UTF-8",
                 "lineEnding":"CRLF","header":true}
                """);

        assertThat(parsed).isEqualTo(new CsvContract(List.of(TEXT_AWB, NUMBER_PIECES), ',', '"', false,
                StandardCharsets.UTF_8, "\r\n", true));
    }

    @Test
    void badContractJsonIsAConfigurationBugNotAFormatFailure() {
        assertThatThrownBy(() -> CsvContractSettings.fromJson("{\"columns\":[],\"delimiter\":\",\",\"quote\":"
                + "\"\\\"\",\"quoteAll\":false,\"encoding\":\"UTF-8\",\"lineEnding\":\"LF\",\"header\":false}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CsvContractSettings.fromJson(
                "{\"columns\":[{\"name\":\"a\",\"type\":\"TEXT\"}],\"delimiter\":\",\",\"quote\":\"\\\"\",\""
                + "quoteAll\":false,\"encoding\":\"UTF-8\",\"lineEnding\":\"CR\",\"header\":false}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lineEnding");
    }
}
