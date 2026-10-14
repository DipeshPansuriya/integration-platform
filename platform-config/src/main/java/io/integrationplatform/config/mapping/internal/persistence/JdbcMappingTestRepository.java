package io.integrationplatform.config.mapping.internal.persistence;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E7 lane B2: the live-mapping read by id for the mapping test endpoint ({@code POST /api/v1/admin/mappings/{id}/test}).
 * Lane C1's {@code JdbcMappingRepository} is not lane B2's file, so the id lookup rides in its own tenant-scoped
 * boundary reusing C1's {@link JdbcMappingRepository#LiveMapping} record. The id comparison is {@code ::text}, so a
 * non-uuid path id simply finds nothing - the same 404 as a foreign or unknown id, never a 500.
 */
public class JdbcMappingTestRepository extends TenantScopedRepository implements MappingTestRepository {

    private static final RowMapper<JdbcMappingRepository.LiveMapping> LIVE_MAPPING = (row, number) ->
            new JdbcMappingRepository.LiveMapping(
                    row.getString("id"), row.getString("field_mappings"), row.getString("compiled_expression"),
                    row.getString("source_contract_version_id"), row.getString("target_contract_version_id"), null);

    private static final String READ_MAPPING_BY_ID = "select m.id::text as id,"
            + " m.field_mappings::text as field_mappings, m.compiled_expression,"
            + " m.source_contract_version_id::text as source_contract_version_id,"
            + " m.target_contract_version_id::text as target_contract_version_id"
            + " from public.mapping m";

    public JdbcMappingTestRepository(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts) {
        super(jdbcClient, failClosed(tenantContexts));
    }

    private static TenantContext failClosed(ObjectProvider<TenantContext> tenantContexts) {
        return Objects.requireNonNull(tenantContexts, "tenantContexts")
                .getIfAvailable(() -> () -> Optional.empty());
    }

    @Override
    public Optional<JdbcMappingRepository.LiveMapping> findLiveMapping(String mappingId) {
        List<JdbcMappingRepository.LiveMapping> rows = query(
                READ_MAPPING_BY_ID, "m.id::text = :mapping_id", Map.of("mapping_id", mappingId), LIVE_MAPPING);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}