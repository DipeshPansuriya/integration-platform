package io.integrationplatform.delivery.port;

/** The 40001/40P01 retry budget is spent - three consecutive serialization failures or deadlocks (T-197). */
public class OrderedStateChangeRetryExhaustedException extends RuntimeException {

    public OrderedStateChangeRetryExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}