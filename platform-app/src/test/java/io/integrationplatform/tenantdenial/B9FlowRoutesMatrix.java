package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import java.util.List;

/**
 * Row 278 wave 2, T81 / B9 (lane D, vijay-developer): the forged-tenant matrix part for the routes-listing read
 * ({@code GET /api/v1/admin/flows/{flowId}/routes} - the flow's current version's bundle routes, the same guard as
 * {@code GET /api/v1/admin/flows/{flowId}}: another tenant's flow answers 404, never 403). It targets the core seed's
 * main flow (its version snapshot carries an empty routes array, so tenant A's call answers 200 with no routes), so
 * this part seeds no rows of its own and writes nothing.
 */
final class B9FlowRoutesMatrix implements LaneMatrix {

    private static final String FLOWS = "/api/v1/admin/flows";
    private static final String ROUTES = "/routes";

    @Override
    public List<Entry> entries() {
        return List.of(
                new Entry("GET", FLOWS + "/{flowId}" + ROUTES, false,
                        s -> FLOWS + "/" + s.a().config().flowMainId() + ROUTES,
                        s -> FLOWS + "/" + s.b().config().flowMainId() + ROUTES,
                        null,
                        // the answer carries the flow id (and its version's id); the seeded main flow has no routes
                        s -> List.of(s.a().config().flowMainId().toString())),
                new Entry("PATCH", FLOWS + "/{flowId}" + ROUTES + "/{routeId}/callback", true,
                        s -> FLOWS + "/" + s.a().config().flowPublishId() + ROUTES + "/"
                                + s.a().config().pubRouteId() + "/callback",
                        s -> FLOWS + "/" + s.b().config().flowPublishId() + ROUTES + "/"
                                + s.b().config().pubRouteId() + "/callback",
                        s -> "{\"callbackMode\":null}",
                        s -> List.of(s.a().config().pubRouteId().toString())));
    }
}
