package io.integrationplatform.routing.stage;

/**
 * A record that cannot be read against the ids-only record contract of ADR-011 Decision 7 (bad JSON, missing or
 * wrong-typed {@code v}/{@code type}/{@code id}, an unknown work type). Its one destination is the poison topic,
 * written once - never a retry (T-113).
 */
public final class UnreadableRecordException extends RuntimeException {

    public UnreadableRecordException(String message) {
        super(message);
    }
}