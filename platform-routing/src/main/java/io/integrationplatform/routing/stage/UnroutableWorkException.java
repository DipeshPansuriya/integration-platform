package io.integrationplatform.routing.stage;

/**
 * Work that is readable but can never succeed however often it is retried (T190: a message pinned to a flow snapshot
 * published before the bundle carried the target contract id). The stage consumer sends the record to the poison
 * topic once, with this message as the reason, and commits its offset - classing it TRANSIENT would never commit the
 * offset, block the partition for ever and feed the database breaker (same destination as {@link
 * UnreadableRecordException}).
 */
public final class UnroutableWorkException extends RuntimeException {

    public UnroutableWorkException(String message) {
        super(message);
    }
}
