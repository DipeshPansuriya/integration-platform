package io.integrationplatform.transform.api;

/**
 * E7-C4/C7/C8 (ADR-006, Accepted 2026-10-12): the seam Arjun writes before any lane starts - signatures only, no
 * logic. Lane B2 (T-108) implements it calling {@code com.dashjoin:jsonata} directly and owns the 5 s budget
 * (T-109); lane C2's publish gate (T-037) and lane B3's parity/perf tests call it through this interface, so no
 * caller ever touches the engine library. Lanes never edit this file - a signature request goes to arjun-fullstack.
 */
public interface TransformPort {

  /**
   * Evaluates one JSONata expression against one input document, resolving lookup-table references through
   * {@code lookups}. Never returns null; a runaway expression stops at the 5 s budget with {@link Result#status}
   * {@link Status#MAPPING_TIMEOUT} (E7-C10), any engine failure with {@link Status#ERROR} and a human-readable
   * {@link Result#error}.
   */
  Result transform(String expression, String inputJson, LookupSource lookups);

  /** The engine's answer: exactly one of {@code OK} (output), {@code MAPPING_TIMEOUT} or {@code ERROR}. */
  enum Status { OK, MAPPING_TIMEOUT, ERROR }

  /** The transform result; {@code outputJson} is null unless the status is OK. */
  record Result(Status status, String outputJson, String error) {}
}