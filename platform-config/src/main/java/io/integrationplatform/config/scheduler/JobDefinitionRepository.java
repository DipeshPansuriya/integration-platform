package io.integrationplatform.config.scheduler;

import java.util.List;
import java.util.Optional;

/**
 * E14 T-056: reads the closed register ({@code scheduler.job_definition}). The row is read fresh on every call -
 * that is what makes the row (not a cached schedule object) the one source of truth, so a row change takes effect
 * at the next schedule computation or sync. Implemented by {@link JdbcJobDefinitionRepository}.
 */
public interface JobDefinitionRepository {

    Optional<JobDefinitionRow> find(String jobType);

    List<JobDefinitionRow> all();
}