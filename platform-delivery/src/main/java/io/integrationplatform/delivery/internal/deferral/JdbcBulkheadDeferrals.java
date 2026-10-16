package io.integrationplatform.delivery.internal.deferral;

import io.integrationplatform.delivery.port.BulkheadDeferrals;
import io.integrationplatform.delivery.port.OrderedStateChange;
import io.integrationplatform.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** T-198 RED stub: the conditional bulkhead-full deferral on the real tables (body lands in the GREEN commit). */
public final class JdbcBulkheadDeferrals implements BulkheadDeferrals {

    public JdbcBulkheadDeferrals(JdbcClient jdbcClient, TenantContext tenantContext,
            TransactionOperations transactions, OrderedStateChange ordered) {
    }

    @Override
    public boolean defer(UUID deliveryId, Instant now, int consecutiveDeferrals, double jitter) {
        throw new UnsupportedOperationException("T-198 RED stub");
    }
}
