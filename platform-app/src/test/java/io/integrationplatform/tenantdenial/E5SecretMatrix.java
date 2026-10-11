package io.integrationplatform.tenantdenial;

import io.integrationplatform.config.credential.crypto.EnvelopeSealer;
import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import io.integrationplatform.tenantdenial.EndpointMatrix.WriteProbe;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;

/**
 * E5 seam (arjun-fullstack, F13): the forged-tenant matrix part owned by E5 lane C (rohit-developer, T44: T-103, T-185) - the issue, rotate and revoke endpoints and the target secrets.
 * Only that lane edits this file; see {@link LaneMatrix} for the rules.
 *
 * <p>The suite's context has neither the identity-provider admin secret nor a key-encryption key (its wiring is
 * frozen), so the legitimate issue, rotate and target-secret save answer 503 there - AFTER the tenant check, so a
 * tenant-B id is still 404 and nothing is written. What this matrix proves is tenant isolation; the issue, rotate and
 * save paths themselves are proven by platform-config's e5.credential suites and platform-app's e5.credential suites.
 * Revoke works without the admin client (platform first; the client disable waits as {@code idp_disable_pending}).
 *
 * <p>Lane rows, rewritten before every test:
 * <ul>
 *   <li>a source credential of the tenant's machine system (seeded once, never reset - source_credential references
 *       it): fixed id {@link LaneMatrix#laneId}, upserted back to ACTIVE (DELETE is denied by design, so it is never
 *       deleted); the seeded machine credential itself is never touched, so the source callers keep working;</li>
 *   <li>one target credential of the core chain's target endpoint, sealed with a key generated in memory per run
 *       (deleted first: it references the endpoint the core chain rewrites).</li>
 * </ul>
 */
final class E5SecretMatrix implements LaneMatrix {

    private static final String SYSTEMS = "/api/v1/admin/systems";
    private static final String SOURCE_CREDENTIALS = "/api/v1/admin/source-credentials";
    private static final String TARGET_ENDPOINTS = "/api/v1/admin/target-endpoints";
    private static final String LANE_ISSUER = "https://issuer.e5-lane-c.invalid/realms/platform";
    private static final byte[] LANE_KEY = laneKey();

    @Override
    public List<Entry> entries() {
        return List.of(
                // --- T-103 source credentials -----------------------------------------------------------------
                new Entry("POST", SYSTEMS + "/{systemId}/source-credentials", true,
                        s -> SYSTEMS + "/" + s.a().machineSystemId() + "/source-credentials",
                        s -> SYSTEMS + "/" + s.b().machineSystemId() + "/source-credentials",
                        s -> "{\"customerId\":\"" + s.a().customerId() + "\"}",
                        s -> List.of(),
                        503,
                        new WriteProbe(s -> "SELECT count(*)::text FROM public.source_credential WHERE source_system_id"
                                + " = '" + s.a().machineSystemId() + "'", "2", "2")),
                new Entry("GET", SYSTEMS + "/{systemId}/source-credentials", false,
                        s -> SYSTEMS + "/" + s.a().machineSystemId() + "/source-credentials",
                        s -> SYSTEMS + "/" + s.b().machineSystemId() + "/source-credentials",
                        null,
                        s -> List.of(sourceCredential(s.a()).toString(), laneClient(s.a()), "********")),
                new Entry("POST", SOURCE_CREDENTIALS + "/{credentialId}/rotate", false,
                        s -> SOURCE_CREDENTIALS + "/" + sourceCredential(s.a()) + "/rotate",
                        s -> SOURCE_CREDENTIALS + "/" + sourceCredential(s.b()) + "/rotate",
                        null,
                        s -> List.of(),
                        503,
                        new WriteProbe(s -> sourceState(s.a()), "ACTIVE|-", "ACTIVE|-")),
                new Entry("POST", SOURCE_CREDENTIALS + "/{credentialId}/revoke", false,
                        s -> SOURCE_CREDENTIALS + "/" + sourceCredential(s.a()) + "/revoke",
                        s -> SOURCE_CREDENTIALS + "/" + sourceCredential(s.b()) + "/revoke",
                        null,
                        s -> List.of(sourceCredential(s.a()).toString(), "REVOKED"),
                        200,
                        new WriteProbe(s -> sourceState(s.a()), "ACTIVE|-", "REVOKED|-")),
                // --- T-103 target credentials -----------------------------------------------------------------
                new Entry("GET", TARGET_ENDPOINTS + "/{endpointId}/credentials", false,
                        s -> TARGET_ENDPOINTS + "/" + s.a().config().dstEndpointId() + "/credentials",
                        s -> TARGET_ENDPOINTS + "/" + s.b().config().dstEndpointId() + "/credentials",
                        null,
                        s -> List.of(targetCredential(s.a()).toString(), "basicPassword", "********")),
                new Entry("PUT", TARGET_ENDPOINTS + "/{endpointId}/credentials", true,
                        s -> TARGET_ENDPOINTS + "/" + s.a().config().dstEndpointId() + "/credentials",
                        s -> TARGET_ENDPOINTS + "/" + s.b().config().dstEndpointId() + "/credentials",
                        s -> "{\"fields\":{\"apiKey\":\"matrix-" + s.a().code() + "\"}}",
                        s -> List.of(),
                        503,
                        new WriteProbe(s -> "SELECT count(*)::text FROM public.target_credential WHERE"
                                + " target_endpoint_id = '" + s.a().config().dstEndpointId() + "'", "1", "1")));
    }

