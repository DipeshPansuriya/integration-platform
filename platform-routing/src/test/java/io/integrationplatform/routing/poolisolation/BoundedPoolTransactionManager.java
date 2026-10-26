package io.integrationplatform.routing.poolisolation;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * A fake pool of {@code size} connections (T175): one open transaction holds one permit, and a begin that cannot get
 * a permit within the wait fails like a Hikari connection timeout. Proves "other work proceeds" while a target call
 * is in flight, which a bare open-count cannot.
 */
public final class BoundedPoolTransactionManager implements PlatformTransactionManager {

    private final Semaphore connections;
    private final long waitMillis;

    public BoundedPoolTransactionManager(int size, long waitMillis) {
        this.connections = new Semaphore(size);
        this.waitMillis = waitMillis;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        try {
            if (!connections.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("pool exhausted: no connection within " + waitMillis + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
        connections.release();
    }

    @Override
    public void rollback(TransactionStatus status) {
        connections.release();
    }

    public int available() {
        return connections.availablePermits();
    }
}
