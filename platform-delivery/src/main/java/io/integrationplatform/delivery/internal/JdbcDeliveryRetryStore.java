package io.integrationplatform.delivery.internal;

import io.integrationplatform.delivery.DeliveryEventType;
import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.delivery.port.RetryCandidate;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.ingest.port.StageTimings;
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
 *
 * <p>With a {@link StageTimings} bound, every {@code deadLetter} move that matched a row also records the message's
 * DELIVER stage, finished at the move's own instant, in the same caller transaction (lane deliver-deadletter): the
 * credential dead letter (T-185) and the reconciler's AMBIGUOUS stale-claim dead letter reach it through here. The
 * send-outcome recorder's dead letters are NOT double-written: {@code JdbcSendOutcomes} builds its own store without
 * timings and the recorder writes the stage itself.
 */
public final class JdbcDeliveryRetryStore extends TenantScopedRepository implements DeliveryRetryStore {

    private final JdbcDeliveryRepository repository;
    private final StageTimings timings;

    /** Records no stage timing - for a database without the message family and for the recorder's own store. */
    public JdbcDeliveryRetryStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        this(jdbcClient, tenantContext, null);
    }

    /** {@code timings} may be null (a bare slice): the dead letters still move, no DELIVER stage is written. */
    public JdbcDeliveryRetryStore(JdbcClient jdbcClient, TenantContext tenantContext, StageTimings timings) {
        this(new JdbcDeliveryRepository(jdbcClient, tenantContext), jdbcClient, tenantContext, timings);
    }

    /** The seam for unit tests: the repository (the only SQL writer) is supplied. */
    public JdbcDeliveryRetryStore(JdbcDeliveryRepository repository, JdbcClient jdbcClient,
            TenantContext tenantContext, StageTimings timings) {
        super(jdbcClient, tenantContext);
        this.repository = repository;
        this.timings = timings;
    }

    @Override
    public List<RetryCandidate> lockDue(Instant now, int limit) {
        return query(
                "select scoped.id, scoped.attempts, scoped.next_attempt_at from (select d.id,"
                        + " (select count(*) from public.delivery_attempt a"
                        + " where a.delivery_id = d.id and a.tenant_id = :tenant_id::uuid"
            // T165-B (ADR-010 s6 M5): the current series - rows started after the latest REPLAY.
            + " and a.started_at > coalesce((select max(e.occurred_at) from public.delivery_event e"
            + " where e.delivery_id = d.id and e.tenant_id = d.tenant_id and e.type = 'REPLAY'),"
            + " '-infinity'::timestamptz)) as attempts,"
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
        Instant at = Instant.now();
        if (repository.deadLetter(deliveryId, claimToken, reasonClass, at)) {
            deliverFinished(deliveryId, at);
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
        Instant at = Instant.now();
        if (repository.deadLetter(deliveryId, claimToken, reasonClass, reason, at)) {
            deliverFinished(deliveryId, at);
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

    /** The move matched: the DELIVER stage finishes at its instant, in this transaction (the writer is idempotent). */
    private void deliverFinished(UUID deliveryId, Instant at) {
        if (timings != null) {
            timings.recordDeliverFinished(deliveryId, at);
        }
    }

    private void lateOutcome(UUID deliveryId, String detail) {
        repository.appendEvent(deliveryId, DeliveryEventType.LATE_OUTCOME, detail, Instant.now());
    }
}
