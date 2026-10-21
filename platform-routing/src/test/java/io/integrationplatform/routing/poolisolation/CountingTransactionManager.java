package io.integrationplatform.routing.poolisolation;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * A fake transaction manager standing for the shared Hikari pool: one open transaction = one pooled connection held.
 * {@link #open()} is what a hanging target would pin; {@link #begun()} proves the reads ran in a transaction at all.
 */
public final class CountingTransactionManager implements PlatformTransactionManager {

    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger begun = new AtomicInteger();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        open.incrementAndGet();
        begun.incrementAndGet();
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
        open.decrementAndGet();
    }

    @Override
    public void rollback(TransactionStatus status) {
        open.decrementAndGet();
    }

    /** Transactions (pooled connections) held right now. */
    public int open() {
        return open.get();
    }

    /** Transactions begun so far. */
    public int begun() {
        return begun.get();
    }
}
