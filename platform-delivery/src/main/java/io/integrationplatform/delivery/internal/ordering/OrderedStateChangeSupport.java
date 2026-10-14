package io.integrationplatform.delivery.internal.ordering;

import io.integrationplatform.delivery.port.OrderedStateChangeRetryExhaustedException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;

/**
 * The 40001/40P01 in-place retry of the ONE lock order (T-197, ADR-010 section 7): a change whose attempt fails
 * with a serialization failure ({@code 40001}, Spring {@code CannotSerializeTransactionException}) or a deadlock
 * ({@code 40P01}, Spring {@code DeadlockLoserDataAccessException}) is retried in place - three attempts in total,
 * full-jitter backoff of at most 100 ms after the first failure and 200 ms after the second - writing no attempt
 * row of its own; the third consecutive failure is surfaced as
 * {@link OrderedStateChangeRetryExhaustedException}, which the caller answers with the E8-F1-S2 consumer pause.
 *
 * <p>When {@link #canRetry()} is {@code false} - the change already ran inside a caller's open transaction - a
 * failure cannot be retried (the caller transaction collapses) and the original failure propagates untouched.
 * Any OTHER failure propagates as it is, on the first attempt. The affected deliveries arrive in whatever order
 * the caller names them and are always locked in ascending id order with duplicates dropped (the one row order).
 */
public abstract class OrderedStateChangeSupport {

    /** The attempt budget - three attempts in total (ADR-010 section 7: "retried in place up to 3 times"). */
    static final int MAX_ATTEMPTS = 3;

    /** The full-jitter bound of the first wait, in ms; the second wait doubles it (never larger with 2 waits). */
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

    /**
     * Runs the change with the retry budget.
     *
     * @return the body's result, exactly once (a retried attempt lands exactly once)
     */
    <T> T orderedRun(String orderingKey, UUID messageId, List<UUID> deliveryIdsAscending, Supplier<T> body) {
        List<UUID> ascending = deliveryIdsAscending == null ? List.<UUID>of()
                : deliveryIdsAscending.stream().filter(Objects::nonNull).distinct().sorted().toList();
        long waitMillis = FIRST_WAIT_MILLIS;
        for (int attempt = 1; ; attempt++) {
            try {
                return attempt(orderingKey, messageId, ascending, body);
            } catch (ConcurrencyFailureException e) {
                if (!canRetry()) {
                    throw e; // the change ran inside a caller transaction: its whole transaction collapses
                }
                if (attempt >= MAX_ATTEMPTS) {
                    throw new OrderedStateChangeRetryExhaustedException(
                            "The ordered state change failed " + attempt
                                    + " consecutive times (40001/40P01) - the E8-F1-S2 consumer pause applies", e);
                }
                waitBeforeRetry((long) (waitMillis * Math.random())); // full jitter over the doubling band
                waitMillis = waitMillis * 2;
            }
        }
    }
}