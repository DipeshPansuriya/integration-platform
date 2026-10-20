package io.integrationplatform.routing.adapter.db;

import io.integrationplatform.routing.stage2.Stage2Send;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * T-149 send wiring: the target's stored settings and the delivery's mapped rows decide the parameterised insert
 * at send time (the MqCsvOutbound shape). A resolved {@link DbMessage} carries the approved table, the first
 * mapped row's column order, and every row's values; a row the contract cannot express leaves as an
 * {@link IllegalStateException} ({@code DbTargetCall} classes it PERMANENT, never reaching the target).
 */
public final class DbTargetOutbound implements DbOutbound {

    private final DbTargets targets;
    private final DbRows rows;

    public DbTargetOutbound(DbTargets targets, DbRows rows) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.rows = Objects.requireNonNull(rows, "rows");
    }

    @Override
    public Optional<DbMessage> resolve(Stage2Send send) {
        DbTargets.DbTarget target = targets.byEndpointId(send.targetEndpointId()).orElse(null);
        if (target == null) {
            return Optional.empty();
        }
        Optional<List<Map<String, ?>>> mapped = rows.rowsFor(send);
        if (mapped.isEmpty() || mapped.get().isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, ?>> rowsOfSend = mapped.get();
        List<String> columns = new ArrayList<>(rowsOfSend.get(0).keySet());
        List<List<Object>> values = new ArrayList<>();
        for (Map<String, ?> row : rowsOfSend) {
            List<Object> cellValues = new ArrayList<>();
            for (String column : columns) {
                if (!row.containsKey(column)) {
                    throw new IllegalStateException("a mapped row is missing column '" + column + "'");
                }
                cellValues.add(row.get(column));
            }
            values.add(cellValues);
        }
        DbSettings settings = target.settings();
        return Optional.of(new DbMessage(settings, settings.user(), target.password(), settings.table(), columns,
                values));
    }
}