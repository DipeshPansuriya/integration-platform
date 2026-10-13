package io.integrationplatform.delivery.internal.ordering;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.port.OrderedStateChange;
import io.integrationplatform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@link OrderedStateChange} port on the real tables (T-197, ADR-010 section 7): each attempt is one
 * transaction that takes the ordering-key advisory lock first (skipped only for a {@code null}/{@code blank} key -
 * ordering {@code NONE}), then the message row ({@code null} messageId skips it, and the table is E8 lane A's
 * wave-1 migration), then the affected deliveries in id order, then runs the body - all conditional updates inside
 * it. A failure of the attempt rolls the transaction back and, when no caller transaction was open, the change is
 * retried in place (40001/40P01, up to 3 attempts, full-jitter backoff, no attempt row); when the caller's
 * transaction was already open the attempt joins it - a failure there collapses the caller transaction, so no
 * retry is attempted.
 */
public final class JdbcOrderedStateChange extends OrderedStateChangeSupport implements OrderedStateChange {

    private final JdbcDeliveryRepository repository;
    private final TransactionTemplate transactions;

    public JdbcOrderedStateChange(JdbcClient jdbcClient, TenantContext tenantContext,
            PlatformTransactionManager transactionManager) {
        this(jdbcClient, tenantContext, new TransactionTemplate(transactionManager));
    }

    /** The test shape: the exact transaction template (a repeatable-read attempt is a container test's unit). */
    JdbcOrderedStateChange(JdbcClient jdbcClient, TenantContext tenantContext, TransactionTemplate transactions) {
        this.repository = new JdbcDeliveryRepository(jdbcClient, tenantContext);
        this.transactions = transactions;
    }

    @Override
    protected boolean canRetry() {
        return !TransactionSynchronizationManager.isActualTransactionActive();
    }

    // RED stub (T-197): the transaction shape lands with the green commit; the container test fails
    // (exceptional red) until then, which keeps every row-order scenario red exactly as the gherkin asks.
    @Override
    protected <T> T attempt(String orderingKey, UUID messageId, List<UUID> deliveryIdsAscending, Supplier<T> body) {
        throw new UnsupportedOperationException("T-197: the ordered state change lands with the green commit");
    }

    @Override
    public <T> T run(String orderingKey, UUID messageId, List<UUID> deliveryIdsAscending, Supplier<T> body) {
        return orderedRun(orderingKey, messageId, deliveryIdsAscending, body);
    }
}