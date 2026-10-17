package io.integrationplatform.e11;

import io.integrationplatform.config.flow.application.FlowBundle;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.MappingRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.RouteRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

/**
 * Dipesh's example as onboarding rows (backlog.md:3032-3046, E11-F2-S1; E11-plan lane C1): one tenant's source
 * system and credential, the source contract {@code orders} v1.0, Target A with contract v1.0
 * {@code {customer_code, order_number, total_amount}} and Target B with contract v2.0
 * {@code {clientId, referenceNo, invoiceValue}}, the published interface {@code orders} whose two routes carry one
 * JSONata mapping each, pinned by a {@code mapping_version} - inserted as the migration owner while the application
 * is already running, so onboarding is rows only (0 deploys, 0 migrations, 0 restarts).
 *
 * <p>The published version's snapshot is the canonical {@link FlowBundle} the publish lays (the same serializer the
 * console's publish writes); the live route and mapping rows equal it, as a publish leaves them. The source's
 * {@code customer_id} is the {@code customerId} the source posts (E4-F1-S2 body cross-check).
 */
final class DipeshExampleSeed {

    /** Target A, contract v1.0: {customer_code, order_number, total_amount}. */
    static final String EXPRESSION_A =
            "{\"customer_code\": customerId, \"order_number\": orderId, \"total_amount\": amount}";

    /** Target B, contract v2.0: {clientId, referenceNo, invoiceValue}. */
    static final String EXPRESSION_B =
            "{\"clientId\": customerId, \"referenceNo\": orderId, \"invoiceValue\": amount}";

    static final String INTERFACE_CODE = "orders";
    static final String SOURCE_CONTRACT_LABEL = "1.0";
    static final String BUSINESS_KEY_RULE = "orderId";

    /** One onboarded tenant: everything the suite needs to post and to look the rows up. */
    record Tenant(String word, UUID id, String customerId, String clientId, UUID interfaceId,
            UUID endpointA, UUID endpointB, UUID routeA, UUID routeB, String pathA, String pathB) {
    }

    private DipeshExampleSeed() {
    }

    /** The URL path each target endpoint of the tenant is addressed on (one stub path per target). */
    static String pathOf(String word, String target) {
        return "/" + word + "/" + target;
    }

