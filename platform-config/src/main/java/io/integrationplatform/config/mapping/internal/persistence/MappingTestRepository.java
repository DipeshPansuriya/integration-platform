package io.integrationplatform.config.mapping.internal.persistence;

import java.util.Optional;

/**
 * E7 lane B2: the read the mapping test endpoint makes - the live mapping by its OWN id (the endpoint path id),
 * tenant-scoped. Lane C1's {@code JdbcMappingRepository} is not edited by lane B2, so the read lives in its own
 * boundary; it reuses C1's {@link JdbcMappingRepository#LiveMapping} record so the shapes stay one shape.
 */
public interface MappingTestRepository {

  /** The live mapping with this id when the bound tenant can see it; empty for unknown, foreign or non-uuid ids. */
  Optional<JdbcMappingRepository.LiveMapping> findLiveMapping(String mappingId);
}