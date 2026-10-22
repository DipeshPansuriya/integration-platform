package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import java.util.List;

/**
 * Forged-tenant matrix part for the report facade read ({@code GET /api/v1/reports/facade/{trackingId}},
 * ReportFacadeController, READ_MESSAGE_TIMELINE in ReportFacadeEndpointRoles).
 *
 * <p>It reads the same tenant-owned message {@link E13TimelineMatrix} seeds for each tenant (a ROUTE_FAILED message with
 * an INTAKE stage row), so tenant A's call must answer A's tracking id and no forgery may surface B's. The PATH_ID
 * forgery names tenant B's tracking id: 404 (the facade answers a missing timeline as 404) with one recorded attempt.
 */
final class E13ReportFacadeMatrix implements LaneMatrix {

    private static final String FACADE = "/api/v1/reports/facade";

    @Override
    public List<Entry> entries() {
        return List.of(new Entry("GET", FACADE + "/{trackingId}", false,
                s -> FACADE + "/" + E13TimelineMatrix.messageId(s.a()),
                s -> FACADE + "/" + E13TimelineMatrix.messageId(s.b()),
                null,
                s -> List.of(E13TimelineMatrix.messageId(s.a()).toString())));
    }
}
