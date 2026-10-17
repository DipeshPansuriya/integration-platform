package io.integrationplatform.app.activity;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** RED stub (row327 T118): signatures only, so the activity tests compile and fail. */
public class ActivityService {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 200;

    public record Activity(String id, String actor, String action, List<String> targetIds, String before,
            String after, String at) {
    }

    public record ActivityPage(List<Activity> items, int page, int size, boolean hasMore) {
    }

    public ActivityService(JdbcActivityStore store) {
    }

    public ActivityPage list(String action, LocalDate from, LocalDate to, Integer page, Integer size) {
        throw new UnsupportedOperationException("RED");
    }

    public Optional<Activity> event(long id) {
        throw new UnsupportedOperationException("RED");
    }
}
