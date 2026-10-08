package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import java.util.List;

/**
 * E5 seam (arjun-fullstack, F13): the forged-tenant matrix part owned by E5 lane B (sanjay-developer, T43: T-102, T-104) - the systems registry and test-connection endpoints.
 * Only that lane edits this file; see {@link LaneMatrix} for the rules.
 */
final class E5SystemMatrix implements LaneMatrix {

    @Override
    public List<Entry> entries() {
        return List.of();
    }
}