    private static UUID sourceCredential(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "e5-source-credential");
    }

    private static UUID targetCredential(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "e5-target-credential");
    }

    private static String laneClient(SeededTenant tenant) {
        return "e5-lane-" + tenant.code();
    }

    private static String sourceState(SeededTenant tenant) {
        return "SELECT status || '|' || coalesce(revoke_after::text, '-') FROM public.source_credential WHERE id = '"
                + sourceCredential(tenant) + "'";
    }

    /** The target credential references the endpoint the core chain is about to delete and re-insert. */
    @Override
    public void clearTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        try (PreparedStatement delete = owner.prepareStatement(
                "DELETE FROM public.target_credential WHERE tenant_id = ?::uuid")) {
            delete.setString(1, tenant.id().toString());
            delete.executeUpdate();
        }
    }

    @Override
    public void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        try (PreparedStatement source = owner.prepareStatement("INSERT INTO public.source_credential (id, tenant_id,"
                + " source_system_id, customer_id, issuer, client_id, status) VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?,"
                + " ?, 'ACTIVE') ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE', revoked_at = NULL,"
                + " idp_disable_pending = false, revoke_after = NULL")) {
            source.setString(1, sourceCredential(tenant).toString());
            source.setString(2, tenant.id().toString());
            source.setString(3, tenant.machineSystemId().toString());
            source.setString(4, tenant.customerId());
            source.setString(5, LANE_ISSUER);
            source.setString(6, laneClient(tenant));
            source.executeUpdate();
        }
        UUID id = targetCredential(tenant);
        UUID endpoint = tenant.config().dstEndpointId();
        EnvelopeSealer.Sealed sealed = EnvelopeSealer.seal(("lane-secret-" + UUID.randomUUID()).toCharArray(),
                tenant.id(), id, endpoint, "BASIC_PASSWORD", "LANE1", new SecretKeySpec(LANE_KEY, "AES"));
        try (PreparedStatement target = owner.prepareStatement("INSERT INTO public.target_credential (id, tenant_id,"
                + " target_endpoint_id, target_type, field, kek_id, wrapped_key, ciphertext) VALUES (?::uuid, ?::uuid,"
                + " ?::uuid, 'API', 'BASIC_PASSWORD', 'LANE1', ?, ?)")) {
            target.setString(1, id.toString());
            target.setString(2, tenant.id().toString());
            target.setString(3, endpoint.toString());
            target.setBytes(4, sealed.wrappedKey());
            target.setBytes(5, sealed.ciphertext());
            target.executeUpdate();
        }
    }

    @Override
    public List<String> markers(SeededTenant tenant) {
        return List.of(sourceCredential(tenant).toString(), laneClient(tenant), targetCredential(tenant).toString());
    }

    /** The tenant's credentials: a forged issue, rotate, revoke or save on the other tenant shows here. */
    @Override
    public String fingerprint(Connection owner, UUID tenantId) throws Exception {
        try (PreparedStatement facts = owner.prepareStatement("SELECT 'source_credential:' || coalesce((SELECT"
                + " string_agg(id::text || '=' || status || ':' || idp_disable_pending || ':'"
                + " || coalesce(revoke_after::text, '-'), ',' ORDER BY id) FROM public.source_credential"
                + " WHERE tenant_id = ?::uuid), '') || '|target_credential:' || coalesce((SELECT string_agg(id::text"
                + " || '=' || field || ':' || md5(ciphertext), ',' ORDER BY id) FROM public.target_credential"
                + " WHERE tenant_id = ?::uuid), '')")) {
            facts.setString(1, tenantId.toString());
            facts.setString(2, tenantId.toString());
            try (ResultSet rs = facts.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static byte[] laneKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
