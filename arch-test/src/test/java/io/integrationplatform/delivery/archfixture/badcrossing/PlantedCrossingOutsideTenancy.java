package io.integrationplatform.delivery.archfixture.badcrossing;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Planted violation: a listed reason, but the crossing lives outside io.integrationplatform.tenancy.internal. */
@TenantBoundaryCrossing("ipaas_work_tenant")
public class PlantedCrossingOutsideTenancy {

    private final JdbcClient jdbcClient;

    public PlantedCrossingOutsideTenancy(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Map<String, Object>> workTenants() {
        return jdbcClient.sql("select * from ipaas_work_tenant()").query().listOfRows();
    }
}
