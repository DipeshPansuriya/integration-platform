package io.integrationplatform.delivery.internal.deferral;

import io.integrationplatform.delivery.port.DeliveryBulkheadFacts;
import io.integrationplatform.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** T-198 RED stub: the pre-permit facts read of T-177 on the real tables (body lands in the GREEN commit). */
public final class JdbcDeliveryBulkheadFacts implements DeliveryBulkheadFacts {

    public JdbcDeliveryBulkheadFacts(JdbcClient jdbcClient, TenantContext tenantContext,
            TransactionOperations transactions) {
    }

    @Override
    public Optional<BulkheadFacts> forDelivery(UUID deliveryId) {
        throw new UnsupportedOperationException("T-198 RED stub");
    }
}
