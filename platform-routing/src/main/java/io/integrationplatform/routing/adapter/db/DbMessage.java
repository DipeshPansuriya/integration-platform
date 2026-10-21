package io.integrationplatform.routing.adapter.db;

import java.util.List;
import java.util.Map;

/**
 * One resolved DB send: the target's settings, the restricted account, the approved table and the parameterised
 * values (ADR-011 DB row). The statement is built against validated plain identifiers only; every data value is a
 * statement parameter. {@code password} is zeroed by {@code DbTargetCall} after the attempt.
 *
 * <p>The send carries the platform's delivery id for the target table's {@code delivery_id} column (the adapter
 * inserts {@code ON CONFLICT (delivery_id) DO NOTHING}), so an ambiguous commit never double-inserts on retry
 * (E16-C4). shortcut: {@code delivery_id} is the fixed column convention; a per-target column name arrives with
 * the endpoint-settings registry extension.
 */
public record DbMessage(DbSettings settings, String user, char[] password, String table, List<String> columns,
        List<List<Object>> values) {

    public DbMessage {
        if (table == null || !DbSettings.validIdentifier(table)) {
            throw new IllegalStateException("the DB target needs a plain-identifier table");
        }
        if (columns == null || columns.isEmpty() || !columns.stream().allMatch(DbSettings::validIdentifier)) {
            throw new IllegalStateException("the DB target's insert needs plain-identifier column names");
        }
        if (values == null || values.stream().anyMatch(row -> row.size() != columns.size())) {
            throw new IllegalStateException("the DB target's insert rows must match the column count");
        }
    }
}