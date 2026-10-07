package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The source-credential state changes of ADR-007 Decision 3's revoke order (T-098), through the tenant-scoped
 * repository base: every statement carries the bound tenant's predicate (R08), runs in the caller's stamped
 * transaction (R11) and is parameterised with the explicit uuid cast (R09). The grants of V2_3_4 allow nothing else.
 */
class JdbcSourceCredentialStore extends TenantScopedRepository implements SourceCredentialStore {

    private static final String REVOKE = "UPDATE public.source_credential SET status = 'REVOKED', revoked_at = now()";
    private static final String NOT_REVOKED = "id = :id::uuid AND status <> 'REVOKED'";

    private static final String SET_PENDING = "UPDATE public.source_credential SET idp_disable_pending = :pending";
    private static final String REVOKED = "id = :id::uuid AND status = 'REVOKED'";

    private static final String SELECT_PENDING =
            "SELECT id, issuer, client_id, revoked_at FROM public.source_credential";
    private static final String IS_PENDING = "idp_disable_pending AND status = 'REVOKED'";

    private record Row(PendingDisable pending, OffsetDateTime revokedAt) {
    }

    JdbcSourceCredentialStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public boolean markRevoked(UUID credentialId) {
        return update(REVOKE, NOT_REVOKED, Map.of("id", credentialId.toString())) == 1;
    }

    @Override
    public boolean setIdpDisablePending(UUID credentialId, boolean pending) {
        return update(SET_PENDING, REVOKED, Map.of("id", credentialId.toString(), "pending", pending)) == 1;
    }

    @Override
    public List<PendingDisable> findIdpDisablePending() {
        // ponytail: the base appends the condition in parentheses, so the oldest-first order is applied here.
        return query(SELECT_PENDING, IS_PENDING, Map.of(), (rs, n) -> new Row(
                        new PendingDisable(rs.getObject("id", UUID.class), rs.getString("issuer"),
                                rs.getString("client_id")),
                        rs.getObject("revoked_at", OffsetDateTime.class)))
                .stream()
                .sorted(Comparator.comparing(Row::revokedAt)
                        .thenComparing(row -> row.pending().credentialId()))
                .map(Row::pending)
                .toList();
    }
}
