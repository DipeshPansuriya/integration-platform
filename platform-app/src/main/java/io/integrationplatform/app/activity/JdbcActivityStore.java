package io.integrationplatform.app.activity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** RED stub (row327 T118): signatures only. */
final class JdbcActivityStore {

    record ActivityRow(long id, String actor, String action, List<String> targetIds, String before, String after,
            Instant at) {
    }

    List<ActivityRow> page(String action, Instant from, Instant toExclusive, int limit, long offset) {
        throw new UnsupportedOperationException("RED");
    }

    Optional<ActivityRow> event(long id) {
        throw new UnsupportedOperationException("RED");
    }
}
