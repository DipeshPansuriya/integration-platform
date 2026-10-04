package io.integrationplatform.config.archfixture.compliant;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Compliant fixture for E1-F2-S1 "Compliant queries pass": platform-config reads through the tenant-scoped base. */
public class CompliantRouteRepository extends TenantScopedRepository {

    public CompliantRouteRepository(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    public List<String> findRouteNames() {
        return query("select name from route", null, Map.of(), (row, rowNumber) -> row.getString("name"));
    }
}
