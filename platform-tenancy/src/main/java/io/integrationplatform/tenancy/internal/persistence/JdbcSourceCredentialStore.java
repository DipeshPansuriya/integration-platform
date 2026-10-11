package io.integrationplatform.tenancy.internal.persistence;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import java.time.Duration;
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
 * The source-credential state changes of ADR-007 Decision 3's revoke order (T-098), through the tenant-scoped
 * repository base: every statement carries the bound tenant's predicate (R08), runs in the caller's stamped
 * transaction (R11) and is parameterised with the explicit uuid cast (R09). The grants of V2_3_4 allow nothing else.
 *
 * <p>E5 T-103 (additive): the issue order (INSERT PENDING, then ACTIVE), the rotation's {@code revoke_after} (V2_3_6,
 * the one new column-level UPDATE grant) and the reconciliation's two sweeps - every time is the database's
 * {@code now()}, the clock the client bootstrap compares against.
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

    // E5 T-103: the tenant comes from the stamped setting (RLS WITH CHECK refuses anything else), the outer select is
    // what the tenant-scoped base filters - the JdbcSystemRepository insert idiom.
    private static final String INSERT_PENDING = "with ins as (insert into public.source_credential (id, tenant_id,"
            + " source_system_id, customer_id, issuer, client_id, status) values (:id::uuid,"
            + " current_setting('app.tenant_id')::uuid, :system::uuid, :customer, :issuer, :client, 'PENDING')"
            + " returning id, tenant_id) select id::text from ins";
    private static final String ACTIVATE = "UPDATE public.source_credential SET status = 'ACTIVE'";
    private static final String PENDING = "id = :id::uuid AND status = 'PENDING'";
    private static final String SELECT_CREDENTIAL = "SELECT id, source_system_id, customer_id, issuer, client_id,"
            + " status, idp_disable_pending, created_at, revoked_at, revoke_after FROM public.source_credential";
    private static final String SCHEDULE_REVOKE =
            "UPDATE public.source_credential SET revoke_after = now() + make_interval(secs => :grace)";
    private static final String ACTIVE_UNSCHEDULED = "id = :id::uuid AND status = 'ACTIVE' AND revoke_after IS NULL";
    private static final String REVOKE_FOR_RECONCILIATION = "UPDATE public.source_credential SET status = 'REVOKED',"
            + " revoked_at = now(), idp_disable_pending = true";
    private static final String STALE_PENDING = "status = 'PENDING' AND created_at < now() - make_interval(secs => :age)";
    private static final String EXPIRED_ROTATION = "status = 'ACTIVE' AND revoke_after IS NOT NULL"
            + " AND revoke_after <= now()";

    private static final RowMapper<Credential> CREDENTIAL = (rs, n) -> new Credential(
            rs.getObject("id", UUID.class), rs.getObject("source_system_id", UUID.class), rs.getString("customer_id"),
            rs.getString("issuer"), rs.getString("client_id"), rs.getString("status"),
            rs.getBoolean("idp_disable_pending"), instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("revoked_at", OffsetDateTime.class)),
            instant(rs.getObject("revoke_after", OffsetDateTime.class)));

    JdbcSourceCredentialStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    @Override
    public void insertPending(NewCredential credential) {
        List<String> inserted = query(INSERT_PENDING, null, Map.of("id", credential.id().toString(),
                "system", credential.sourceSystemId().toString(), "customer", credential.customerId(),
                "issuer", credential.issuer(), "client", credential.clientId()), (rs, n) -> rs.getString(1));
        if (inserted.size() != 1) {
            throw new IllegalStateException("the pending credential row was not written");
        }
    }

    @Override
    public boolean markActive(UUID credentialId) {
        return update(ACTIVATE, PENDING, Map.of("id", credentialId.toString())) == 1;
    }

    @Override
    public Optional<Credential> find(UUID credentialId) {
        return query(SELECT_CREDENTIAL, "id = :id::uuid", Map.of("id", credentialId.toString()), CREDENTIAL)
                .stream().findFirst();
    }

    @Override
    public List<Credential> findBySourceSystem(UUID sourceSystemId) {
        // ponytail: the base appends the condition in parentheses, so the oldest-first order is applied here.
        return query(SELECT_CREDENTIAL, "source_system_id = :system::uuid",
                Map.of("system", sourceSystemId.toString()), CREDENTIAL).stream()
                .sorted(Comparator.comparing(Credential::createdAt).thenComparing(Credential::id))
                .toList();
    }

    @Override
    public boolean scheduleRevoke(UUID credentialId, Duration grace) {
        return update(SCHEDULE_REVOKE, ACTIVE_UNSCHEDULED,
                Map.of("id", credentialId.toString(), "grace", (double) grace.toSeconds())) == 1;
    }

    @Override
    public int abandonStalePending(Duration olderThan) {
        return update(REVOKE_FOR_RECONCILIATION, STALE_PENDING, Map.of("age", (double) olderThan.toSeconds()));
    }

    @Override
    public int revokeExpiredRotations() {
        return update(REVOKE_FOR_RECONCILIATION, EXPIRED_ROTATION, Map.of());
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
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
