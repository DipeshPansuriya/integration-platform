package io.integrationplatform.tenancy.internal.archfixture.crossing;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Allowed crossing fixture: the transaction-local stamp of ADR-007 Decision 6, in tenancy internal. */
@TenantBoundaryCrossing("ADR-007 Decision 6")
public class PlantedTenantStampCrossing {

    private final JdbcClient jdbcClient;

    public PlantedTenantStampCrossing(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public String stamp(String tenantId) {
        return jdbcClient.sql("select set_config('app.tenant_id', :tenant_id, true)")
                .param("tenant_id", tenantId)
                .query(String.class)
                .single();
    }
}
