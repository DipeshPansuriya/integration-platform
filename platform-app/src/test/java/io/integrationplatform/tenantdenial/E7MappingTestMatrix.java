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
 * <p>Write state (lane C2's landing, T-037): the JDBC {@code MappingSampleRuns} bean replaced the wave-1 no-op, so
 * a legitimate test records one sample run. {@link E7MappingMatrix} seeds one passing run of the pinned content (the
 * publish gate's premise), so the probe reads {@code 1} seeded and {@code 2} after the legitimate write - a forged or
 * refused call must leave it at {@code 1}.
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
                "1", "2")));
  }
}