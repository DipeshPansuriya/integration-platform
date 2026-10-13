package io.integrationplatform.tenantdenial;

import io.integrationplatform.config.mapping.application.MappingContent;
import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import io.integrationplatform.tenantdenial.EndpointMatrix.WriteProbe;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

/**
 * E7 lane C1 (rohit-developer, T-190, back T-190/backlog T-190): the forged-tenant matrix part for lane C1's
 * mapping API ({@code /api/v1/admin/routes/{routeId}/mapping} - save/read, one mapping per route, bound source
 * and target contract versions) and its {@code mapping_version} rows. Each tenant gets one live route + mapping
 * on its main flow (bound to the source contract version the core seeds and an approved TARGET_REQUEST contract
 * version of the tenant's destination system) plus one deduplicated version row behind it; the publish flow's
 * pinned version row is the same row, referenced by the snapshot the core seeds. Entries target tenant A's route
 * (or the other tenant's, the forged id). Only that lane edits this file; see {@link LaneMatrix} for the rules.
 */
final class E7MappingMatrix implements LaneMatrix {

  private static final String ROUTES = "/api/v1/admin/routes";
  private static final String MAPPING = "/mapping";

  /** The target contract's field universe its links must stay inside (ADR-004 rule 4). */
  private static final String TARGET_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"customer_code\":{\"type\":\"string\"},"
          + "\"order_number\":{\"type\":\"string\"},\"total_amount\":{\"type\":\"number\"}}}";

  static UUID targetContractId(SeededTenant tenant) {
    return LaneMatrix.laneId(tenant, "matrix-target-contract");
  }

  static UUID targetCvId(SeededTenant tenant) {
    return LaneMatrix.laneId(tenant, "matrix-target-cv");
  }

  static UUID routeIdOf(SeededTenant tenant) {
    return LaneMatrix.laneId(tenant, "matrix-route");
  }

  static UUID mappingIdOf(SeededTenant tenant) {
    return LaneMatrix.laneId(tenant, "matrix-mapping");
  }

  static UUID mappingVersionIdOf(SeededTenant tenant) {
    return LaneMatrix.laneId(tenant, "matrix-mapping-version");
  }

  /**
   * The compiled expression the matrix PUT saves - a constant, so the WriteProbe's expected row state is a plain
   * literal. It is deliberately NOT in {@link #markers}: it is the same for both tenants, so it cannot
   * discriminate whose data an answer carries; the probe (row state) and the tenant-A data containment check own
   * that. Tenant markers are the ids and codes, which are unique per tenant.
   */
  static final String SAVE_COMPILED = "compiled-e7-save";

  @Override
  public List<Entry> entries() {
    return List.of(
        new Entry("GET", ROUTES + "/{routeId}" + MAPPING, false,
            s -> ROUTES + "/" + routeIdOf(s.a()) + MAPPING,
            s -> ROUTES + "/" + routeIdOf(s.b()) + MAPPING,
            null,
            s -> List.of(routeIdOf(s.a()).toString(), mappingIdOf(s.a()).toString(),
                mappingVersionIdOf(s.a()).toString(), targetCvId(s.a()).toString())),
        new Entry("PUT", ROUTES + "/{routeId}" + MAPPING, true,
            s -> ROUTES + "/" + routeIdOf(s.a()) + MAPPING,
            s -> ROUTES + "/" + routeIdOf(s.b()) + MAPPING,
            s -> "{\"fieldMappings\":[{\"source\":\"$.customerNumber\",\"target\":\"customer_code\"}],"
                + "\"compiledExpression\":\"" + SAVE_COMPILED
                + "\",\"sourceContractVersionId\":\"" + s.a().config().sourceContractVersionId()
                + "\",\"targetContractVersionId\":\"" + targetCvId(s.a()) + "\"}",
            s -> List.of(routeIdOf(s.a()).toString(), mappingIdOf(s.a()).toString(),
                SAVE_COMPILED),
            200,
            new WriteProbe(s -> "SELECT m.field_mappings::text || '/' || m.compiled_expression || '/v'"
                + " || (SELECT count(*) FROM public.mapping_version v WHERE v.mapping_id = m.id)"
                + " FROM public.mapping m WHERE m.id = '" + mappingIdOf(s.a()) + "'",
                "[]/$/v1",
                // jsonb::text renders with PostgreSQL's normalized spacing - the probe reads that text
                "[{\"source\": \"$.customerNumber\", \"target\": \"customer_code\"}]/"
                    + SAVE_COMPILED + "/v2")));
  }

