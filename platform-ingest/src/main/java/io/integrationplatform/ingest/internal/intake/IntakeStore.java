package io.integrationplatform.ingest.internal.intake;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The intake SQL (E11 A1 narrow) - every statement through {@link TenantScopedRepository} (R08), every fragment a
 * compile-time constant, every value a named parameter (R09/R11). Resolution is three single-table reads so the
 * base's unqualified {@code tenant_id} predicate stays unambiguous; the acceptance write is ONE statement whose
 * two data-modifying CTEs commit message and payload together (both carrying the same transaction-stable
 * {@code now()} as {@code accepted_at} - the partition key the payload family pairs on, ADR-014).
 */
class IntakeStore extends TenantScopedRepository {

    /** What the first read found: the published interface and which of its versions is published. */
    record PublishedInterface(UUID interfaceId, int publishedVersion) {
    }

    /** The published version's flow content: the business key rule and the pinned source contract version id. */
    record PublishedVersion(UUID interfaceVersionId, String businessKeyRule, UUID sourceContractVersionId) {
    }

    IntakeStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    /** The tenant's PUBLISHED interface by code (unambiguous single table; the code is per-tenant unique). */
    Optional<PublishedInterface> resolvePublishedInterface(String code) {
        List<PublishedInterface> rows = query("select i.id as interface_id, i.published_version from public.interface i",
                "i.code = :code and i.status = 'PUBLISHED'", Map.of("code", code),
                (rs, rowNum) -> new PublishedInterface(UUID.fromString(rs.getString("interface_id")),
                        rs.getInt("published_version")));
        return rows.stream().findFirst();
    }

    /** The interface's published version row: its business key rule and the pinned source contract version id. */
    Optional<PublishedVersion> resolvePublishedVersion(PublishedInterface published) {
        List<PublishedVersion> rows = query(
                "select iv.id, iv.business_key_rule, iv.source_contract_version_id from public.interface_version iv",
                "iv.interface_id = :interface_id::uuid and iv.version = :published_version"
                        + " and iv.status = 'PUBLISHED'",
                Map.of("interface_id", published.interfaceId().toString(),
                        "published_version", published.publishedVersion()),
                (rs, rowNum) -> new PublishedVersion(UUID.fromString(rs.getString("id")),
                        rs.getString("business_key_rule"),
                        UUID.fromString(rs.getString("source_contract_version_id"))));
        return rows.stream().findFirst();
    }

    /** The pinned source contract version's label - the value {@code X-Source-Contract-Version} must equal. */
    Optional<String> pinnedLabel(UUID sourceContractVersionId) {
        List<String> labels = query("select cv.version_label from public.contract_version cv",
                "cv.id = :id::uuid and cv.status = 'APPROVED'", Map.of("id", sourceContractVersionId.toString()),
                (rs, rowNum) -> rs.getString("version_label"));
        return labels.stream().findFirst();
    }

    /**
     * The acceptance write: message + payload in ONE statement. {@code now()} is transaction-start stable, so the
     * message and its payload share the exact {@code accepted_at} the message and payload partitions pair on
     * (ADR-014). The base's appended tenant predicate filters the RETURNING row: exactly one row id comes back for
     * a message that really carries the bound tenant - anything else is a bug and throws.
     *
     * @return the message id that was stored
     */
    UUID accept(UUID messageId, UUID interfaceId, UUID interfaceVersionId, UUID sourceContractVersionId,
            String businessKey, String payloadBody) {
        List<UUID> stored = query("""
                        with m as (
                            insert into public.message
                                (id, tenant_id, interface_id, interface_version_id, source_contract_version_id,
                                 business_key, state)
                            values
                                (:message_id::uuid, :tenant_id::uuid, :interface_id::uuid,
                                 :interface_version_id::uuid, :source_contract_version_id::uuid, :business_key,
                                 'ACCEPTED')
                            returning id, tenant_id, accepted_at
                        ), p as (
                            insert into public.payload (message_id, tenant_id, accepted_at, body)
                            select m.id, m.tenant_id, m.accepted_at, :body::jsonb from m
                        )
                        select m.id, m.tenant_id, m.accepted_at from m""",
                null,
                Map.of("message_id", messageId.toString(),
                        "interface_id", interfaceId.toString(),
                        "interface_version_id", interfaceVersionId.toString(),
                        "source_contract_version_id", sourceContractVersionId.toString(),
                        "business_key", businessKey,
                        "body", payloadBody),
                (rs, rowNum) -> UUID.fromString(rs.getString("id")));
        if (stored.size() != 1) {
            throw new IllegalStateException("the intake statement stored " + stored.size() + " message rows for the"
                    + " bound tenant - the row and the stamp disagreed (never expected; RLS WITH CHECK also guards)");
        }
        return stored.get(0);
    }
}