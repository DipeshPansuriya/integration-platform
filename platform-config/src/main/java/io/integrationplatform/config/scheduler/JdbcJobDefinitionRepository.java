package io.integrationplatform.config.scheduler;

import io.integrationplatform.tenancy.port.SchedulerRegistry;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * E14 T-056: registers the closed register from PostgreSQL. Every read is fresh from
 * {@code scheduler.job_definition} (V2_2_1400's schema); access is by the {@code ipaas_app} grant, no tenant
 * filtering (no row of the table is tenant data - ADR-012 section 4). The statements live in the reviewed platform
 * crossing ({@code JdbcSchedulerRegistry}, reason "ADR-012 section 4") - this repository owns no SQL and no JDBC, the
 * arch-test tenant filter rule (T-003) refuses any second scheduler call site.
 */
@Repository
public class JdbcJobDefinitionRepository implements JobDefinitionRepository {

    private static final RowMapper<JobDefinitionRow> ROW_MAPPER = (rs, rowNum) -> new JobDefinitionRow(
            rs.getString("job_type"),
            rs.getString("schedule_kind"),
            rs.getString("schedule"),
            rs.getBoolean("enabled"),
            rs.getBoolean("paused"),
            rs.getInt("max_run_seconds"),
            rs.getInt("late_threshold_seconds"),
            rs.getString("parameters"));

    private final SchedulerRegistry registry;

    public JdbcJobDefinitionRepository(SchedulerRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Optional<JobDefinitionRow> find(String jobType) {
        List<JobDefinitionRow> rows = registry.jobDefinition(jobType, ROW_MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public List<JobDefinitionRow> all() {
        return Collections.unmodifiableList(registry.jobDefinitions(ROW_MAPPER));
    }
}