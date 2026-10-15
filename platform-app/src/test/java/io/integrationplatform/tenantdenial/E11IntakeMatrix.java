package io.integrationplatform.tenantdenial;

import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import io.integrationplatform.tenantdenial.EndpointMatrix.WriteProbe;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Row 278 T104 (lane A, amit-developer): the forged-tenant matrix part for the common intake endpoint
 * ({@code POST /api/v1/interfaces/{interfaceCode}/messages}, T-048, IntakeController) - the coverage guard's red on
 * 699e588. The endpoint's API contract demands two headers the suite's generic send() cannot know
 * ({@code X-Source-Contract-Version} pin and {@code Idempotency-Key}, IntakeController:51-57), which the entry
 * carries through the arjun-approved {@link Entry#extraHeaders()}; a fresh nonce per request is generated inside
 * the function, so every scenario's call is a distinct acceptance, never a replay.
 *
 * <p>Unlike the read lanes, this lane must own a whole published intake chain, because {@code IntakeService.accept}
 * refuses every shortcut: the interface must be {@code PUBLISHED} with a {@code PUBLISHED} version pinned to an
 * {@code APPROVED} source contract version whose label equals the header (IntakeStore:33-60). The core chain's
 * contract version is a DRAFT (its lifecycle entries drive submit/approve/publish), so it can never satisfy the
 * pin - the lane seeds its own contract + APPROVED 1.0 + PUBLISHED interface + version, all laneId-keyed, in the
 * exact insert set the green {@code IntakeCommitContainerTest.seedPublishedOrders} uses on this tip. The
 * business-key rule is the dot-path shape ({@code orderId}, BusinessKeyRule:22-37 - NOT the admin API's
 * {@code $.orderId} JSONPath: intake would refuse it 422).
 *
 * <p>The lane registers BEFORE {@code F10QueueSummaryMatrix} in {@link EndpointMatrix#LANES}: F10's clear deletes
 * the tenant's whole {@code message} set, and the message/payload/outbox/stage-timing family has no FK between
 * members (the partitioned parents only reference {@code tenant}), so the lane's children of an intake call can
 * only be reached by id while their parent message still stands.
 *
 * <p>{@code tenantAData} is empty on purpose: the 202 answer is {@code {"trackingId": ...}} with a fresh random
 * uuid and no tenant-stable value to echo (IntakeController:67-69). The proof this entry carries is the row-state
 * probe (the caller's own four-family rows), the marker check (nothing of the other tenant can appear in the
 * answer) and the fingerprint (a cross-tenant acceptance changes the other tenant's child counts). The
 * {@code PATH_ID} forgery names tenant B's interface code and must answer 404 (IntakeController maps
 * INTERFACE_NOT_FOUND_OR_NOT_PUBLISHED to 404); the code appears in no marker because on this endpoint a human
 * never reaches the handler (SUBMIT_MESSAGE is source-only) and no source forgery enumerates path ids.
 */
final class E11IntakeMatrix implements LaneMatrix {

    private static final String INTAKE = "/api/v1/interfaces/{interfaceCode}/messages";

    /** The pinned source contract version's label - {@code X-Source-Contract-Version} must answer it exactly. */
    private static final String PINNED_LABEL = "1.0";

    /** The dot-path business-key rule the intake placeholder extractor accepts, and the payload's key value. */
    private static final String BUSINESS_KEY = "orderId";
    private static final String ORDER_ID = "tden-intake-278";

    static UUID interfaceId(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "e11-interface");
    }

    static UUID versionId(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "e11-interface-version");
    }

    static UUID contractId(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "e11-contract");
    }

    static UUID contractVersionId(SeededTenant tenant) {
        return LaneMatrix.laneId(tenant, "e11-contract-version");
    }

    private static String interfaceCode(SeededTenant tenant) {
        return "tden-in-" + tenant.config().word();
    }

    private static String contractCode(SeededTenant tenant) {
        return "e11-src-" + tenant.config().word();
    }

    @Override
    public List<Entry> entries() {
        return List.of(
                new Entry("POST", INTAKE, true,
                        s -> "/api/v1/interfaces/" + interfaceCode(s.a()) + "/messages",
                        s -> "/api/v1/interfaces/" + interfaceCode(s.b()) + "/messages",
                        s -> "{\"" + BUSINESS_KEY + "\":\"" + ORDER_ID + "\"}",
                        s -> List.of(),
                        202,
                        // the four-family acceptance write (IntakeService.accept + outbox append + INTAKE timing),
                        // scoped by the lane's business key so F10's ROUTED row never enters the count
                        new WriteProbe(s -> "SELECT (SELECT count(*) FROM public.message WHERE tenant_id = '"
                                + s.a().id() + "' AND business_key = '" + ORDER_ID + "')"
                                + " || '|' || (SELECT count(*) FROM public.payload p WHERE p.tenant_id = '"
                                + s.a().id() + "' AND p.message_id IN (SELECT id FROM public.message"
                                + " WHERE tenant_id = '" + s.a().id() + "' AND business_key = '" + ORDER_ID + "'))"
                                + " || '|' || (SELECT count(*) FROM public.outbox o WHERE o.aggregate_id IN"
                                + " (SELECT id FROM public.message WHERE tenant_id = '" + s.a().id()
                                + "' AND business_key = '" + ORDER_ID + "'))"
                                + " || '|' || (SELECT count(*) FROM public.message_stage_timing t WHERE t.tenant_id = '"
                                + s.a().id() + "' AND t.stage = 'INTAKE' AND t.message_id IN"
                                + " (SELECT id FROM public.message WHERE tenant_id = '" + s.a().id()
                                + "' AND business_key = '" + ORDER_ID + "'))",
                                "0|0|0|0", "1|1|1|1"),
                        // per request: the pinned version label (the pin check answers it) and a fresh nonce,
                        // so the legitimate call, every forgery leg and the 401 leg are all distinct acceptances
                        s -> Map.of("X-Source-Contract-Version", PINNED_LABEL,
                                "Idempotency-Key", "tden-" + UUID.randomUUID())));
    }

    @Override
    public void clearTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        // deepest first, and while F10 has not yet cleared the parent messages: the lane's message rows (the
        // legitimate acceptance's whole family, the id is random so only the business key names them) and then
        // the lane's config rows - the cv only after its interface_version stops referencing it (composite FK).
        update(owner, "DELETE FROM public.payload WHERE tenant_id = ?::uuid AND message_id IN"
                        + " (SELECT id FROM public.message WHERE tenant_id = ?::uuid AND business_key = ?)",
                tenant.id().toString(), tenant.id().toString(), ORDER_ID);
        update(owner, "DELETE FROM public.message_stage_timing WHERE tenant_id = ?::uuid AND stage = 'INTAKE'"
                        + " AND message_id IN (SELECT id FROM public.message WHERE tenant_id = ?::uuid"
                        + " AND business_key = ?)",
                tenant.id().toString(), tenant.id().toString(), ORDER_ID);
        // outbox carries its own tenant_id (V2_0_1__outbox.sql:11), so this delete follows the payload/stage
        // double-scope: outer tenant_id = ?::uuid, subquery tenant_id = ?::uuid - three binds, three values.
        update(owner, "DELETE FROM public.outbox WHERE tenant_id = ?::uuid AND aggregate_id IN"
                        + " (SELECT id FROM public.message WHERE tenant_id = ?::uuid AND business_key = ?)",
                tenant.id().toString(), tenant.id().toString(), ORDER_ID);
        update(owner, "DELETE FROM public.message WHERE tenant_id = ?::uuid AND business_key = ?",
                tenant.id().toString(), ORDER_ID);
        update(owner, "DELETE FROM public.interface_version WHERE id = ?::uuid",
                versionId(tenant).toString());
        update(owner, "DELETE FROM public.interface WHERE id = ?::uuid", interfaceId(tenant).toString());
        update(owner, "DELETE FROM public.contract_version WHERE contract_id = ?::uuid",
                contractId(tenant).toString());
        update(owner, "DELETE FROM public.contract WHERE id = ?::uuid", contractId(tenant).toString());
    }

    @Override
    public void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
        // IntakeCommitContainerTest.seedPublishedOrders' exact insert set (green on this tip), laneId-keyed and
        // tenant-unique. The contract hangs on the core chain's SOURCE system; the pin is this tenant's own
        // APPROVED 1.0 (the core's DRAFT can never satisfy IntakeStore.pinnedLabel's status check).
        update(owner, "INSERT INTO public.contract (id, tenant_id, system_id, code, role, name)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'SOURCE_REQUEST', ?)",
                contractId(tenant).toString(), tenant.id().toString(),
                tenant.config().srcSystemId().toString(), contractCode(tenant), contractCode(tenant) + " intake");
        update(owner, "INSERT INTO public.contract_version (id, tenant_id, contract_id, version_label, status,"
                        + " json_schema_draft_2020_12, author_id)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'APPROVED', '{}'::jsonb, ?::uuid)",
                contractVersionId(tenant).toString(), tenant.id().toString(), contractId(tenant).toString(),
                PINNED_LABEL, tenant.userId().toString());
        update(owner, "INSERT INTO public.interface (id, tenant_id, code, name, mode, ordering, current_version,"
                        + " published_version, status) VALUES (?::uuid, ?::uuid, ?, ?, 'ASYNC', 'PER_KEY', 1, 1,"
                        + " 'PUBLISHED')",
                interfaceId(tenant).toString(), tenant.id().toString(), interfaceCode(tenant),
                interfaceCode(tenant) + " intake");
        update(owner, "INSERT INTO public.interface_version (id, tenant_id, interface_id, version, status,"
                        + " business_key_rule, snapshot, source_contract_version_id, author_id, approver_id)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, 1, 'PUBLISHED', ?, '{}'::jsonb, ?::uuid, ?::uuid,"
                        + " ?::uuid)",
                versionId(tenant).toString(), tenant.id().toString(), interfaceId(tenant).toString(),
                BUSINESS_KEY, contractVersionId(tenant).toString(), tenant.userId().toString(),
                tenant.userId().toString());
    }

    @Override
    public List<String> markers(SeededTenant tenant) {
        return List.of(interfaceCode(tenant), contractCode(tenant), interfaceId(tenant).toString(),
                versionId(tenant).toString(), contractId(tenant).toString(),
                contractVersionId(tenant).toString());
    }

    @Override
    public String fingerprint(Connection owner, UUID tenantId) throws Exception {
        // the acceptance family beyond the message row itself (F10's fingerprint counts messages only): an
        // intake that crossed the tenant boundary shows here even if its parent row were missed; the counts run
        // whole-tenant so an orphaned child (a bug, never a legal state) still changes the digest
        try (PreparedStatement facts = owner.prepareStatement("SELECT"
                + " (SELECT 'payload:' || count(*) || '@'"
                + " || coalesce(string_agg(message_id::text, ',' ORDER BY message_id), '-')"
                + "   FROM public.payload WHERE tenant_id = ?::uuid)"
                + " || ';' || (SELECT 'stage:' || count(*) FROM public.message_stage_timing"
                + "   WHERE tenant_id = ?::uuid AND stage = 'INTAKE')")) {
            facts.setString(1, tenantId.toString());
            facts.setString(2, tenantId.toString());
            try (ResultSet rs = facts.executeQuery()) {
                rs.next();
                return "e11-intake:" + rs.getString(1);
            }
        }
    }

    private static void update(Connection owner, String sql, String... values) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int position = 0; position < values.length; position++) {
                statement.setString(position + 1, values[position]);
            }
            statement.executeUpdate();
        }
    }
}
