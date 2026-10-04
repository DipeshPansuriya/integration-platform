package io.integrationplatform.tenancy.internal.archfixture.badcrossing;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Planted violation: a boundary crossing whose reason is not on the reviewed list. */
@TenantBoundaryCrossing("reporting convenience")
public class PlantedUnlistedReasonCrossing {

    private final JdbcClient jdbcClient;

    public PlantedUnlistedReasonCrossing(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Map<String, Object>> readAllTenants() {
        return jdbcClient.sql("select * from tenant").query().listOfRows();
    }
}
