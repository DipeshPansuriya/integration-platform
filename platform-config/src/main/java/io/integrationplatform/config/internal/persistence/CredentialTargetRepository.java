package io.integrationplatform.config.internal.persistence;

import io.integrationplatform.config.credential.api.SealedCredential;
import io.integrationplatform.config.credential.api.TargetCredentialVault;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E5 T-103 / T-185: the {@code target_credential} rows (V2_3_7) through the tenant-scoped repository base - every
 * statement carries the bound tenant's predicate (R08), runs in the caller's stamped transaction (R11) and binds every
 * value (R09). One row per field of a target endpoint, sealed by the service before it gets here; this class never
 * sees a plaintext value. It is also the send step's {@link TargetCredentialVault} (ciphertext only).
 *
 * <p>The grants of V2_3_7 allow INSERT and an UPDATE of the sealed columns only - a save of an existing field keeps
 * the row id (the snapshot and the audit name credentials by id) and replaces its data key, nonces and ciphertext.
 */
public class CredentialTargetRepository extends TenantScopedRepository implements TargetCredentialVault {

    /** A target endpoint of the bound tenant and its system's registered type (null for an E3 untyped system). */
    public record EndpointOwner(UUID endpointId, UUID tenantId, String systemType) {
    }

    /** A stored field's metadata (no blob): what the masked read shows. */
    public record StoredCredential(UUID id, String field, String targetType, Instant updatedAt) {
    }

    private static final String READ_ENDPOINT = "SELECT te.id, te.tenant_id, (SELECT s.type FROM public.system s"
            + " WHERE s.tenant_id = te.tenant_id AND s.id = te.system_id) AS system_type FROM public.target_endpoint te";
    private static final String LIST = "SELECT id, field, target_type, updated_at FROM public.target_credential";
    private static final String SEALED = "SELECT id, tenant_id, target_endpoint_id, field, kek_id, wrapped_key,"
            + " ciphertext FROM public.target_credential";
    private static final String OF_ENDPOINT = "target_endpoint_id = :endpoint::uuid";
    private static final String INSERT = "with ins as (insert into public.target_credential (id, tenant_id,"
            + " target_endpoint_id, target_type, field, kek_id, wrapped_key, ciphertext) values (:id::uuid,"
            + " current_setting('app.tenant_id')::uuid, :endpoint::uuid, :target_type, :field, :kek_id, :wrapped_key,"
            + " :ciphertext) returning id, tenant_id) select id::text from ins";
    private static final String RESEAL = "UPDATE public.target_credential SET kek_id = :kek_id,"
            + " wrapped_key = :wrapped_key, ciphertext = :ciphertext, updated_at = now()";

    private static final RowMapper<StoredCredential> STORED = (rs, n) -> new StoredCredential(
            rs.getObject("id", UUID.class), rs.getString("field"), rs.getString("target_type"),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private static final RowMapper<SealedCredential> SEALED_ROW = (rs, n) -> new SealedCredential(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
            rs.getObject("target_endpoint_id", UUID.class), rs.getString("field"), rs.getString("kek_id"),
            rs.getBytes("wrapped_key"), rs.getBytes("ciphertext"));

    public CredentialTargetRepository(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    public Optional<EndpointOwner> findEndpoint(UUID endpointId) {
        return query(READ_ENDPOINT, "te.id = :id::uuid", Map.of("id", endpointId.toString()),
                (rs, n) -> new EndpointOwner(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("system_type"))).stream().findFirst();
    }

    /** The endpoint's stored fields, by field name. */
    public List<StoredCredential> listForEndpoint(UUID endpointId) {
        return query(LIST, OF_ENDPOINT, Map.of("endpoint", endpointId.toString()), STORED).stream()
                .sorted(Comparator.comparing(StoredCredential::field)).toList();
    }

    public Optional<UUID> findId(UUID endpointId, String field) {
        return query("SELECT id FROM public.target_credential", OF_ENDPOINT + " AND field = :field",
                Map.of("endpoint", endpointId.toString(), "field", field), (rs, n) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    public void insert(UUID id, UUID endpointId, String targetType, String field, String kekId, byte[] wrappedKey,
            byte[] ciphertext) {
        List<String> inserted = query(INSERT, null, Map.of("id", id.toString(), "endpoint", endpointId.toString(),
                "target_type", targetType, "field", field, "kek_id", kekId, "wrapped_key", wrappedKey,
                "ciphertext", ciphertext), (rs, n) -> rs.getString(1));
        if (inserted.size() != 1) {
            throw new IllegalStateException("the target credential row was not written");
        }
    }

    /** Re-seals an existing field in place; the number of rows changed (0 or 1). */
    public int reseal(UUID id, String kekId, byte[] wrappedKey, byte[] ciphertext) {
        return update(RESEAL, "id = :id::uuid", Map.of("id", id.toString(), "kek_id", kekId,
                "wrapped_key", wrappedKey, "ciphertext", ciphertext));
    }

    @Override
    public List<SealedCredential> sealedFor(UUID targetEndpointId) {
        return query(SEALED, OF_ENDPOINT, Map.of("endpoint", targetEndpointId.toString()), SEALED_ROW).stream()
                .sorted(Comparator.comparing(SealedCredential::field)).toList();
    }
}
