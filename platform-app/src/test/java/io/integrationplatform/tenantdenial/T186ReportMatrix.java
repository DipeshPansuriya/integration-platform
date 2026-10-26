package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * T186 (stale-test fix, arjun-fullstack): the forged-tenant matrix part for the two report reads, both
 * READ_MESSAGE_TIMELINE (tenant admin, support, read-only; never the agent or a source):
 * {@code GET /api/v1/reports/delivery?day=} (DeliveryReportController: the tenant comes from the bound context and
 * FORCE RLS on the daily aggregate) and {@code GET /api/v1/reports/facade/{trackingId}} (ReportFacadeController: the
 * tracking id is the E13 timeline message of {@link E13TimelineMatrix}, whose rows this part reuses).
 *
 * <p>Each tenant gets one daily-aggregate row on its main flow, so the delivery report is non-empty: tenant A's call
 * answers A's flow id and no forgery may surface B's. The facade's PATH_ID forgery names B's tracking id and must
 * answer 404 with one recorded denied attempt, like the timeline endpoint it fronts.
 */
final class T186ReportMatrix implements LaneMatrix {

    private static final String DELIVERY = "/api/v1/reports/delivery";
    private static final String FACADE = "/api/v1/reports/facade";
    private static final String DAY = dayFor(Clock.systemUTC());

    /**
     * Yesterday (UTC): inside DeliveryReportController's window (not after today, not older than
     * platform.delivery.report.max-age-days) on any run date, and safe if midnight passes between seed and request.
     */
    static String dayFor(Clock clock) {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1).toString();
    }

    @Override
    public List<Entry> entries() {
        return List.of(
                new Entry("GET", DELIVERY, false, s -> DELIVERY + "?day=" + DAY, null, null,
                        s -> List.of(s.a().config().flowMainId().toString())),
                new Entry("GET", FACADE + "/{trackingId}", false,
                        s -> FACADE + "/" + timelineMessageId(s.a()),
                        s -> FACADE + "/" + timelineMessageId(s.b()),
                        null,
                        s -> List.of(timelineMessageId(s.a()).toString())));
    }

    @Override
    public void clearTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement(
                "DELETE FROM public.delivery_daily_aggregate WHERE tenant_id = ?::uuid")) {
            statement.setString(1, tenant.id().toString());
            statement.executeUpdate();
        }
    }

    @Override
    public void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement("INSERT INTO public.delivery_daily_aggregate"
                + " (tenant_id, day, interface_id, target_id, successes)"
                + " VALUES (?::uuid, ?::date, ?::uuid, ?::uuid, 3)")) {
            statement.setString(1, tenant.id().toString());
            statement.setString(2, DAY);
            statement.setString(3, tenant.config().flowMainId().toString());
            statement.setString(4, LaneMatrix.laneId(tenant, "t186-report-target").toString());
            statement.executeUpdate();
        }
    }

    @Override
    public List<String> markers(SeededTenant tenant) {
        return List.of(LaneMatrix.laneId(tenant, "t186-report-target").toString());
    }

    /** The E13 timeline lane's message: the facade's tracking id (same lane-id name, written by that lane). */
    private static UUID timelineMessageId(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "e13-timeline-message");
    }
}
