package io.integrationplatform.tenancy.e5.credential;

import io.integrationplatform.tenancy.port.SourceCredentialStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * E5 T-103 test double of the {@link SourceCredentialStore} contract for ONE bound tenant, honouring the same rules the
 * PostgreSQL adapter enforces (V2_3_4 + V2_3_6): PENDING -> ACTIVE -> REVOKED only, revoked_at set with REVOKED,
 * idp_disable_pending only on a REVOKED row, revoke_after only on an ACTIVE row and set once. Every state change is
 * reported as true/false, never as an exception. The container proof of the real adapter is
 * {@code E5SourceCredentialStoreContainerTest}.
 */
public final class InMemorySourceCredentialStore implements SourceCredentialStore {

    /** One row; mutable on purpose (the store is the only writer). */
    public static final class Row {
        public final UUID id;
        final UUID sourceSystemId;
        final String customerId;
        final String issuer;
        final String clientId;
        final Instant createdAt;
        public String status = "PENDING";
        public boolean idpDisablePending;
        Instant revokedAt;
        Instant revokeAfter;

        Row(NewCredential credential, Instant createdAt) {
            this.id = credential.id();
            this.sourceSystemId = credential.sourceSystemId();
            this.customerId = credential.customerId();
            this.issuer = credential.issuer();
            this.clientId = credential.clientId();
            this.createdAt = createdAt;
        }

        Credential view() {
            return new Credential(id, sourceSystemId, customerId, issuer, clientId, status, idpDisablePending,
                    createdAt, revokedAt, revokeAfter);
        }
    }

    public final Map<UUID, Row> rows = new LinkedHashMap<>();
    private final Clock clock;

    public InMemorySourceCredentialStore(Clock clock) {
        this.clock = clock;
    }

    public Row row(UUID id) {
        return rows.get(id);
    }

    @Override
    public void insertPending(NewCredential credential) {
        if (rows.containsKey(credential.id())
                || rows.values().stream().anyMatch(row -> row.issuer.equals(credential.issuer())
                && row.clientId.equals(credential.clientId()))) {
            throw new IllegalStateException("unique violation");
        }
        rows.put(credential.id(), new Row(credential, clock.instant()));
    }

    @Override
    public boolean markActive(UUID credentialId) {
        Row row = rows.get(credentialId);
        if (row == null || !row.status.equals("PENDING")) {
            return false;
        }
        row.status = "ACTIVE";
        return true;
    }

    @Override
    public Optional<Credential> find(UUID credentialId) {
        return Optional.ofNullable(rows.get(credentialId)).map(Row::view);
    }

    @Override
    public List<Credential> findBySourceSystem(UUID sourceSystemId) {
        return rows.values().stream().filter(row -> row.sourceSystemId.equals(sourceSystemId))
                .sorted(Comparator.comparing((Row row) -> row.createdAt).thenComparing(row -> row.id))
                .map(Row::view).toList();
    }

    @Override
    public boolean scheduleRevoke(UUID credentialId, Duration grace) {
        Row row = rows.get(credentialId);
        if (row == null || !row.status.equals("ACTIVE") || row.revokeAfter != null) {
            return false;
        }
        row.revokeAfter = clock.instant().plus(grace);
        return true;
    }

    @Override
    public int abandonStalePending(Duration olderThan) {
        Instant limit = clock.instant().minus(olderThan);
        int changed = 0;
        for (Row row : rows.values()) {
            if (row.status.equals("PENDING") && row.createdAt.isBefore(limit)) {
                revoke(row);
                changed++;
            }
        }
        return changed;
    }

    @Override
    public int revokeExpiredRotations() {
        Instant now = clock.instant();
        int changed = 0;
        for (Row row : rows.values()) {
            if (row.status.equals("ACTIVE") && row.revokeAfter != null && !row.revokeAfter.isAfter(now)) {
                revoke(row);
                changed++;
            }
        }
        return changed;
    }

    @Override
    public boolean markRevoked(UUID credentialId) {
        Row row = rows.get(credentialId);
        if (row == null || row.status.equals("REVOKED")) {
            return false;
        }
        row.status = "REVOKED";
        row.revokedAt = clock.instant();
        return true;
    }

    @Override
    public boolean setIdpDisablePending(UUID credentialId, boolean pending) {
        Row row = rows.get(credentialId);
        if (row == null || !row.status.equals("REVOKED")) {
            return false;
        }
        row.idpDisablePending = pending;
        return true;
    }

    @Override
    public List<PendingDisable> findIdpDisablePending() {
        List<PendingDisable> pending = new ArrayList<>();
        rows.values().stream().filter(row -> row.idpDisablePending)
                .sorted(Comparator.comparing((Row row) -> row.revokedAt).thenComparing(row -> row.id))
                .forEach(row -> pending.add(new PendingDisable(row.id, row.issuer, row.clientId)));
        return pending;
    }

    private void revoke(Row row) {
        row.status = "REVOKED";
        row.revokedAt = clock.instant();
        row.idpDisablePending = true;
    }
}
