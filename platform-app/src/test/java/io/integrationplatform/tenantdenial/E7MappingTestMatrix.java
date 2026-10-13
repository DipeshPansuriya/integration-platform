package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.WriteProbe;
import java.util.List;

/**
 * E7 lane B2 seam (arjun-fullstack, integrator, at the lane's {@code dbabfa4} landing): the forged-tenant matrix
 * part for lane B2's mapping test endpoint ({@code POST /api/v1/admin/mappings/{id}/test} - the stored compiled
 * expression runs on one posted sample, EDIT_CONFIGURATION, another tenant's mapping answers 404, never 403). The
 * endpoint targets tenant A of the seeded {@link E7MappingMatrix} live mapping (its wave-1 expression is the
 * identity {@code $}, so the sample passes and echoes), so this part seeds no rows of its own and the id fns read
 * the ids that part already plants - the two parts ride one tenant seed, like one lane.
 *
 * <p>Wave-1 write state: lane B2's {@code MappingSampleRuns} bean is the seam's no-op, so a legitimate test writes
 * nothing - the probe answers {@code 0} both ways, which still proves a forged call wrote nothing. When lane C2's
 * JDBC bean replaces the no-op (the known seam collision), {@code afterWrite} becomes {@code 1}; lane C2 supplies
 * that change at its landing, never by editing this file's semantics silently.
 */
final class E7MappingTestMatrix implements LaneMatrix {

  private static final String MAPPINGS = "/api/v1/admin/mappings";
  private static final String TEST = "/test";

  /** A benign sample: the identity expression echoes it, so tenant A's call answers OK without tenant data in it. */
  private static final String SAMPLE_BODY = "{\"sample\":{\"sampleRef\":\"matrix-sample-e7\"}}";

  @Override
  public List<Entry> entries() {
    return List.of(
        new Entry("POST", MAPPINGS + "/{id}" + TEST, true,
            s -> MAPPINGS + "/" + E7MappingMatrix.mappingIdOf(s.a()) + TEST,
            s -> MAPPINGS + "/" + E7MappingMatrix.mappingIdOf(s.b()) + TEST,
            s -> SAMPLE_BODY,
            s -> List.of(E7MappingMatrix.mappingIdOf(s.a()).toString()),
            200,
            new WriteProbe(s -> "SELECT count(*) FROM public.mapping_sample_run WHERE mapping_id = '"
                    + E7MappingMatrix.mappingIdOf(s.a()) + "'",
                "0", "0")));
  }
}