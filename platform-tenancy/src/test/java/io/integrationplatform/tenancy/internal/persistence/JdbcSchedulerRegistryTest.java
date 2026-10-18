package io.integrationplatform.tenancy.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import io.integrationplatform.tenancy.port.SchedulerRegistry.RunQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * E14 T-059 (E14-C9 "every run's outcome is recorded and searchable"), bare: the run search's SQL is owned by the
 * crossing, built only from compile-time fragments, and every filter value is a bound parameter (R11). A missed run
 * never started, so the date window and the order use {@code coalesce(started, scheduled_for)} - otherwise a date
 * search would drop every MISSED row.
 */
class JdbcSchedulerRegistryTest {

    /** Records the one query the adapter runs; no database, no Mockito varargs matching. */
    static final class RecordingJdbc extends JdbcTemplate {
        String sql;
        List<Object> args;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.args = List.of(args);
            return List.of();
        }
    }

    private final RecordingJdbc jdbc = new RecordingJdbc();
    private final JdbcSchedulerRegistry registry = new JdbcSchedulerRegistry(jdbc);
    private final RowMapper<String> mapper = (rs, rowNum) -> "row";

    @Test
    void everyFilterIsAConstantFragmentWithABoundValue() {
        Instant from = Instant.parse("2026-10-17T00:00:00Z");
        Instant to = Instant.parse("2026-10-18T00:00:00Z");

        registry.searchRuns(new RunQuery("DAILY_REPORT", "FAILED", from, to, 50), mapper);

        assertThat(jdbc.sql)
                .startsWith("select id, job_type, scheduled_for, started, finished, instance, outcome, is_late,"
                        + " error_class, counts::text as counts from scheduler.job_run where true")
                .contains(" and job_type = ?", " and outcome = ?",
                        " and coalesce(started, scheduled_for) >= ?", " and coalesce(started, scheduled_for) < ?")
                .endsWith(" order by coalesce(started, scheduled_for) desc nulls last, id limit ?")
                .doesNotContain("tenant", "DAILY_REPORT", "FAILED");
        assertThat(jdbc.args).containsExactly("DAILY_REPORT", "FAILED", Timestamp.from(from), Timestamp.from(to), 50);
    }

    @Test
    void anAbsentFilterAddsNoFragmentAndNoValue() {
        registry.searchRuns(new RunQuery(null, null, null, null, 100), mapper);

        assertThat(jdbc.sql).doesNotContain(" and ").endsWith(" where true"
                + " order by coalesce(started, scheduled_for) desc nulls last, id limit ?");
        assertThat(jdbc.args).containsExactly(100);
    }

    @Test
    void isTheDeclaredBoundaryCrossingOfTheSchedulerTables() {
        assertThat(JdbcSchedulerRegistry.class.getAnnotation(TenantBoundaryCrossing.class).value())
                .isEqualTo("ADR-012 section 4");
    }
}
