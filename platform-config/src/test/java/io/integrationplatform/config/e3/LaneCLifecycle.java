package io.integrationplatform.config.e3;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e3.ConfigAdminApi.Response;
import io.integrationplatform.config.e3.E3ConfigDatabase.PendingIntegration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lane C's shared scenario helpers (T-015, T-019, T-021): the draft flow with two destinations, the full
 * lifecycle walk driven over the real HTTP API, the owner-side reads the byte-for-byte proof compares, and the
 * deliberate drift of the live rows. Bearer tokens always come from the suite's one {@link LaneCIdentities}
 * key pair - the same key its decoder verifies - never from a second signing key.
 *
 * <p>Package-private test code; the lanes' poms are the seam, so this is lane C's own copy, not an import of
 * lane A's classes. It throws the fixture's named {@link PendingIntegration} at every 404: the committed RED of
 * the red-first lane, per endpoint, with its owning task.
 */
final class LaneCLifecycle {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private LaneCLifecycle() {
    }

    /** The suite's three identities for one test: the author, the tenant-A approver, another tenant's user. */
    record Actors(String author, String approver, String otherTenantUser) {

        static Actors of(LaneCIdentities tokens, String authorSubject, String approverSubject,
                String otherTenantSubject) {
            return new Actors(tokens.accessToken(authorSubject), tokens.accessToken(approverSubject),
                    tokens.accessToken(otherTenantSubject));
        }
    }

    /** A flow body lane A's helper cannot build: the ordering is a parameter, not a fixed PER_KEY. */
    static String flowBody(String sourceSystem, List<String> destinations, String businessKeyRule,
            String orderingOrNull) {
        ObjectNode json = JSON.createObjectNode();
        json.put("sourceSystem", sourceSystem);
        ArrayNode array = json.putArray("destinations");
        destinations.forEach(array::add);
        if (businessKeyRule != null) {
            json.put("businessKeyRule", businessKeyRule);
        }
        if (orderingOrNull != null) {
            json.put("ordering", orderingOrNull);
        }
        return JSON.writeValueAsString(json);
    }

    /** A draft flow of one source and two destinations: its id plus the system codes the draft-save reuses. */
    record Draft(UUID flowId, String sourceSystem, String destination1, String destination2) {

        List<String> destinations() {
            return List.of(destination1, destination2);
        }
    }

    /**
     * Creates the source system, two destination systems and the draft flow naming them (rows through the real
     * API). Every missing endpoint is gated by name with its owning task.
     */
    static Draft createDraftFlowWithTwoDestinations(String base, Actors actors) {
        String sourceSystem = LaneCIdentities.randomCode("src");
        String destination1 = LaneCIdentities.randomCode("tgt");
        String destination2 = LaneCIdentities.randomCode("tgt");
        createSystem(base, actors.author(), sourceSystem, "INBOUND");
        createSystem(base, actors.author(), destination1, "OUTBOUND");
        createSystem(base, actors.author(), destination2, "OUTBOUND");
        return new Draft(createFlow(base, actors.author(), sourceSystem,
                List.of(destination1, destination2), "$.orderId"), sourceSystem, destination1, destination2);
    }

    static UUID createSystem(String base, String bearer, String code, String direction) {
        Response created = ConfigAdminApi.call("POST", base + ConfigAdminApi.SYSTEMS, bearer,
                ConfigAdminApi.createSystem(code, "system " + code, direction));
        if (created.status() == 404) {
            throw new PendingIntegration("the systems admin API answered 404", "lane C (T-015)");
        }
        assertThat(created.status()).as("the system %s is created as a row, body: %s", code, created.body())
                .isEqualTo(201);
        return UUID.fromString(created.json().path("id").asString());
    }

