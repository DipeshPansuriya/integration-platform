package io.integrationplatform.routing.adapter.mq;

import io.integrationplatform.routing.adapter.mq.CsvContract.Column;
import io.integrationplatform.routing.adapter.mq.CsvContract.ColumnType;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Renders rows to the bytes the contract names: column order, delimiter, RFC 4180 quoting, charset, line ending. */
public final class CsvRenderer {

    private CsvRenderer() {
    }

    public static byte[] render(CsvContract contract, List<? extends Map<String, ?>> rows) {
        StringBuilder csv = new StringBuilder();
        if (contract.header()) {
            line(csv, contract, contract.columns().stream().map(Column::name).toList());
        }
        for (Map<String, ?> row : rows) {
            line(csv, contract, contract.columns().stream().map(c -> value(c, row.get(c.name()))).toList());
        }
        try {
            ByteBuffer bytes = contract.charset().newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(csv));
            return Arrays.copyOf(bytes.array(), bytes.limit());
        } catch (CharacterCodingException e) {
            throw new CsvContractException("a value cannot be encoded in " + contract.charset().name());
        }
    }

    private static String value(Column column, Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (column.type() == ColumnType.NUMBER) {
            try {
                new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw new CsvContractException("column " + column.name() + " is NUMBER but the value is not a number");
            }
        }
        return text;
    }

    private static void line(StringBuilder csv, CsvContract contract, List<String> fields) {
        String quote = String.valueOf(contract.quote());
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                csv.append(contract.delimiter());
            }
            String field = fields.get(i);
            boolean quoted = contract.quoteAll() || field.indexOf(contract.delimiter()) >= 0
                    || field.contains(quote) || field.indexOf('\r') >= 0 || field.indexOf('\n') >= 0;
            csv.append(quoted ? quote + field.replace(quote, quote + quote) + quote : field);
        }
        csv.append(contract.lineEnding());
    }
}
