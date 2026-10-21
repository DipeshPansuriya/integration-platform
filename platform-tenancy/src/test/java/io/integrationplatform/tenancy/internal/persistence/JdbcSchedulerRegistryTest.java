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

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            return query(sql, rowMapper, new Object[0]);
        }

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            this.args = java.util.Arrays.asList(args);
            return 1;
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

    /** E14 T-060: pausing records who and when on the register row; resuming clears both. Bound values only (R11). */
    @Test
    void pauseRecordsWhoAndWhenWithBoundValues() {
        Instant at = Instant.parse("2026-10-19T09:00:00Z");

        assertThat(registry.setPaused("DAILY_REPORT", true, "ops-agent", at)).isEqualTo(1);

        assertThat(jdbc.sql).isEqualTo(
                "update scheduler.job_definition set paused = ?, paused_by = ?, paused_at = ? where job_type = ?");
        assertThat(jdbc.args).containsExactly(true, "ops-agent", Timestamp.from(at), "DAILY_REPORT");
    }

    @Test
    void resumeClearsWhoAndWhen() {
        registry.setPaused("DAILY_REPORT", false, "ops-agent", Instant.parse("2026-10-19T09:00:00Z"));

        assertThat(jdbc.args).containsExactly(false, null, null, "DAILY_REPORT");
    }

    /** E14 follow-up: the register read exposes when the job was paused, so "changed since" can see a state change. */
    @Test
    void theRegisterReadIncludesPausedAt() {
        registry.jobDefinitions(mapper);
        assertThat(jdbc.sql).contains("paused, paused_at,").endsWith("from scheduler.job_definition");

        registry.jobDefinition("DAILY_REPORT", mapper);
        assertThat(jdbc.sql).contains("paused_at").endsWith(" where job_type = ?");
        assertThat(jdbc.args).containsExactly("DAILY_REPORT");
    }

    /** T-127 follow-up: the operator credential lookup is ACTIVE-only with bound values; no row is not active. */
    @Test
    void anOperatorCredentialIsActiveOnlyByItsBoundIssuerAndClient() {
        assertThat(registry.operatorCredentialActive("https://idp", "ops-agent")).isFalse();

        assertThat(jdbc.sql).isEqualTo("select 1 from scheduler.operator_credential"
                + " where issuer = ? and client_id = ? and status = 'ACTIVE'");
        assertThat(jdbc.args).containsExactly("https://idp", "ops-agent");
    }

    @Test
    void aBlankOperatorIssuerOrClientRunsNoQuery() {
        assertThat(registry.operatorCredentialActive("https://idp", " ")).isFalse();
        assertThat(registry.operatorCredentialActive(null, "ops-agent")).isFalse();

        assertThat(jdbc.sql).isNull();
    }

    @Test
    void isTheDeclaredBoundaryCrossingOfTheSchedulerTables() {
        assertThat(JdbcSchedulerRegistry.class.getAnnotation(TenantBoundaryCrossing.class).value())
                .isEqualTo("ADR-012 section 4");
    }
}