    static UUID createFlow(String base, String bearer, String sourceSystem, List<String> destinations,
            String businessKeyRule) {
        Response created = ConfigAdminApi.call("POST", base + ConfigAdminApi.FLOWS, bearer,
                flowBody(sourceSystem, destinations, businessKeyRule, null));
        if (created.status() == 404) {
            throw new PendingIntegration("the flow admin API answered 404", "lane C (T-015)");
        }
        assertThat(created.status()).as("the draft flow is created, body: %s", created.body()).isEqualTo(201);
        return UUID.fromString(created.json().path("id").asString());
    }

    /** The draft-save that adds destinations to an existing flow: one new version, the live rows updated. */
    static void saveFlowDraft(String base, String bearer, UUID flowId, String sourceSystem,
            List<String> destinations) {
        Response saved = ConfigAdminApi.call("PUT", base + ConfigAdminApi.FLOWS + "/" + flowId, bearer,
                flowBody(sourceSystem, destinations, "$.orderId", null));
        if (saved.status() == 404) {
            throw new PendingIntegration("the flow draft-save API (PUT /flows/{id}) answered 404", "lane C (T-019)");
        }
        assertThat(saved.status()).as("the new draft version is saved, body: %s", saved.body()).isEqualTo(200);
    }

    static void submit(String base, Actors actors, UUID flowId, String changeNote) {
        Response submitted = ConfigAdminApi.call("POST",
                base + ConfigAdminApi.FLOWS + "/" + flowId + "/submit", actors.author(),
                ConfigAdminApi.submit(changeNote));
        if (submitted.status() == 404) {
            throw new PendingIntegration("the lifecycle API (submit) answered 404", "lane C (T-019)");
        }
        assertThat(submitted.status()).as("the draft version is submitted, body: %s", submitted.body())
                .isEqualTo(200);
    }

    static Response approve(String base, String bearer, UUID flowId) {
        Response approved = ConfigAdminApi.call("POST",
                base + ConfigAdminApi.FLOWS + "/" + flowId + "/approve", bearer, "{}");
        if (approved.status() == 404) {
            throw new PendingIntegration("the lifecycle API (approve) answered 404", "lane C (T-019)");
        }
        return approved;
    }

    /** The whole walk through the real API: submit, approve by the other user, publish. */
    static void submitApprovePublish(String base, Actors actors, UUID flowId, String changeNote) {
        submit(base, actors, flowId, changeNote);
        Response approved = approve(base, actors.approver(), flowId);
        assertThat(approved.status()).as("another user of the tenant approves, body: %s", approved.body())
                .isEqualTo(200);
        publish(base, actors.approver(), flowId);
    }