    /** Onboards Dipesh's example for one tenant. The tenant row is written first (the directory owns it). */
    static Tenant onboard(Connection owner, String word, String customerId, String clientId, String issuer)
            throws Exception {
        UUID tenantId = UUID.randomUUID();
        execute(owner, "INSERT INTO public.tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE',"
                + " 'Etc/UTC')", tenantId, "tnt-e11-" + word + "-" + tenantId.toString().substring(0, 8));
        UUID author = UUID.randomUUID();
        UUID approver = UUID.randomUUID();

        UUID sourceSystem = UUID.randomUUID();
        execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction)"
                + " VALUES (?::uuid, ?::uuid, 'shop', 'Customer shop', 'SOURCE')", sourceSystem, tenantId);
        execute(owner, "INSERT INTO public.source_credential (tenant_id, source_system_id, customer_id, issuer,"
                + " client_id, status) VALUES (?::uuid, ?::uuid, ?, ?, ?, 'ACTIVE')",
                tenantId, sourceSystem, customerId, issuer, clientId);

        UUID sourceContract = UUID.randomUUID();
        UUID sourceCv = UUID.randomUUID();
        execute(owner, "INSERT INTO public.contract (id, tenant_id, system_id, code, role, name)"
                + " VALUES (?::uuid, ?::uuid, ?::uuid, 'orders-in', 'SOURCE_REQUEST', 'Orders in')",
                sourceContract, tenantId, sourceSystem);
        approvedVersion(owner, sourceCv, tenantId, sourceContract, SOURCE_CONTRACT_LABEL, author);

        UUID endpointA = UUID.randomUUID();
        UUID endpointB = UUID.randomUUID();
        UUID targetCvA = targetWithContract(owner, tenantId, "target-a", "Target A", endpointA,
                pathOf(word, "target-a"), "1.0", author);
        UUID targetCvB = targetWithContract(owner, tenantId, "target-b", "Target B", endpointB,
                pathOf(word, "target-b"), "2.0", author);

        UUID interfaceId = UUID.randomUUID();
        execute(owner, "INSERT INTO public.interface (id, tenant_id, code, name, mode, ordering, current_version,"
                + " published_version, status) VALUES (?::uuid, ?::uuid, ?, 'Orders', 'ASYNC', 'PER_KEY', 1, 1,"
                + " 'PUBLISHED')", interfaceId, tenantId, INTERFACE_CODE);
        UUID routeA = UUID.randomUUID();
        UUID routeB = UUID.randomUUID();
        UUID mappingA = UUID.randomUUID();
        UUID mappingB = UUID.randomUUID();
        UUID mappingVersionA = UUID.randomUUID();
        UUID mappingVersionB = UUID.randomUUID();
        route(owner, tenantId, interfaceId, routeA, endpointA, 1);
        route(owner, tenantId, interfaceId, routeB, endpointB, 2);
        mapping(owner, tenantId, routeA, mappingA, mappingVersionA, EXPRESSION_A, sourceCv, targetCvA, author);
        mapping(owner, tenantId, routeB, mappingB, mappingVersionB, EXPRESSION_B, sourceCv, targetCvB, author);

        String snapshot = FlowBundle.serialize(INTERFACE_CODE, "Orders", "PER_KEY", BUSINESS_KEY_RULE,
                List.of(new RouteRow(routeA.toString(), 1, null, false, "ACTIVE", endpointA.toString()),
                        new RouteRow(routeB.toString(), 2, null, false, "ACTIVE", endpointB.toString())),
                List.of(new MappingRow(mappingA.toString(), routeA.toString(), "[]", EXPRESSION_A,
                                sourceCv.toString(), targetCvA.toString(), mappingVersionA.toString()),
                        new MappingRow(mappingB.toString(), routeB.toString(), "[]", EXPRESSION_B,
                                sourceCv.toString(), targetCvB.toString(), mappingVersionB.toString())));
        execute(owner, "INSERT INTO public.interface_version (id, tenant_id, interface_id, version, status,"
                + " business_key_rule, snapshot, source_contract_version_id, author_id, approver_id, published_at)"
                + " VALUES (?::uuid, ?::uuid, ?::uuid, 1, 'PUBLISHED', ?, ?::jsonb, ?::uuid, ?::uuid, ?::uuid,"
                + " now())", UUID.randomUUID(), tenantId, interfaceId, BUSINESS_KEY_RULE, snapshot, sourceCv,
                author, approver);
        return new Tenant(word, tenantId, customerId, clientId, interfaceId, endpointA, endpointB, routeA, routeB,
                pathOf(word, "target-a"), pathOf(word, "target-b"));
    }

    private static UUID targetWithContract(Connection owner, UUID tenantId, String code, String name,
            UUID endpointId, String path, String label, UUID author) throws Exception {
        UUID system = UUID.randomUUID();
        String baseUrl = "http://" + code + ".e11.test:8080" + path;
        execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction, type, settings)"
                + " VALUES (?::uuid, ?::uuid, ?, ?, 'TARGET', 'API', ?::jsonb)", system, tenantId, code, name,
                "{\"baseUrl\":\"" + baseUrl + "\"}");
        execute(owner, "INSERT INTO public.target_endpoint (id, tenant_id, system_id, code, name, base_url,"
                + " http_method, auth_method, timeout_ms) VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, 'POST',"
                + " 'NO_AUTH', 5000)", endpointId, tenantId, system, code + "-orders", name + " orders", baseUrl);
        UUID contract = UUID.randomUUID();
        UUID contractVersion = UUID.randomUUID();
        execute(owner, "INSERT INTO public.contract (id, tenant_id, system_id, code, role, name)"
                + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'TARGET_REQUEST', ?)", contract, tenantId, system,
                code + "-orders", name + " orders");
        approvedVersion(owner, contractVersion, tenantId, contract, label, author);
        return contractVersion;
    }

    private static void approvedVersion(Connection owner, UUID id, UUID tenantId, UUID contract, String label,
            UUID author) throws Exception {
        execute(owner, "INSERT INTO public.contract_version (id, tenant_id, contract_id, version_label, status,"
                + " json_schema_draft_2020_12, author_id) VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'APPROVED',"
                + " '{}'::jsonb, ?::uuid)", id, tenantId, contract, label, author);
    }

    private static void route(Connection owner, UUID tenantId, UUID interfaceId, UUID routeId, UUID endpointId,
            int sequence) throws Exception {
        execute(owner, "INSERT INTO public.route (id, tenant_id, interface_id, target_endpoint_id, sequence,"
                + " condition, is_primary, status) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, NULL, false,"
                + " 'ACTIVE')", routeId, tenantId, interfaceId, endpointId, sequence);
    }

    private static void mapping(Connection owner, UUID tenantId, UUID routeId, UUID mappingId,
            UUID mappingVersionId, String expression, UUID sourceCv, UUID targetCv, UUID author) throws Exception {
        execute(owner, "INSERT INTO public.mapping (id, tenant_id, route_id, field_mappings, compiled_expression,"
                + " source_contract_version_id, target_contract_version_id)"
                + " VALUES (?::uuid, ?::uuid, ?::uuid, '[]'::jsonb, ?, ?::uuid, ?::uuid)",
                mappingId, tenantId, routeId, expression, sourceCv, targetCv);
        try (PreparedStatement version = owner.prepareStatement("INSERT INTO public.mapping_version (id, tenant_id,"
                + " mapping_id, version, source_contract_version_id, target_contract_version_id, engine,"
                + " field_mappings, compiled_expression, content_sha256, author_id)"
                + " VALUES (?::uuid, ?::uuid, ?::uuid, 1, ?::uuid, ?::uuid, 'JSONATA', '[]'::jsonb, ?, ?,"
                + " ?::uuid)")) {
            version.setString(1, mappingVersionId.toString());
            version.setString(2, tenantId.toString());
            version.setString(3, mappingId.toString());
            version.setString(4, sourceCv.toString());
            version.setString(5, targetCv.toString());
            version.setString(6, expression);
            version.setBytes(7, MessageDigest.getInstance("SHA-256")
                    .digest(expression.getBytes(StandardCharsets.UTF_8)));
            version.setString(8, author.toString());
            version.executeUpdate();
        }
    }

    static void execute(Connection owner, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int position = 0; position < values.length; position++) {
                Object value = values[position];
                if (value == null) {
                    statement.setNull(position + 1, Types.OTHER);
                } else {
                    statement.setObject(position + 1, value instanceof UUID id ? id.toString() : value);
                }
            }
            statement.executeUpdate();
        }
    }
}
