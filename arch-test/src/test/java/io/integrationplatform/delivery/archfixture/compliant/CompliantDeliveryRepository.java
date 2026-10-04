package io.integrationplatform.delivery.archfixture.compliant;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Compliant fixture for E1-F2-S1 "Compliant queries pass": platform-delivery writes through the tenant-scoped base. */
public class CompliantDeliveryRepository extends TenantScopedRepository {

    public CompliantDeliveryRepository(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    public int markHeld(UUID deliveryId) {
        return update("update delivery set state = 'HELD'", "id = :id", Map.of("id", deliveryId));
    }
}
