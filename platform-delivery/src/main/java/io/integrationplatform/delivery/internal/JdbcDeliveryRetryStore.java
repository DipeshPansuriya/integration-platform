package io.integrationplatform.delivery.internal;

import io.integrationplatform.delivery.DeliveryEventType;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.delivery.port.RetryCandidate;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The frozen {@link DeliveryRetryStore} contract (E2 seam) on lane C's real tables: every method runs in the
 * caller's open, tenant-bound transaction; every state change is conditional (0 rows means "no effect",
 * reported as {@code false}, never as an exception); a stale or foreign claim token additionally records the
 * {@code LATE_OUTCOME} delivery event without touching the state (ADR-010 section 6). This is the behaviour
 * lane B's fake mirrors.
 */
public final class JdbcDeliveryRetryStore extends TenantScopedRepository implements DeliveryRetryStore {

    private final JdbcDeliveryRepository repository;

    public JdbcDeliveryRetryStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
        this.repository = new JdbcDeliveryRepository(jdbcClient, tenantContext);
    }

    @Override
    public List<RetryCandidate> lockDue(Instant now, int limit) {
        return query(
                "select scoped.id, scoped.attempts, scoped.next_attempt_at from (select d.id,"
                        + " (select count(*) from public.delivery_attempt a"
                        + " where a.delivery_id = d.id and a.tenant_id = :tenant_id::uuid) as attempts,"
                        + " d.next_attempt_at, d.tenant_id from public.delivery d"
                        + " where d.tenant_id = :tenant_id::uuid and d.state = 'RETRY_WAIT'"
                        + " and d.next_attempt_at <= :now order by d.next_attempt_at limit :limit"
                        + " for update of d skip locked) scoped",
                null,
                Map.of("now", java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC), "limit", limit),
                (rs, n) -> new RetryCandidate(UUID.fromString(rs.getString(1)), rs.getInt(2),
                        rs.getTimestamp(3).toInstant()));
    }

    @Override
    public boolean markDue(UUID deliveryId) {
        return repository.redrive(deliveryId, Instant.now());
    }

    @Override
    public boolean scheduleRetry(UUID deliveryId, UUID claimToken, Instant nextAttemptAt) {
        if (repository.scheduleRetry(deliveryId, claimToken, nextAttemptAt, Instant.now())) {
            return true;
        }
        lateOutcome(deliveryId, "schedule-retry on a stale or foreign claim");
        return false;
    }

    @Override
    public boolean deadLetter(UUID deliveryId, UUID claimToken, OutcomeClass reasonClass) {
        if (repository.deadLetter(deliveryId, claimToken, reasonClass, Instant.now())) {
            return true;
        }
        lateOutcome(deliveryId, "dead-letter on a stale or foreign claim");
        return false;
    }

    /** ADR-010 section 2's deferral backoff: the first wait 1 s, doubling per consecutive deferral, capped at 30 s. */
    static final Duration KEY_DEFERRAL_FIRST = Duration.ofSeconds(1);
    static final Duration KEY_DEFERRAL_CAP = Duration.ofSeconds(30);

    @Override
    public boolean deferKeyUnavailable(UUID deliveryId, UUID claimToken, Instant now, double jitter) {
        if (jitter < 0.0 || jitter > 1.0) {
            throw new IllegalArgumentException("jitter fraction must be within [0, 1], was " + jitter);
        }
        // ponytail: "consecutive" = this delivery's earlier KEY_UNAVAILABLE events; T-200 (E10) refines the count
        int earlier = repository.countEvents(deliveryId, DeliveryEventType.KEY_UNAVAILABLE);
        Instant next = now.plus(keyDeferral(earlier + 1, jitter));
        if (repository.deferKeyUnavailable(deliveryId, claimToken, next, now)) {
            return true;
        }
        lateOutcome(deliveryId, "key-unavailable deferral on a stale or foreign claim");
        return false;
    }

    @Override
    public boolean deadLetter(UUID deliveryId, UUID claimToken, OutcomeClass reasonClass, String reason) {
        if (repository.deadLetter(deliveryId, claimToken, reasonClass, reason, Instant.now())) {
            return true;
        }
        lateOutcome(deliveryId, "dead-letter on a stale or foreign claim");
        return false;
    }

    /** The wait before the {@code deferral}-th consecutive key deferral's retry: full jitter of min(30 s, 1 s x 2^(n-1)). */
    static Duration keyDeferral(int deferral, double jitter) {
        Duration cap = deferral >= 6 ? KEY_DEFERRAL_CAP
                : KEY_DEFERRAL_FIRST.multipliedBy(1L << (deferral - 1));
        if (cap.compareTo(KEY_DEFERRAL_CAP) > 0) {
            cap = KEY_DEFERRAL_CAP;
        }
        return Duration.ofNanos((long) (cap.toNanos() * jitter));
    }

    private void lateOutcome(UUID deliveryId, String detail) {
        repository.appendEvent(deliveryId, DeliveryEventType.LATE_OUTCOME, detail, Instant.now());
    }
}
