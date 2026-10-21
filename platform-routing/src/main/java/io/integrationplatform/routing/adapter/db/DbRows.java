package io.integrationplatform.routing.adapter.db;

import io.integrationplatform.routing.stage2.Stage2Send;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The seam the adapter takes the delivery's mapped rows from (the MqCsvRows precedent; E7 transform). */
@FunctionalInterface
public interface DbRows {

    /**
     * Rows keyed by approved-table column name, or empty when no transform result exists for the send - the
     * adapter then refuses the call (PERMANENT: nothing it could write).
     */
    Optional<List<Map<String, ?>>> rowsFor(Stage2Send send);
}