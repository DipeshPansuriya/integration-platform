package io.integrationplatform.config.scheduler;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * E14 T-056: registers the closed register from PostgreSQL. Every read is fresh from
 * {@code scheduler.job_definition} (V2_2_1400's schema); access is by the {@code ipaas_app} grant, no tenant
 * filtering (no row of the table is tenant data - ADR-012 section 4).
 */
@Repository
public class JdbcJobDefinitionRepository implements JobDefinitionRepository {

    private static final String SELECT =
            "select job_type, schedule_kind, schedule, enabled, paused, max_run_seconds,"
                    + " late_threshold_seconds, coalesce(parameters::text, '') as parameters"
                    + " from scheduler.job_definition";

    private static final RowMapper<JobDefinitionRow> ROW_MAPPER = (rs, rowNum) -> new JobDefinitionRow(
            rs.getString("job_type"),
            rs.getString("schedule_kind"),
            rs.getString("schedule"),
            rs.getBoolean("enabled"),
            rs.getBoolean("paused"),
            rs.getInt("max_run_seconds"),
            rs.getInt("late_threshold_seconds"),
            rs.getString("parameters"));

    private final JdbcTemplate jdbc;

    public JdbcJobDefinitionRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<JobDefinitionRow> find(String jobType) {
        List<JobDefinitionRow> rows = jdbc.query(SELECT + " where job_type = ?", ROW_MAPPER, jobType);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public List<JobDefinitionRow> all() {
        return Collections.unmodifiableList(jdbc.query(SELECT, ROW_MAPPER));
    }
}