package io.integrationplatform.config.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.tenancy.port.SchedulerRegistry;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;

/** E14 state-change stamp: the row mapper reads {@code state_changed_at} (null-safe) into the register row. */
class JdbcJobDefinitionRepositoryTest {

    private static final Instant AT = Instant.parse("2026-10-22T08:00:00Z");

    private final SchedulerRegistry registry = mock(SchedulerRegistry.class);
    private final JdbcJobDefinitionRepository repository = new JdbcJobDefinitionRepository(registry);

    @Test
    void theMapperCarriesStateChangedAtAndToleratesNull() throws Exception {
        ResultSet withStamp = resultSet(Timestamp.from(AT));
        ResultSet withoutStamp = resultSet(null);
        when(registry.jobDefinitions(any())).thenAnswer(invocation -> {
            RowMapper<JobDefinitionRow> mapper = invocation.getArgument(0);
            return List.of(mapper.mapRow(withStamp, 0), mapper.mapRow(withoutStamp, 1));
        });

        assertThat(repository.all()).extracting(JobDefinitionRow::stateChangedAt).containsExactly(AT, null);
    }

    @Test
    void findMapsTheSameColumn() throws Exception {
        ResultSet rs = resultSet(Timestamp.from(AT));
        when(registry.jobDefinition(eq("PAYLOAD_PURGE"), any())).thenAnswer(invocation -> {
            RowMapper<JobDefinitionRow> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });

        assertThat(repository.find("PAYLOAD_PURGE")).get().extracting(JobDefinitionRow::stateChangedAt)
                .isEqualTo(AT);
    }

    private static ResultSet resultSet(Timestamp stateChangedAt) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("job_type")).thenReturn("PAYLOAD_PURGE");
        when(rs.getString("schedule_kind")).thenReturn("CRON");
        when(rs.getString("schedule")).thenReturn("0 0 2 * * *");
        when(rs.getBoolean("enabled")).thenReturn(false);
        when(rs.getInt("max_run_seconds")).thenReturn(60);
        when(rs.getInt("late_threshold_seconds")).thenReturn(30);
        when(rs.getString("parameters")).thenReturn("{}");
        when(rs.getTimestamp("state_changed_at")).thenReturn(stateChangedAt);
        return rs;
    }
}
