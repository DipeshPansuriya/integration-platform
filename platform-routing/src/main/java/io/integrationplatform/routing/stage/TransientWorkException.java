package io.integrationplatform.routing.stage;

/**
 * A database call that failed transiently (connection loss, serialization 40001, deadlock 40P01) while the stage
 * base was resolving the row's tenant, checking the row or running the handler. The partition pauses, the offset
 * is not committed, a back-off passes and the record is retried - no record skipped, none poisoned (T-181, ADR
 * fix m-817272 B4).
 */
public final class TransientWorkException extends RuntimeException {

    public TransientWorkException(String message) {
        super(message);
    }
}