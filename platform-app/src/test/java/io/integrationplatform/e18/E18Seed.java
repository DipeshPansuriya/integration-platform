package io.integrationplatform.e18;

import io.integrationplatform.config.flow.application.FlowBundle;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.MappingRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.RouteRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

/**
 * E18 onboarding rows: one tenant, one source credential, the source contract {@code orders} v1.0, ONE target of any
 * type (the stored {@code system.type} and its registry settings JSON are the caller's), one route with one JSONata
 * mapping, and the published interface {@code orders}. Inserted as the migration owner while the application runs, so
 * onboarding is rows only (0 deploys, 0 restarts) - the E11 pattern generalised from API to every target type.
 */
final class E18Seed {

    static final String INTERFACE_CODE = "orders";
    static final String SOURCE_CONTRACT_LABEL = "1.0";
    static final String BUSINESS_KEY_RULE = "orderId";
    /** The partner's contract: {order_no, customer, total}. */
    static final String EXPRESSION = "{\"order_no\": orderId, \"customer\": customerId, \"total\": amount}";

    /** One onboarded tenant with its single target. */
    record Tenant(UUID id, String customerId, String clientId, UUID interfaceId, UUID endpointId, UUID systemId,
            UUID routeId) {
    }

    private E18Seed() {
    }

    /**
     * @param systemType  the stored {@code system.type} the stage-2 dispatch keys on (API, MQ, KAFKA, DB, FILE)
     * @param settings    the system's registry settings JSON (host/port/queue, bootstrapServers/topic, directory ...)
     * @param baseUrl     the endpoint's NOT NULL base_url (the real URL for API; a descriptive URI otherwise)
     * @param idempotent  the endpoint's idempotent_receive flag (decides whether a 5xx is retried or ambiguous)
     */
    static Tenant onboard(Connection owner, String word, String customerId, String clientId, String issuer,
            String systemType, String settings, String baseUrl, boolean idempotent) throws Exception {
        UUID tenantId = UUID.randomUUID();
        settings = settings.replace("{tenant}", tenantId.toString()); // FILE roots live under <base>/<tenantId>
        baseUrl = baseUrl.replace("{tenant}", tenantId.toString());
        execute(owner, "INSERT INTO public.tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE',"
                + " 'Etc/UTC')", tenantId, "tnt-e18-" + word + "-" + tenantId.toString().substring(0, 8));
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

        UUID targetSystem = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction, type, settings)"
                + " VALUES (?::uuid, ?::uuid, 'partner', 'Partner', 'TARGET', ?, ?::jsonb)",
                targetSystem, tenantId, systemType, settings);
        execute(owner, "INSERT INTO public.target_endpoint (id, tenant_id, system_id, code, name, base_url,"
                + " http_method, auth_method, timeout_ms, idempotent_receive) VALUES (?::uuid, ?::uuid, ?::uuid,"
                + " 'partner-orders', 'Partner orders', ?, 'POST', 'NO_AUTH', 5000, ?)",
                endpointId, tenantId, targetSystem, baseUrl, idempotent);
        UUID targetContract = UUID.randomUUID();
        UUID targetCv = UUID.randomUUID();
        execute(owner, "INSERT INTO public.contract (id, tenant_id, system_id, code, role, name)"
                + " VALUES (?::uuid, ?::uuid, ?::uuid, 'partner-orders', 'TARGET_REQUEST', 'Partner orders')",
                targetContract, tenantId, targetSystem);
        approvedVersion(owner, targetCv, tenantId, targetContract, "1.0", author);

        UUID interfaceId = UUID.randomUUID();
        execute(owner, "INSERT INTO public.interface (id, tenant_id, code, name, mode, ordering, current_version,"
                + " published_version, status) VALUES (?::uuid, ?::uuid, ?, 'Orders', 'ASYNC', 'PER_KEY', 1, 1,"
                + " 'PUBLISHED')", interfaceId, tenantId, INTERFACE_CODE);
        UUID routeId = UUID.randomUUID();
        UUID mappingId = UUID.randomUUID();
        UUID mappingVersionId = UUID.randomUUID();
        execute(owner, "INSERT INTO public.route (id, tenant_id, interface_id, target_endpoint_id, sequence,"
                + " condition, is_primary, status) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 1, NULL, false,"
                + " 'ACTIVE')", routeId, tenantId, interfaceId, endpointId);
        execute(owner, "INSERT INTO public.mapping (id, tenant_id, route_id, field_mappings, compiled_expression,"
                + " source_contract_version_id, target_contract_version_id)"
                + " VALUES (?::uuid, ?::uuid, ?::uuid, '[]'::jsonb, ?, ?::uuid, ?::uuid)",
                mappingId, tenantId, routeId, EXPRESSION, sourceCv, targetCv);
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
            version.setString(6, EXPRESSION);
            version.setBytes(7, MessageDigest.getInstance("SHA-256").digest(EXPRESSION.getBytes(StandardCharsets.UTF_8)));
            version.setString(8, author.toString());
            version.executeUpdate();
        }
        String snapshot = FlowBundle.serialize(INTERFACE_CODE, "Orders", "PER_KEY", BUSINESS_KEY_RULE,
                List.of(new RouteRow(routeId.toString(), 1, null, false, "ACTIVE", endpointId.toString())),
                List.of(new MappingRow(mappingId.toString(), routeId.toString(), "[]", EXPRESSION,
                        sourceCv.toString(), targetCv.toString(), mappingVersionId.toString())));
        execute(owner, "INSERT INTO public.interface_version (id, tenant_id, interface_id, version, status,"
                + " business_key_rule, snapshot, source_contract_version_id, author_id, approver_id, published_at)"
                + " VALUES (?::uuid, ?::uuid, ?::uuid, 1, 'PUBLISHED', ?, ?::jsonb, ?::uuid, ?::uuid, ?::uuid,"
                + " now())", UUID.randomUUID(), tenantId, interfaceId, BUSINESS_KEY_RULE, snapshot, sourceCv,
                author, approver);
        return new Tenant(tenantId, customerId, clientId, interfaceId, endpointId, targetSystem, routeId);
    }

    private static void approvedVersion(Connection owner, UUID id, UUID tenantId, UUID contract, String label,
            UUID author) throws Exception {
        execute(owner, "INSERT INTO public.contract_version (id, tenant_id, contract_id, version_label, status,"
                + " json_schema_draft_2020_12, author_id) VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'APPROVED',"
                + " '{}'::jsonb, ?::uuid)", id, tenantId, contract, label, author);
    }

    static void execute(Connection owner, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int position = 0; position < values.length; position++) {
                Object value = values[position];
                if (value instanceof Boolean flag) {
                    statement.setBoolean(position + 1, flag);
                } else {
                    statement.setObject(position + 1, value instanceof UUID id ? id.toString() : value);
                }
            }
            statement.executeUpdate();
        }
    }
}
