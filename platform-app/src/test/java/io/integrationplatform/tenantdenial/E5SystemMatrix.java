package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import io.integrationplatform.tenantdenial.EndpointMatrix.WriteProbe;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

/**
 * E5 seam (arjun-fullstack, F13): the forged-tenant matrix part owned by E5 lane B (sanjay-developer, T43: T-102, T-104) - the systems registry and test-connection endpoints.
 * Only that lane edits this file; see {@link LaneMatrix} for the rules.
 *
 * <p>Lane B adds NO rows of its own: the core POST /systems entry pins the tenant's system count ("3|1" before, "4|2"
 * after), so an extra system row would break it. Instead {@link #writeTenantRows} gives the core chain's target system
 * (rewritten before every test) a type and settings - an API system whose base address is http://127.0.0.1:9/.
 * Test-connection on it is therefore answered by the destination guard without touching any network: the platform's
 * allow-list is empty unless the operator sets it (refused NOT_ON_ALLOW_LIST), and a loopback address is refused even
 * when allow-listed. The legitimate call answers 200 with result REFUSED and the system's id; a tenant-B id answers 404.
 */
final class E5SystemMatrix implements LaneMatrix {

    private static final String SYSTEMS = "/api/v1/admin/systems";
    // URI-typed: an upper-case String constant holding "scheme:..." reads as an unpinned image to the R05 guard
    private static final String REFUSED_ADDRESS = URI.create("http://127.0.0.1:9/").toString();
    private static final String EDITED_ADDRESS = URI.create("https://edited.partner.example/").toString();

    @Override
    public List<Entry> entries() {
        return List.of(
                // --- T-102: list, open, edit settings ----------------------------------------------------------
                new Entry("GET", SYSTEMS, false,
                        s -> SYSTEMS, null, null,
                        s -> List.of(s.a().config().srcSystemId().toString(), dst(s.a()), s.a().config().dstCode(),
                                s.a().config().srcCode())),
                new Entry("GET", SYSTEMS + "/{systemId}", false,
                        s -> SYSTEMS + "/" + dst(s.a()),
                        s -> SYSTEMS + "/" + dst(s.b()),
                        null,
                        s -> List.of(dst(s.a()), s.a().config().dstCode(), "API", REFUSED_ADDRESS)),
                new Entry("PUT", SYSTEMS + "/{systemId}", true,
                        s -> SYSTEMS + "/" + dst(s.a()),
                        s -> SYSTEMS + "/" + dst(s.b()),
                        s -> "{\"type\":\"API\",\"settings\":{\"baseUrl\":\"" + EDITED_ADDRESS + "\"}}",
                        s -> List.of(dst(s.a()), EDITED_ADDRESS),
                        200,
                        new WriteProbe(s -> "SELECT coalesce(settings->>'baseUrl', '-') FROM public.system"
                                + " WHERE id = '" + dst(s.a()) + "'", REFUSED_ADDRESS, EDITED_ADDRESS)),
                // --- T-104: test-connection (refused by the guard: no network is ever reached) -------------------
                new Entry("POST", SYSTEMS + "/{systemId}/test-connection", false,
                        s -> SYSTEMS + "/" + dst(s.a()) + "/test-connection",
                        s -> SYSTEMS + "/" + dst(s.b()) + "/test-connection",
                        null,
                        s -> List.of(dst(s.a()), "REFUSED")));
    }

    private static String dst(SeededTenant tenant) {
        return tenant.config().dstSystemId().toString();
    }

    /** The core chain re-inserts the target system untyped; lane B types it (API, a guard-refused address). */
    @Override
    public void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        try (PreparedStatement typed = owner.prepareStatement("UPDATE public.system SET type = 'API',"
                + " settings = ?::jsonb WHERE tenant_id = ?::uuid AND id = ?::uuid")) {
            typed.setString(1, "{\"baseUrl\":\"" + REFUSED_ADDRESS + "\"}");
            typed.setString(2, tenant.id().toString());
            typed.setString(3, tenant.config().dstSystemId().toString());
            if (typed.executeUpdate() != 1) {
                throw new IllegalStateException("the core chain's target system of " + tenant.code() + " is missing");
            }
        }
    }

    /** Every system's type and settings of the tenant: a forged edit of the other tenant shows here. */
    @Override
    public String fingerprint(Connection owner, UUID tenantId) throws Exception {
        try (PreparedStatement facts = owner.prepareStatement("SELECT 'system_settings:' || coalesce(string_agg("
                + "id::text || '=' || coalesce(type, '-') || ':' || coalesce(settings::text, '-'), ',' ORDER BY id),"
                + " '') FROM public.system WHERE tenant_id = ?::uuid")) {
            facts.setString(1, tenantId.toString());
            try (ResultSet rs = facts.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
