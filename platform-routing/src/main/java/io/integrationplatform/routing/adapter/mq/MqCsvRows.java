package io.integrationplatform.routing.adapter.mq;

import io.integrationplatform.routing.stage2.Stage2Send;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The seam the adapter takes the delivery's mapped rows from (the ApiTargetBody precedent; E7 transform). */
@FunctionalInterface
public interface MqCsvRows {

    /**
     * Rows keyed by contract column name, or empty when no transform result exists for the send - the adapter
     * then refuses the call (PERMANENT: nothing it could send).
     */
    Optional<List<Map<String, ?>>> rowsFor(Stage2Send send);
}
