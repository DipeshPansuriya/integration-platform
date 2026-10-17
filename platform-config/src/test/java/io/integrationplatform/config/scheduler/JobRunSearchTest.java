package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.integrationplatform.tenancy.port.SchedulerRegistry;
import io.integrationplatform.tenancy.port.SchedulerRegistry.RunQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;

/**
 * E14 T-059 (E14-C9, JVM red): the operator's run search validates its input before it reaches the reviewed crossing
 * ({@link SchedulerRegistry#searchRuns}) and maps each {@code scheduler.job_run} row to a {@link JobRunView}. No tenant
 * id anywhere (ADR-012 section 4: jobs are platform-wide).
 */
class JobRunSearchTest {

    static final Instant FROM = Instant.parse("2026-10-17T00:00:00Z");
    static final Instant TO = Instant.parse("2026-10-18T00:00:00Z");

    private final SchedulerRegistry registry = mock(SchedulerRegistry.class);
    private final JobRunSearch search = new JobRunSearch(registry);

    private RunQuery sentQuery() {
        ArgumentCaptor<RunQuery> query = ArgumentCaptor.forClass(RunQuery.class);
        verify(registry).searchRuns(query.capture(), any());
        return query.getValue();
    }

    @Test
    void passesTheFiltersToTheCrossingWithABlankJobAsNoFilterAndTheDefaultLimit() {
        search.search(" ", "FAILED", FROM, TO, null);

        assertThat(sentQuery()).isEqualTo(new RunQuery(null, "FAILED", FROM, TO, 100));
    }

    @Test
    void absentFiltersStayAbsentAndAnExplicitLimitIsKept() {
        search.search("DAILY_REPORT", null, null, null, 500);

        assertThat(sentQuery()).isEqualTo(new RunQuery("DAILY_REPORT", null, null, null, 500));
    }

    @Test
    void refusesAnOutcomeOutsideTheClosedSet() {
        assertThatThrownBy(() -> search.search(null, "RUNNING", null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outcome");
        verifyNoInteractions(registry);
    }

    @Test
    void refusesAWindowWhoseFromIsAfterItsTo() {
        assertThatThrownBy(() -> search.search(null, null, TO, FROM, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("from");
        verifyNoInteractions(registry);
    }

    @Test
    void refusesALimitOutsideOneToFiveHundred() {
        assertThatThrownBy(() -> search.search(null, null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> search.search(null, null, null, null, 501))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
        verifyNoInteractions(registry);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void mapsEveryColumnOfARunRow() throws SQLException {
        UUID id = UUID.randomUUID();
        Instant started = FROM.plusSeconds(60);
        Instant finished = FROM.plusSeconds(90);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getString("job_type")).thenReturn("DAILY_REPORT");
        when(rs.getTimestamp("scheduled_for")).thenReturn(Timestamp.from(FROM));
        when(rs.getTimestamp("started")).thenReturn(Timestamp.from(started));
        when(rs.getTimestamp("finished")).thenReturn(Timestamp.from(finished));
        when(rs.getString("instance")).thenReturn("app-1");
        when(rs.getString("outcome")).thenReturn("FAILED");
        when(rs.getBoolean("is_late")).thenReturn(true);
        when(rs.getString("error_class")).thenReturn("java.io.UncheckedIOException");
        when(rs.getString("counts")).thenReturn("{\"rows\": 12}");

        search.search(null, null, null, null, null);
        ArgumentCaptor<RowMapper> mapper = ArgumentCaptor.forClass(RowMapper.class);
        verify(registry).searchRuns(any(), mapper.capture());

        assertThat(mapper.getValue().mapRow(rs, 0)).isEqualTo(new JobRunView(id, "DAILY_REPORT", FROM, started,
                finished, "app-1", "FAILED", true, "java.io.UncheckedIOException", "{\"rows\": 12}"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aMissedRunMapsWithNoStartAndNoEnd() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("outcome")).thenReturn("MISSED");
        when(rs.getTimestamp("scheduled_for")).thenReturn(Timestamp.from(FROM));

        search.search(null, "MISSED", null, null, null);
        ArgumentCaptor<RowMapper> mapper = ArgumentCaptor.forClass(RowMapper.class);
        verify(registry).searchRuns(any(), mapper.capture());

        JobRunView missed = (JobRunView) mapper.getValue().mapRow(rs, 0);
        assertThat(missed.outcome()).isEqualTo("MISSED");
        assertThat(missed.scheduledFor()).isEqualTo(FROM);
        assertThat(missed.started()).isNull();
        assertThat(missed.finished()).isNull();
    }
}
