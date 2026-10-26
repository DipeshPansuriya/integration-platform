package io.integrationplatform.routing.adapter.db;

import io.integrationplatform.routing.credential.OpenedCredentials;
import io.integrationplatform.routing.credential.SendTimeCredentials;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E16 F3 (design note task 11, Karan change 4) over the send path's credential seam: the DB system's target endpoints
 * (tenant-scoped, under row-level security) read and vault-opened in ONE short read-only transaction of its own - the
 * test-connection itself runs outside any transaction (the T-104 rule that no platform database connection is held
 * while a destination answers), so this port opens and closes its own. The first endpoint whose vault carries the
 * send path's password field ({@link JdbcDbTargets#DB_ACCOUNT_PASSWORD_FIELD}) answers; the returned array is a
 * fresh copy that survives the opened credentials' {@code close()} - the caller wipes it. A vault failure
 * propagates - never a passwordless probe.
 */
public class JdbcDbSystemCredentials extends TenantScopedRepository implements DbSystemCredentials {

    private static final String SELECT_ENDPOINTS = "select te.id as id from public.target_endpoint te";

    private final SendTimeCredentials credentials;
    private final TransactionTemplate readOnly;

    public JdbcDbSystemCredentials(JdbcClient jdbcClient, TenantContext tenantContext, SendTimeCredentials credentials,
            TransactionTemplate readOnly) {
        super(jdbcClient, tenantContext);
        this.credentials = credentials;
        this.readOnly = readOnly;
    }

    @Override
    public Optional<char[]> passwordOf(UUID systemId) {
        return readOnly.execute(status -> {
            List<UUID> endpoints = query(SELECT_ENDPOINTS, "te.system_id = :system_id::uuid",
                    Map.of("system_id", systemId.toString()), (rs, n) -> rs.getObject("id", UUID.class));
            for (UUID endpoint : endpoints) {
                try (OpenedCredentials opened = credentials.open(endpoint)) {
                    Optional<char[]> password = opened.value(JdbcDbTargets.DB_ACCOUNT_PASSWORD_FIELD);
                    if (password.isPresent()) {
                        return Optional.of(password.get().clone()); // close() zeroes the opened original
                    }
                }
            }
            return Optional.<char[]>empty();
        });
    }
}