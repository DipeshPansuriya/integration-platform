package io.integrationplatform.config.mapping.internal.persistence;

import io.integrationplatform.config.mapping.api.MappingSampleRuns;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lane C2's JDBC side of the {@link MappingSampleRuns} seam (T-037, ADR-013 Decision 6): the mapping test endpoint
 * records each sample round trip into {@code mapping_sample_run} (V2_2_730, INSERT/SELECT only) keyed by the mapping
 * content hash, and the publish gate asks when that exact content last passed. Both statements are tenant-scoped by
 * {@link TenantScopedRepository} on top of the table's fail-closed RLS policy; the acting user of a record comes from
 * the request's bound login (supplied by the wiring - persistence never reads the security context itself).
 */
public class JdbcMappingSampleRuns extends TenantScopedRepository implements MappingSampleRuns {

    private static final String INSERT_RUN = "with ins as ("
            + "insert into public.mapping_sample_run"
            + "    (tenant_id, mapping_id, content_sha256, sample_sha256, passed, actor_id)"
            + " values (current_setting('app.tenant_id')::uuid, :mapping_id::uuid, :content_sha256,"
            + "    :sample_sha256, :passed, :actor_id::uuid)"
            + " returning id, tenant_id) select id::text from ins";

    private static final String READ_LAST_PASSING = "select max(r.ran_at) as last_passing"
            + " from public.mapping_sample_run r";

    private final Supplier<UUID> actor;

    public JdbcMappingSampleRuns(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts,
            Supplier<UUID> actor) {
        super(jdbcClient, Objects.requireNonNull(tenantContexts, "tenantContexts")
                .getIfAvailable(() -> () -> Optional.empty()));
        this.actor = Objects.requireNonNull(actor, "actor");
    }

    @Override
    public void record(UUID mappingId, String expressionHash, String sampleHash, boolean passed) {
        List<String> ids = query(INSERT_RUN, null, Map.of("mapping_id", mappingId.toString(),
                "content_sha256", HexFormat.of().parseHex(expressionHash),
                "sample_sha256", HexFormat.of().parseHex(sampleHash),
                "passed", passed,
                "actor_id", actor.get().toString()), (rs, n) -> rs.getString(1));
        if (ids.size() != 1) {
            throw new IllegalStateException("the sample run of mapping " + mappingId + " was not recorded");
        }
    }

    @Override
    public Optional<Instant> lastPassing(UUID mappingId, String expressionHash) {
        return query(READ_LAST_PASSING, "r.mapping_id = :mapping_id::uuid and r.content_sha256 = :content_sha256"
                + " and r.passed", Map.of("mapping_id", mappingId.toString(),
                        "content_sha256", HexFormat.of().parseHex(expressionHash)),
                (rs, n) -> rs.getTimestamp("last_passing")).stream()
                .filter(Objects::nonNull)
                .map(Timestamp::toInstant)
                .findFirst();
    }
}
