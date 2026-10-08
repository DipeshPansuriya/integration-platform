package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import java.util.List;

/**
 * E5 seam (arjun-fullstack, F13): the forged-tenant matrix part owned by E5 lane A (amit-developer, T42: T-105, T-106) - the contract import, approval and version endpoints.
 * Only that lane edits this file; see {@link LaneMatrix} for the rules.
 */
final class E5ContractMatrix implements LaneMatrix {

    @Override
    public List<Entry> entries() {
        return List.of();
    }
}
