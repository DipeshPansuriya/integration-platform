package io.integrationplatform.delivery.internal.retry;

import io.integrationplatform.delivery.port.DeliveryRetryStore;
import io.integrationplatform.delivery.port.RetryCandidate;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.tenancy.NoTenantBoundException;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A {@link DeliveryRetryStore} honouring the interface's javadoc contract exactly, so lane B's callers
 * (T-090 trigger, outcome recorder) are honest fakes before lane C's real store (T-012, T-013) lands:
 *
 * <ul>
 *   <li>every method runs in the caller's open, tenant-bound transaction and fails closed when none is
 *       {@linkplain #beginTransaction() begun} - the contract's "never opens its own";</li>
 *   <li>{@link #lockDue} skips rows another transaction holds and never returns them twice; the locks
 *       release when that transaction commits or rolls back, so a crash leaves the row in
 *       {@code RETRY_WAIT} for the next run;</li>
 *   <li>every state change is conditional and 0 rows is {@code false}, never an exception; a stale or
 *       foreign claim token also records a {@code LATE_OUTCOME} event (#lateOutcomes()).</li>
 * </ul>
 *
 * <p>Test-only seam stand-in; the real store is proven on the real tables by lane C's container test.
 */
public final class FakeDeliveryRetryStore implements DeliveryRetryStore {

    private static final class Row {
        final UUID tenant;
        final UUID id;
        DeliveryState state;
        int attempts;
        Instant nextAttemptAt;
        UUID claimToken;
        OutcomeClass deadLetterReason;
        volatile Transaction lockedBy;

        Row(UUID tenant, UUID id, DeliveryState state, int attempts, Instant nextAttemptAt, UUID claimToken) {
            this.tenant = tenant;
            this.id = id;
            this.state = state;
            this.attempts = attempts;
            this.nextAttemptAt = nextAttemptAt;
            this.claimToken = claimToken;
        }
    }

    /** One caller transaction: the bound tenant (from {@link TenantContext}, like the stamped setting) and staged work. */
    public static final class Transaction {
        final UUID tenant;
        final Set<UUID> heldLocks = new HashSet<>();
        final List<Runnable> commitOps = new ArrayList<>();

        private Transaction(UUID tenant) {
            this.tenant = tenant;
        }
    }

    private final TenantContext tenantContext;
    private final List<Row> rows = new ArrayList<>();
    private final ThreadLocal<Transaction> current = new ThreadLocal<>();
    private final List<UUID> tenantsSeen = new ArrayList<>();
    private int lateOutcomes;
    private final Set<UUID> markDueRefusals = new HashSet<>();

    public FakeDeliveryRetryStore(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    // ---------- test-side setup and inspection (outside the interface contract) ----------

    public void seed(UUID tenant, UUID deliveryId, DeliveryState state, int attempts, Instant nextAttemptAt,
            UUID claimToken) {
        rows.add(new Row(tenant, deliveryId, state, attempts, nextAttemptAt, claimToken));
    }

    /** Makes {@link #markDue} return {@code false} for that delivery - the "0 rows changed" case. */
    public void refuseMarkDue(UUID deliveryId) {
        markDueRefusals.add(deliveryId);
    }

    /** Opens the caller's transaction for this thread, bound to the tenant in {@link TenantContext} (R08, R11). */
    public void beginTransaction() {
        if (current.get() != null) {
            throw new IllegalStateException("this thread already has an open transaction");
        }
        current.set(new Transaction(tenantContext.requireTenantId()));
    }

    public void commitTransaction() {
        Transaction txn = requireTransaction();
        txn.commitOps.forEach(Runnable::run);
        txn.heldLocks.forEach(id -> row(id).ifPresent(row -> row.lockedBy = null));
        current.remove();
    }

    public void rollbackTransaction() {
        Transaction txn = requireTransaction();
        txn.heldLocks.forEach(id -> row(id).ifPresent(row -> row.lockedBy = null));
        current.remove();
    }

    public DeliveryState stateOf(UUID deliveryId) {
        return row(deliveryId).orElseThrow().state;
    }

    public OutcomeClass deadLetterReasonOf(UUID deliveryId) {
        return row(deliveryId).orElseThrow().deadLetterReason;
    }

    public Instant nextAttemptAtOf(UUID deliveryId) {
        return row(deliveryId).orElseThrow().nextAttemptAt;
    }

    public int lateOutcomes() {
        return lateOutcomes;
    }

    public List<UUID> tenantsSeen() {
        return List.copyOf(tenantsSeen);
    }

    // ---------- the contract ----------

    @Override
    public List<RetryCandidate> lockDue(Instant now, int limit) {
        Transaction txn = requireTransaction();
        tenantsSeen.add(txn.tenant);
        synchronized (rows) {
            List<RetryCandidate> claimed = rowsOf(txn.tenant).stream()
                    .filter(row -> row.state == DeliveryState.RETRY_WAIT)
                    .filter(row -> row.lockedBy == null)
                    .filter(row -> !row.nextAttemptAt.isAfter(now))
                    .sorted(Comparator.comparing((Row row) -> row.nextAttemptAt).thenComparing(row -> row.id))
                    .limit(limit)
                    .peek(row -> {
                        row.lockedBy = txn;
                        txn.heldLocks.add(row.id);
                    })
                    .map(row -> new RetryCandidate(row.id, row.attempts, row.nextAttemptAt))
                    .toList();
            return claimed;
        }
    }

    @Override
    public boolean markDue(UUID deliveryId) {
        Transaction txn = requireTransaction();
        Optional<Row> found = rowOf(txn.tenant, deliveryId);
        if (found.isEmpty() || !txn.heldLocks.contains(deliveryId)
                || found.get().state != DeliveryState.RETRY_WAIT || markDueRefusals.contains(deliveryId)) {
            return false; // 0 rows: never in RETRY_WAIT anymore, not ours, or hidden from this tenant
        }
        Row row = found.get();
        txn.commitOps.add(() -> {
            row.state = DeliveryState.DUE;
            row.nextAttemptAt = null;
        });
        return true;
    }

    @Override
    public boolean scheduleRetry(UUID deliveryId, UUID claimToken, Instant nextAttemptAt) {
        Transaction txn = requireTransaction();
        Optional<Row> found = rowOf(txn.tenant, deliveryId);
        if (found.isEmpty() || !conditionalSend(found.get(), claimToken)) {
            lateOutcomes++; // stale or foreign token: the store records LATE_OUTCOME (ADR-010 section 6)
            return false;
        }
        Row row = found.get();
        txn.commitOps.add(() -> {
            row.state = DeliveryState.RETRY_WAIT;
            row.nextAttemptAt = nextAttemptAt;
            row.attempts++;
            row.claimToken = null;
        });
        return true;
    }

    @Override
    public boolean deadLetter(UUID deliveryId, UUID claimToken, OutcomeClass reasonClass) {
        Transaction txn = requireTransaction();
        Optional<Row> found = rowOf(txn.tenant, deliveryId);
        if (found.isEmpty() || !conditionalSend(found.get(), claimToken)) {
            lateOutcomes++;
            return false;
        }
        Row row = found.get();
        txn.commitOps.add(() -> {
            row.state = DeliveryState.DEAD_LETTERED;
            row.deadLetterReason = reasonClass;
            row.claimToken = null;
        });
        return true;
    }

    // ---------- helpers ----------

    private static boolean conditionalSend(Row row, UUID claimToken) {
        return row.state == DeliveryState.SENDING && row.claimToken != null && row.claimToken.equals(claimToken);
    }

    private Transaction requireTransaction() {
        Transaction txn = current.get();
        if (txn == null) {
            throw new IllegalStateException("the store was called outside the caller's open transaction");
        }
        if (!txn.tenant.equals(tenantContext.currentTenantId().orElseThrow(NoTenantBoundException::new))) {
            throw new IllegalStateException("the thread's bound tenant changed inside the transaction");
        }
        return txn;
    }

    private List<Row> rowsOf(UUID tenant) {
        synchronized (rows) {
            return rows.stream().filter(row -> row.tenant.equals(tenant)).toList();
        }
    }

    private Optional<Row> rowOf(UUID tenant, UUID id) {
        return rowsOf(tenant).stream().filter(row -> row.id.equals(id)).findFirst();
    }

    private Optional<Row> row(UUID id) {
        synchronized (rows) {
            return rows.stream().filter(candidate -> candidate.id.equals(id)).findFirst();
        }
    }
}