    static void publish(String base, String bearer, UUID flowId) {
        Response published = ConfigAdminApi.call("POST",
                base + ConfigAdminApi.FLOWS + "/" + flowId + "/publish", bearer, "{}");
        if (published.status() == 404) {
            throw new PendingIntegration("the lifecycle API (publish) answered 404", "lane C (T-019/T-021)");
        }
        assertThat(published.status()).as("the approved version publishes, body: %s", published.body())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ owner-side reads (the proofs observe, never fake)

    static UUID latestVersionId(UUID flowId) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select id::text from public.interface_version where interface_id = ?::uuid "
                                + "order by version desc limit 1")) {
            read.setString(1, flowId.toString());
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next()).as("the flow has a version row").isTrue();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    static String snapshotText(UUID versionId) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select snapshot::text from public.interface_version where id = ?::uuid")) {
            read.setString(1, versionId.toString());
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next()).as("the version row exists").isTrue();
                return rs.getString(1);
            }
        }
    }

    /**
     * Stamps a raw application-login connection with tenant A. The composed system's sessions are always stamped
     * (the stamping transaction manager); an un-stamped one is refused by the RLS error-deny (42704) before the
     * grant/CHECK layers under test can answer - the scenario names those layers, so the fixture stamps first.
     */
    static void stampTenant(Connection app) throws SQLException {
        try (Statement stamp = app.createStatement()) {
            stamp.execute("select set_config('app.tenant_id', '" + E3ConfigDatabase.tenantA().id() + "', false)");
        }
    }

    /** Deliberately changes the live configuration rows (the byte-for-byte and rollback premises). */
    static void tamperLiveMapping(UUID routeId, String value) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement update = owner.prepareStatement(
                        "update public.mapping set compiled_expression = ? where route_id = ?::uuid")) {
            update.setString(1, value);
            update.setString(2, routeId.toString());
            assertThat(update.executeUpdate()).as("the owner connection drifts the live mapping").isEqualTo(1);
        }
    }

    static void deleteLiveMappingAndRoute(UUID routeId) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection()) {
            try (PreparedStatement mapping = owner.prepareStatement(
                    "delete from public.mapping where route_id = ?::uuid")) {
                mapping.setString(1, routeId.toString());
                assertThat(mapping.executeUpdate()).as("the mid-bundle state: the mapping row is gone")
                        .isEqualTo(1);
            }
            try (PreparedStatement route = owner.prepareStatement(
                    "delete from public.route where id = ?::uuid")) {
                route.setString(1, routeId.toString());
                assertThat(route.executeUpdate()).as("the mid-bundle state: the route row is gone").isEqualTo(1);
            }
        }
    }

    static UUID routeIdOfLiveRow(UUID flowId, int sequence) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select id::text from public.route where interface_id = ?::uuid and sequence = ?")) {
            read.setString(1, flowId.toString());
            read.setInt(2, sequence);
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next()).as("the route at sequence %d exists", sequence).isTrue();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    /** T-190: how many live routes the flow carries (the bind helper walks them). */
    static int routeCount(UUID flowId) {
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select count(*) from public.route where interface_id = ?::uuid")) {
            read.setString(1, flowId.toString());
            try (ResultSet rs = read.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the route count read failed", e);
        }
    }

    /**
     * T-190: the approved TARGET_REQUEST contract version a mapping pin binds (one stable row pair per system;
     * the publish pin check requires APPROVED + TARGET_REQUEST on the route's system - ADR-011 rule 6).
     */
    static String seedApprovedTargetVersion(UUID tenantId, String systemId) {
        UUID contractId = UUID.nameUUIDFromBytes(
                ("mapping-target-contract|" + systemId).getBytes(StandardCharsets.UTF_8));
        UUID versionId = UUID.nameUUIDFromBytes(
                ("mapping-target-version|" + systemId).getBytes(StandardCharsets.UTF_8));
        String schema = "{\"type\":\"object\",\"properties\":{\"customer_code\":{\"type\":\"string\"},"
                + "\"order_number\":{\"type\":\"string\"},\"total_amount\":{\"type\":\"number\"}}}";
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement contract = owner.prepareStatement(
                        "insert into public.contract (id, tenant_id, system_id, code, role, name)"
                        + " values (?::uuid, ?::uuid, ?::uuid, ?, 'TARGET_REQUEST', ?)"
                        + " on conflict (tenant_id, id) do nothing");
                PreparedStatement version = owner.prepareStatement(
                        "insert into public.contract_version (id, tenant_id, contract_id, version_label, status,"
                        + " json_schema_draft_2020_12, author_id)"
                        + " values (?::uuid, ?::uuid, ?::uuid, '1.0', 'APPROVED', ?::jsonb, ?::uuid)"
                        + " on conflict (tenant_id, id) do nothing")) {
            contract.setString(1, contractId.toString());
            contract.setString(2, tenantId.toString());
            contract.setString(3, systemId);
            contract.setString(4, "map-target-" + contractId.toString().substring(0, 8));
            contract.setString(5, "mapping pin target contract");
            contract.executeUpdate();
            version.setString(1, versionId.toString());
            version.setString(2, tenantId.toString());
            version.setString(3, contractId.toString());
            version.setString(4, schema);
            version.setString(5, tenantId.toString());
            version.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("the approved target version seed failed", e);
        }
        return versionId.toString();
    }

    /** T-190: the source contract version the flow's latest version pins (the binding must match it). */
    static String sourceContractVersionIdOfLatestVersion(UUID flowId) {
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select v.source_contract_version_id::text from public.interface_version v"
                        + " where v.interface_id = ?::uuid order by v.version desc limit 1")) {
            read.setString(1, flowId.toString());
            try (ResultSet rs = read.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the source version read failed", e);
        }
    }

    /** T-190: binds one route's mapping - the flow's pinned source version and an approved target version. */
    static void bindRouteMapping(String base, String bearer, UUID tenantId, UUID flowId, UUID routeId,
            String compiledExpression) {
        String sourceVersion = sourceContractVersionIdOfLatestVersion(flowId);
        String systemId;
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select te.system_id::text from public.route r"
                        + " join public.target_endpoint te on te.id = r.target_endpoint_id"
                        + " where r.id = ?::uuid")) {
            read.setString(1, routeId.toString());
            try (ResultSet rs = read.executeQuery()) {
                rs.next();
                systemId = rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the endpoint system read failed", e);
        }
        String targetVersion = seedApprovedTargetVersion(tenantId, systemId);
        Response bound = ConfigAdminApi.call("PUT",
                base + "/api/v1/admin/routes/" + routeId + "/mapping", bearer,
                "{\"fieldMappings\":[],\"compiledExpression\":\"" + compiledExpression + "\","
                        + "\"sourceContractVersionId\":\"" + sourceVersion + "\","
                        + "\"targetContractVersionId\":\"" + targetVersion + "\"}");
        if (bound.status() == 404) {
            throw new PendingIntegration("the mapping save API answered 404", "lane C (T-190)");
        }
        assertThat(bound.status()).as("the mapping binds to contract versions, body: %s", bound.body())
                .isEqualTo(200);
    }

    /** T-190: binds every route's mapping ('$' content) and re-captures the bundle as a new draft version. */
    static void bindAllRoutesAndResnapshot(String base, String bearer, UUID tenantId, UUID flowId) {
        bindAllRoutesAndResnapshot(base, bearer, tenantId, flowId, "$");
    }

    static void bindAllRoutesAndResnapshot(String base, String bearer, UUID tenantId, UUID flowId,
            String compiledExpression) {
        for (int sequence = 1; sequence <= routeCount(flowId); sequence++) {
            try {
                bindRouteMapping(base, bearer, tenantId, flowId, routeIdOfLiveRow(flowId, sequence),
                        compiledExpression);
            } catch (SQLException e) {
                throw new IllegalStateException("the mapping bind fixture failed", e);
            }
        }
        JsonNode flow = ConfigAdminApi.call("GET", base + ConfigAdminApi.FLOWS + "/" + flowId, bearer, null)
                .json();
        List<String> destinations = new ArrayList<>();
        flow.path("destinations").forEach(destination -> destinations.add(destination.asString()));
        saveFlowDraft(base, bearer, flowId, flow.path("sourceSystem").asString(), destinations);
    }

    static int countRows(String table, UUID flowId) throws SQLException {
        String sql = "route".equals(table)
                ? "select count(*) from public.route where interface_id = ?::uuid"
                : "select count(*) from public.mapping where route_id in "
                        + "(select id from public.route where interface_id = ?::uuid)";
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(sql)) {
            read.setString(1, flowId.toString());
            try (ResultSet rs = read.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** The interface row's live state, read by the owner (the rollback proof observes these). */
    static JsonNode interfaceRow(UUID flowId) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select jsonb_build_object('published_version', published_version::text, "
                                + "'current_version', current_version::text, 'status', status, "
                                + "'ordering', ordering)::text from public.interface where id = ?::uuid")) {
            read.setString(1, flowId.toString());
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next()).as("the interface row exists").isTrue();
                return JSON.readTree(rs.getString(1));
            }
        }
    }

    static String versionStatus(UUID flowId, int version) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select status from public.interface_version where interface_id = ?::uuid "
                                + "and version = ?")) {
            read.setString(1, flowId.toString());
            read.setInt(2, version);
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next()).as("version %d of the flow exists", version).isTrue();
                return rs.getString(1);
            }
        }
    }

    /**
     * The bundle the live rows carry, in the canonical shape the snapshot holds: route entries (id, sequence,
     * condition, isPrimary, status, targetEndpointId) and mapping entries (id, routeId, fieldMappings,
     * compiledExpression), each ordered by sequence then id.
     */
    static ObjectNode bundleOfLiveRows(UUID flowId) throws SQLException {
        ObjectNode bundle = JSON.createObjectNode();
        ArrayNode routes = bundle.putArray("routes");
        ArrayNode mappings = bundle.putArray("mappings");
        try (Connection owner = E3ConfigDatabase.ownerConnection()) {
            try (PreparedStatement route = owner.prepareStatement(
                    "select r.id::text, r.sequence, r.condition, r.is_primary, r.status, "
                            + "r.target_endpoint_id::text from public.route r where r.interface_id = ?::uuid "
                            + "order by r.sequence, r.id")) {
                route.setString(1, flowId.toString());
                try (ResultSet rs = route.executeQuery()) {
                    while (rs.next()) {
                        ObjectNode entry = JSON.createObjectNode();
                        entry.put("id", rs.getString(1));
                        entry.put("sequence", rs.getInt(2));
                        if (rs.getString(3) == null) {
                            entry.putNull("condition");
                        } else {
                            entry.put("condition", rs.getString(3));
                        }
                        entry.put("isPrimary", rs.getBoolean(4));
                        entry.put("status", rs.getString(5));
                        entry.put("targetEndpointId", rs.getString(6));
                        routes.add(entry);
                    }
                }
            }
            try (PreparedStatement mapping = owner.prepareStatement(
                    "select m.id::text, m.route_id::text, m.field_mappings::text, m.compiled_expression, "
                            + "m.source_contract_version_id::text, m.target_contract_version_id::text, "
                            + "(select v.id::text from public.mapping_version v"
                            + "  where v.tenant_id = m.tenant_id and v.mapping_id = m.id"
                            + "  and v.source_contract_version_id is not distinct from"
                            + "      m.source_contract_version_id"
                            + "  and v.target_contract_version_id is not distinct from"
                            + "      m.target_contract_version_id"
                            + "  and v.field_mappings = m.field_mappings"
                            + "  and v.compiled_expression = m.compiled_expression limit 1) "
                            + "from public.mapping m join public.route r on r.id = m.route_id "
                            + "where r.interface_id = ?::uuid order by r.sequence, m.id")) {
                mapping.setString(1, flowId.toString());
                try (ResultSet rs = mapping.executeQuery()) {
                    while (rs.next()) {
                        ObjectNode entry = JSON.createObjectNode();
                        entry.put("id", rs.getString(1));
                        entry.put("routeId", rs.getString(2));
                        entry.set("fieldMappings",
                                rs.getString(3) == null
                                        ? JsonNodeFactory.instance.nullNode()
                                        : JSON.readTree(rs.getString(3)));
                        entry.put("compiledExpression", rs.getString(4));
                        entry.put("sourceContractVersionId", rs.getString(5));
                        entry.put("targetContractVersionId", rs.getString(6));
                        entry.put("mappingVersionId", rs.getString(7));
                        mappings.add(entry);
                    }
                }
            }
        }
        return bundle;
    }

    /** The snapshot's own {routes, mappings} subtree, rematerialized for the comparison. */
    static ObjectNode bundleOfSnapshot(String snapshotText) {
        JsonNode snapshot = JSON.readTree(snapshotText);
        ObjectNode bundle = JSON.createObjectNode();
        bundle.set("routes", snapshot.path("routes"));
        bundle.set("mappings", snapshot.path("mappings"));
        return bundle;
    }
}
