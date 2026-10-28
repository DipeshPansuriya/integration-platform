package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.WriteProbe;
import java.util.List;

/**
 * Row 519 (arjun-fullstack): the forged-tenant matrix part for {@code GET} and {@code PUT
 * /api/v1/admin/systems/{systemId}/endpoint-auth} (PR 40; the PUT is TENANT_ADMIN-only since PR 48 - the matrix caller
 * is the tenant admin). The tenant never travels in the path, a header, the query or the body: it comes from the validated
 * token, and a system id of another tenant is hidden by RLS (404). Rows: the core chain's outbound system and its target
 * endpoint (seeded {@code NO_AUTH}); the legitimate PUT moves tenant A's endpoint to {@code BEARER_TOKEN} (no secret,
 * no token URL, so no destination check) and never tenant B's.
 */
final class T519EndpointAuthMatrix implements LaneMatrix {

    private static final String PATTERN = "/api/v1/admin/systems/{systemId}/endpoint-auth";
    private static final String SYSTEMS = "/api/v1/admin/systems/";

    @Override
    public List<Entry> entries() {
        return List.of(
                new Entry("GET", PATTERN, false,
                        s -> SYSTEMS + s.a().config().dstSystemId() + "/endpoint-auth",
                        s -> SYSTEMS + s.b().config().dstSystemId() + "/endpoint-auth",
                        null,
                        s -> List.of(s.a().config().dstEndpointId().toString(), "NO_AUTH")),
                new Entry("PUT", PATTERN, true,
                        s -> SYSTEMS + s.a().config().dstSystemId() + "/endpoint-auth",
                        s -> SYSTEMS + s.b().config().dstSystemId() + "/endpoint-auth",
                        s -> "{\"authMethod\":\"BEARER_TOKEN\"}",
                        s -> List.of(s.a().config().dstEndpointId().toString(), "BEARER_TOKEN"),
                        200,
                        new WriteProbe(s -> "SELECT auth_method FROM public.target_endpoint WHERE id = '"
                                + s.a().config().dstEndpointId() + "'", "NO_AUTH", "BEARER_TOKEN")));
    }
}