  @Override
  public void clearTenantRows(Connection owner, SeededTenant tenant) throws Exception {
    // Lane C1's own tables, tenant-wide and deepest first: the mapping save API and the publish
    // materialization (JdbcFlowRepository insertMappingFromSnapshot) create mapping/mapping_version rows
    // whose ids this reset cannot name, and the V2_2_730 composite FKs (mapping -> contract_version,
    // mapping_version -> mapping/contract_version, mapping_sample_run -> mapping) make deleting the lane's
    // target contract version fail while any of them survive. The route and the target contract pair stay
    // id-scoped; the core's tenant-wide deletes take the materialized route rows after this.
    update(owner, "DELETE FROM public.mapping_sample_run WHERE tenant_id = ?::uuid", tenant.id().toString());
    update(owner, "DELETE FROM public.mapping_version WHERE tenant_id = ?::uuid", tenant.id().toString());
    update(owner, "DELETE FROM public.mapping WHERE tenant_id = ?::uuid", tenant.id().toString());
    update(owner, "DELETE FROM public.route WHERE id = ?::uuid", routeIdOf(tenant).toString());
    update(owner, "DELETE FROM public.contract_version WHERE contract_id = ?::uuid",
        targetContractId(tenant).toString());
    update(owner, "DELETE FROM public.contract WHERE id = ?::uuid", targetContractId(tenant).toString());
  }

  @Override
  public void writeTenantRows(Connection owner, SeededTenant tenant) throws Exception {
    String target = targetCvId(tenant).toString();
    String source = tenant.config().sourceContractVersionId().toString();
    update(owner,
        "INSERT INTO public.contract (id, tenant_id, system_id, code, role, name)"
            + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'TARGET_REQUEST', ?)",
        targetContractId(tenant).toString(), tenant.id().toString(),
        tenant.config().dstSystemId().toString(), targetContractCode(tenant),
        tenant.config().dstCode() + " mapping target contract");
    update(owner,
        "INSERT INTO public.contract_version (id, tenant_id, contract_id, version_label, status,"
            + " json_schema_draft_2020_12, author_id)"
            + " VALUES (?::uuid, ?::uuid, ?::uuid, '1.0', 'APPROVED', ?::jsonb, ?::uuid)",
        target, tenant.id().toString(), targetContractId(tenant).toString(), TARGET_SCHEMA,
        tenant.userId().toString());
    update(owner,
        "INSERT INTO public.route (id, tenant_id, interface_id, target_endpoint_id, sequence, status,"
            + " is_primary) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 1, 'ACTIVE', false)",
        routeIdOf(tenant).toString(), tenant.id().toString(), tenant.config().flowMainId().toString(),
        tenant.config().dstEndpointId().toString());
    update(owner,
        "INSERT INTO public.mapping (id, tenant_id, route_id, field_mappings, compiled_expression,"
            + " source_contract_version_id, target_contract_version_id)"
            + " VALUES (?::uuid, ?::uuid, ?::uuid, '[]'::jsonb, '$', ?::uuid, ?::uuid)",
        mappingIdOf(tenant).toString(), tenant.id().toString(), routeIdOf(tenant).toString(), source,
        target);
    update(owner,
        "INSERT INTO public.mapping_version (id, tenant_id, mapping_id, version,"
            + " source_contract_version_id, target_contract_version_id, engine, field_mappings,"
            + " compiled_expression, content_sha256, author_id)"
            + " VALUES (?::uuid, ?::uuid, ?::uuid, 1, ?::uuid, ?::uuid, '" + MappingContent.ENGINE
            + "', '[]'::jsonb, '$', decode(?, 'hex'), ?::uuid)",
        mappingVersionIdOf(tenant).toString(), tenant.id().toString(), mappingIdOf(tenant).toString(),
        source, target,
        MappingContent.contentSha256Hex(MappingContent.ENGINE, source, target, "[]", "$"),
        tenant.userId().toString());
  }

  @Override
  public List<String> markers(SeededTenant tenant) {
    return List.of(routeIdOf(tenant).toString(), mappingIdOf(tenant).toString(),
        mappingVersionIdOf(tenant).toString(), targetContractId(tenant).toString(),
        targetCvId(tenant).toString(), targetContractCode(tenant));
  }

  @Override
  public String fingerprint(Connection owner, UUID tenantId) throws Exception {
    try (PreparedStatement facts = owner.prepareStatement("SELECT"
        + " (SELECT 'mapping:' || count(*) || '@' || coalesce(string_agg(id::text || '='"
        + "   || md5(field_mappings::text || compiled_expression), ',' ORDER BY id), '')"
        + "   FROM public.mapping WHERE tenant_id = ?::uuid)"
        + " || ';' || (SELECT 'mapping_version:' || count(*) || '@' || coalesce(string_agg(id::text || '='"
        + "   || version || '#' || encode(content_sha256, 'hex'), ',' ORDER BY id), '')"
        + "   FROM public.mapping_version WHERE tenant_id = ?::uuid)")) {
      facts.setString(1, tenantId.toString());
      facts.setString(2, tenantId.toString());
      try (ResultSet rs = facts.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static String targetContractCode(SeededTenant tenant) {
    return "e7-target-" + tenant.config().word();
  }

  private static void update(Connection owner, String sql, String... args) throws Exception {
    try (PreparedStatement statement = owner.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setString(i + 1, args[i]);
      }
      statement.executeUpdate();
    }
  }
}