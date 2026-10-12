package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import java.util.List;

/**
 * E7 seam (arjun-fullstack, F5): the forged-tenant matrix part for E7 lane C1's mapping API
 * ({@code /api/v1/admin/routes/{routeId}/mapping} - save/read, one mapping per route). Registered EMPTY because the
 * endpoints do not exist until the lane lands them; the lane fills ONLY this file and never touches
 * {@link EndpointMatrix}. See {@link LaneMatrix} for the rules.
 */
final class E7MappingMatrix implements LaneMatrix {

  @Override
  public List<Entry> entries() {
    return List.of();
  }
}