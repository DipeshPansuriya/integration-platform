package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import java.util.List;

/**
 * E5 seam (arjun-fullstack, F13): the forged-tenant matrix part owned by E5 lane C (rohit-developer, T44: T-103, T-185) - the issue, rotate and revoke endpoints and the target secrets.
 * Only that lane edits this file; see {@link LaneMatrix} for the rules.
 */
final class E5SecretMatrix implements LaneMatrix {

    @Override
    public List<Entry> entries() {
        return List.of();
    }
}
