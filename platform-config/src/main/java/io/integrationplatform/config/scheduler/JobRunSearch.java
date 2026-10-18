package io.integrationplatform.config.scheduler;

import io.integrationplatform.tenancy.port.SchedulerRegistry;
import io.integrationplatform.tenancy.port.SchedulerRegistry.RunQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * E14 T-059 (E14-F2-S1, criterion E14-C9 "every run's outcome is recorded and searchable"): the operator's run search
 * over {@code scheduler.job_run} by job, outcome and date window. It validates the input and maps the rows; the
 * statement lives in the reviewed platform crossing ({@code JdbcSchedulerRegistry}, reason "ADR-012 section 4") -
 * like {@link JdbcJobDefinitionRepository}, this class owns no SQL and no JDBC, and the arch-test tenant filter rule
 * (T-003) refuses any second scheduler call site. No tenant id anywhere (jobs are platform-wide).
 */
@Service
public class JobRunSearch {

    /** V2_2_1400's job_run outcome check, verbatim. */
    static final Set<String> OUTCOMES = Set.of("SUCCEEDED", "FAILED", "MISSED");
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private static final RowMapper<JobRunView> ROW_MAPPER = (rs, rowNum) -> new JobRunView(
            rs.getObject("id", UUID.class),
            rs.getString("job_type"),
            instant(rs.getTimestamp("scheduled_for")),
            instant(rs.getTimestamp("started")),
            instant(rs.getTimestamp("finished")),
            rs.getString("instance"),
            rs.getString("outcome"),
            rs.getBoolean("is_late"),
            rs.getString("error_class"),
            rs.getString("counts"));

    private final SchedulerRegistry registry;

    public JobRunSearch(SchedulerRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Lists the matching runs, newest first. Every filter is optional (null = any); a blank job type is no filter.
     *
     * @param from  inclusive start of the window (a run's start, or its due time when it never started)
     * @param to    exclusive end of the window
     * @param limit 1..500, default 100
     * @throws IllegalArgumentException for an outcome outside SUCCEEDED / FAILED / MISSED, a {@code from} after
     *                                  {@code to}, or a limit outside 1..500
     */
    public List<JobRunView> search(String jobType, String outcome, Instant from, Instant to, Integer limit) {
        if (outcome != null && !OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("outcome must be one of " + OUTCOMES);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        int rows = limit == null ? DEFAULT_LIMIT : limit;
        if (rows < 1 || rows > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        String job = jobType == null || jobType.isBlank() ? null : jobType;
        return Collections.unmodifiableList(
                registry.searchRuns(new RunQuery(job, outcome, from, to, rows), ROW_MAPPER));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
