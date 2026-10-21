package io.integrationplatform.routing.adapter.mq;

import io.integrationplatform.routing.adapter.mq.CsvContract.Column;
import io.integrationplatform.routing.adapter.mq.CsvContract.ColumnType;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** An MQ-CSV contract as registry JSON (same style as MqSettings); a bad value is a configuration bug. */
public final class CsvContractSettings {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CsvContractSettings() {
    }

    public static CsvContract fromJson(String json) {
        JsonNode node = JSON.readTree(json);
        try {
            List<Column> columns = new ArrayList<>();
            JsonNode array = columns(node);
            for (JsonNode column : array) {
                columns.add(new Column(text(column, "name"),
                        ColumnType.valueOf(text(column, "type").toUpperCase(Locale.ROOT))));
            }
            return new CsvContract(columns, oneChar(node, "delimiter"), oneChar(node, "quote"),
                    flag(node, "quoteAll"), charset(text(node, "encoding")), lineEnding(node),
                    flag(node, "header"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("MQ CSV contract settings are invalid: " + e.getMessage());
        }
    }

    private static JsonNode columns(JsonNode node) {
        JsonNode columns = node.get("columns");
        if (columns == null || !columns.isArray()) {
            throw new IllegalStateException("MQ CSV contract settings need columns as an array");
        }
        return columns;
    }

    private static char oneChar(JsonNode node, String name) {
        String value = text(node, name);
        if (value.length() != 1) {
            throw new IllegalStateException("MQ CSV contract settings need " + name + " as one character");
        }
        return value.charAt(0);
    }

    private static boolean flag(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException("MQ CSV contract settings need " + name + " as true or false");
        }
        return value.booleanValue();
    }

    private static Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new IllegalStateException("MQ CSV contract settings need a known encoding");
        }
    }

    private static String lineEnding(JsonNode node) {
        return switch (text(node, "lineEnding").toUpperCase(Locale.ROOT)) {
            case "LF" -> "\n";
            case "CRLF" -> "\r\n";
            default -> throw new IllegalStateException("MQ CSV contract settings need lineEnding LF or CRLF");
        };
    }

    private static String text(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (field == null || !field.isTextual()) {
            throw new IllegalStateException("MQ CSV contract settings need " + name + " as text");
        }
        return field.textValue();
    }
}
