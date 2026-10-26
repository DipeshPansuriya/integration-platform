package io.integrationplatform.routing.stage2;

import java.sql.SQLTransientException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * T190 (T175 follow-up): a target call's platform reads ({@code resolve}) run in their own short transaction, so a
 * pool timeout or a database blip surfaces from {@code resolve} too. Those are the PLATFORM's transient faults - the
 * send is retried - and must not be read as "the target's settings are unusable" (PERMANENT, dead-lettered).
 */
public final class PlatformReadFaults {

    private PlatformReadFaults() {
    }

    /** True for a pool/connection/timeout/deadlock-style failure of the platform database, anywhere in the cause chain. */
    public static boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException || cause instanceof TransactionTimedOutException
                    || cause instanceof SQLTransientException) {
                return true;
            }
        }
        return false;
    }
}
