package io.integrationplatform.brandnew.archfixture.compliant;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Compliant fixture for E1-F2-S1 "Compliant queries pass": a brand-new module reads through the tenant-scoped base. */
public class CompliantBrandNewRepository extends TenantScopedRepository {

    public CompliantBrandNewRepository(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    public List<String> findCodes(String status) {
        return query("select code from brand_new_table", "status = :status", Map.of("status", status),
                (row, rowNumber) -> row.getString("code"));
    }
}
