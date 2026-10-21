package io.integrationplatform.ingest.internal.intake;

import io.integrationplatform.config.flow.api.MaskedFields;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.HashMap;
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
    record PublishedInterface(UUID interfaceId, int publishedVersion, String ordering, String mode) {
    }

    /** A kept idempotency entry: the SHA-256 of the request body and the per-record results it answered (T-187). */
    record StoredResults(String requestHash, String results) {
    }

    /** The published version's flow content: the business key rule and the pinned source contract version id. */
    record PublishedVersion(UUID interfaceVersionId, String businessKeyRule, UUID sourceContractVersionId,
            List<String> maskedFields) {
    }

    IntakeStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    /** The tenant's PUBLISHED interface by code (unambiguous single table; the code is per-tenant unique). */
    Optional<PublishedInterface> resolvePublishedInterface(String code) {
        List<PublishedInterface> rows = query("select i.id as interface_id, i.published_version, i.ordering, i.mode from public.interface i",
                "i.code = :code and i.status = 'PUBLISHED'", Map.of("code", code),
                (rs, rowNum) -> new PublishedInterface(UUID.fromString(rs.getString("interface_id")),
                        rs.getInt("published_version"), rs.getString("ordering"), rs.getString("mode")));
        return rows.stream().findFirst();
    }

    /** The interface's published version row: its business key rule and the pinned source contract version id. */
    Optional<PublishedVersion> resolvePublishedVersion(PublishedInterface published) {
        List<PublishedVersion> rows = query(
                "select iv.id, iv.business_key_rule, iv.source_contract_version_id,"
                        + " (iv.snapshot->'interface'->'maskedFields')::text as masked_fields"
                        + " from public.interface_version iv",
                "iv.interface_id = :interface_id::uuid and iv.version = :published_version"
                        + " and iv.status = 'PUBLISHED'",
                Map.of("interface_id", published.interfaceId().toString(),
                        "published_version", published.publishedVersion()),
                (rs, rowNum) -> new PublishedVersion(UUID.fromString(rs.getString("id")),
                        rs.getString("business_key_rule"),
                        UUID.fromString(rs.getString("source_contract_version_id")),
                        MaskedFields.fromJson(rs.getString("masked_fields"))));
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
            String businessKey, String payloadBody, Long keySeq) {
        List<UUID> stored = query("""
                        with m as (
                            insert into public.message
                                (id, tenant_id, interface_id, interface_version_id, source_contract_version_id,
                                 business_key, key_seq, state)
                            values
                                (:message_id::uuid, :tenant_id::uuid, :interface_id::uuid,
                                 :interface_version_id::uuid, :source_contract_version_id::uuid, :business_key,
                                 :key_seq::bigint, 'ACCEPTED')
                            returning id, tenant_id, accepted_at
                        ), p as (
                            insert into public.payload (message_id, tenant_id, accepted_at, body)
                            select m.id, m.tenant_id, m.accepted_at, :body::jsonb from m
                        )
                        select m.id, m.tenant_id, m.accepted_at from m""",
                null,
                acceptParams(messageId, interfaceId, interfaceVersionId, sourceContractVersionId, businessKey,
                        payloadBody, keySeq),
                (rs, rowNum) -> UUID.fromString(rs.getString("id")));
        if (stored.size() != 1) {
            throw new IllegalStateException("the intake statement stored " + stored.size() + " message rows for the"
                    + " bound tenant - the row and the stamp disagreed (never expected; RLS WITH CHECK also guards)");
        }
        return stored.get(0);
    }

    private static Map<String, Object> acceptParams(UUID messageId, UUID interfaceId, UUID interfaceVersionId,
            UUID sourceContractVersionId, String businessKey, String payloadBody, Long keySeq) {
        Map<String, Object> params = new HashMap<>(); // key_seq is null when ordering is NONE (Map.of refuses null)
        params.put("message_id", messageId.toString());
        params.put("interface_id", interfaceId.toString());
        params.put("interface_version_id", interfaceVersionId.toString());
        params.put("source_contract_version_id", sourceContractVersionId.toString());
        params.put("business_key", businessKey);
        params.put("body", payloadBody);
        params.put("key_seq", keySeq);
        return params;
    }

    /** Purges the tenant's idempotency entries past their 24 h retention (T-187) - expired keys are new requests. */
    void purgeExpired() {
        update("delete from public.intake_idempotency", "expires_at < now()", Map.of());
    }

    /** The kept entry for this source credential and key, if any (scoped per tenant by the base, per credential here). */
    Optional<StoredResults> findIdempotency(String credentialRef, String idempotencyKey) {
        return query("select request_hash, results::text as results from public.intake_idempotency",
                "credential_ref = :credential_ref and idempotency_key = :idempotency_key and expires_at >= now()",
                Map.of("credential_ref", credentialRef, "idempotency_key", idempotencyKey),
                (rs, rowNum) -> new StoredResults(rs.getString("request_hash"), rs.getString("results")))
                .stream().findFirst();
    }

    /** Keeps the answer for 24 h (column default); the unique (tenant, credential, key) rejects a racing twin. */
    void saveIdempotency(String credentialRef, String idempotencyKey, String requestHash, String resultsJson) {
        query("""
                        with s as (
                            insert into public.intake_idempotency
                                (tenant_id, credential_ref, idempotency_key, request_hash, results)
                            values (:tenant_id::uuid, :credential_ref, :idempotency_key, :request_hash, :results::jsonb)
                            returning id, tenant_id
                        )
                        select s.id from s""",
                null,
                Map.of("credential_ref", credentialRef, "idempotency_key", idempotencyKey,
                        "request_hash", requestHash, "results", resultsJson),
                (rs, rowNum) -> rs.getString("id"));
    }

    /** Bounds the ordering-key row locks to the 2 s intake budget for this transaction only (T-196). */
    void lockTimeout() {
        query("select set_config('lock_timeout', '2s', true) as v, t.tenant_id from (select :tenant_id::uuid as tenant_id) t",
                null, Map.of(), (rs, rowNum) -> rs.getString("v"));
    }

    /**
     * Reserves {@code count} sequence numbers for one business key and returns the LAST one (T-196): the upsert
     * row-locks the key until commit, so concurrent writers of the same key serialise.
     */
    long nextSeq(UUID interfaceId, String businessKey, int count) {
        List<Long> seq = query("""
                        with k as (
                            insert into public.ordering_key (tenant_id, interface_id, business_key, last_seq)
                            values (:tenant_id::uuid, :interface_id::uuid, :business_key, :n)
                            on conflict (tenant_id, interface_id, business_key)
                                do update set last_seq = ordering_key.last_seq + excluded.last_seq
                            returning tenant_id, last_seq
                        )
                        select k.last_seq from k""",
                null,
                Map.of("interface_id", interfaceId.toString(), "business_key", businessKey, "n", count),
                (rs, rowNum) -> rs.getLong("last_seq"));
        if (seq.size() != 1) {
            throw new IllegalStateException("the ordering key upsert returned " + seq.size() + " rows");
        }
        return seq.get(0);
    }
}
