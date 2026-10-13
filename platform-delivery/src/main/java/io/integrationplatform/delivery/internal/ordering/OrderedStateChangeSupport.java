package io.integrationplatform.delivery.internal.ordering;

import io.integrationplatform.delivery.port.OrderedStateChangeRetryExhaustedException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;

/**
 * The 40001/40P01 in-place retry of the ONE lock order (T-197, ADR-010 section 7): a change whose attempt fails
 * with a serialization failure ({@code 40001}, Spring {@code CannotSerializeTransactionException}) or a deadlock
 * ({@code 40P01}, Spring {@code DeadlockLoserDataAccessException}) is retried in place - up to 3 attempts in
 * total, with full-jitter backoff between them - writing no attempt row of its own; the exhaustion is surfaced as
 * {@link OrderedStateChangeRetryExhaustedException}, which the caller answers with the E8-F1-S2 consumer pause.
 *
 * <p>The attempt itself (the advisory key lock, the message row, the deliveries in id order, then the body) is
 * {@link #attempt}; a subclass decides its transaction shape and whether a failure may be retried at all
 * ({@link #canRetry}: a change that already runs inside a caller's open transaction cannot be retried - such a
 * failure collapses the caller's whole transaction). Any OTHER failure propagates as it is, on the first attempt.
 */
public abstract class OrderedStateChangeSupport {

    /** The attempt budget - three attempts in total (ADR-010 section 7: "retried in place up to 3 times"). */
    static final int MAX_ATTEMPTS = 3;

    /** The full-jitter bound of the first wait, in ms; the second doubles it (never larger with 2 waits). */
    static final long FIRST_WAIT_MILLIS = 100;

    /** One attempt of the change; throws {@code ConcurrencyFailureException} when the attempt was lost. */
    protected abstract <T> T attempt(String orderingKey, UUID messageId, List<UUID> deliveryIdsAscending,
            Supplier<T> body);

    /** {@code false} when a failure must not be retried at all (the change runs inside a caller transaction). */
    protected abstract boolean canRetry();

    /** The wait before the next attempt; tests override to record instead of sleeping. */
    protected void waitBeforeRetry(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // RED stub (T-197): one attempt, ids exactly as given - the retry loop and the ascending row order are the
    // change under test; this compiles the green test's surface without its behavior.
    <T> T orderedRun(String orderingKey, UUID messageId, List<UUID> deliveryIdsAscending, Supplier<T> body) {
        return attempt(orderingKey, messageId, deliveryIdsAscending, body);
    }
}