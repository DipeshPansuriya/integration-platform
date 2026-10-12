package io.integrationplatform.config.mapping.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * E7 seam (arjun-fullstack, F5), signatures only: the sample-run record the mapping test endpoint (lane B2,
 * {@code POST /api/v1/admin/mappings/{id}/test}) writes and the publish gate (lane C2, T-037) reads - E7-C5, publish
 * is refused while a mapping's {@code expressionHash} differs from its last passing run. Interface only: lane C2
 * implements it over its own tables in {@code config/mapping/internal} (JDBC); no lane edits this file.
 */
public interface MappingSampleRuns {

  /** Records one sample round trip against the stored mapping. */
  void record(UUID mappingId, String expressionHash, String sampleHash, boolean passed);

  /** When that mapping last passed with exactly this expression content; empty when it never did. */
  Optional<Instant> lastPassing(UUID mappingId, String expressionHash);

  /** The wave-1 fallback until lane C2's JDBC bean lands: records nothing, never passing. Bean wiring is lane B2's. */
  static MappingSampleRuns noop() {
    return new MappingSampleRuns() {
      @Override
      public void record(UUID mappingId, String expressionHash, String sampleHash, boolean passed) {
      }

      @Override
      public Optional<Instant> lastPassing(UUID mappingId, String expressionHash) {
        return Optional.empty();
      }
    };
  }
}