package io.integrationplatform.routing.stage1;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What stage 1 reads before it fans a message out (T-049 composition; ADR-010 section 2 stage 1): the message row the
 * record names - its state, its {@code accepted_at} (the deliveries' {@code created_at}, ADR-014) and the interface
 * version pinned on it - and that pinned version's published snapshot, whose routes and mapping versions are the ones
 * the deliveries pin (E9-C3: a later configuration change never alters what this message routes by). Tenant-scoped:
 * one table per statement, the tenant predicate appended, row-level security under it (R08).
 */
public class JdbcStage1Reads extends TenantScopedRepository {

    /** The message as stage 1 needs it. */
    public record PinnedMessage(UUID id, String state, Instant acceptedAt, UUID interfaceVersionId) {
    }

    public JdbcStage1Reads(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    public Optional<PinnedMessage> message(UUID messageId) {
        List<PinnedMessage> rows = query(
                "select m.id, m.state, m.accepted_at, m.interface_version_id from public.message m",
                "m.id = :id::uuid", Map.of("id", messageId.toString()),
                (rs, n) -> new PinnedMessage(rs.getObject("id", UUID.class), rs.getString("state"),
                        rs.getTimestamp("accepted_at").toInstant(), rs.getObject("interface_version_id", UUID.class)));
        return rows.stream().findFirst();
    }

    /** The pinned interface version's snapshot (the canonical flow bundle the publish laid). */
    public Optional<String> snapshotOf(UUID interfaceVersionId) {
        List<String> rows = query("select iv.snapshot::text as snapshot from public.interface_version iv",
                "iv.id = :id::uuid", Map.of("id", interfaceVersionId.toString()),
                (rs, n) -> rs.getString("snapshot"));
        return rows.stream().findFirst();
    }
}
