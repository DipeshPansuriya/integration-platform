package io.integrationplatform.routing.adapter.mq;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

/** The MQ-CSV target contract (E10-F1-S2): every byte on the queue is decided here, never by the renderer. */
public record CsvContract(List<Column> columns, char delimiter, char quote, boolean quoteAll, Charset charset,
        String lineEnding, boolean header) {

    public enum ColumnType { TEXT, NUMBER }

    public record Column(String name, ColumnType type) {
        public Column {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    public CsvContract {
        columns = List.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("a CSV contract needs at least one column");
        }
        if (delimiter == quote) {
            throw new IllegalArgumentException("delimiter and quote must differ");
        }
        Objects.requireNonNull(charset, "charset");
        if (!"\r\n".equals(lineEnding) && !"\n".equals(lineEnding)) {
            throw new IllegalArgumentException("line ending must be CRLF or LF");
        }
    }
}
