package io.integrationplatform.tenancy.internal.archfixture.crossing;

import io.integrationplatform.tenancy.TenantBoundaryCrossing;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Allowed crossing fixture: a listed definer function, in tenancy internal - the rule lets it through. */
@TenantBoundaryCrossing("ipaas_login_bootstrap")
public class PlantedLoginBootstrapCrossing {

    private final JdbcClient jdbcClient;

    public PlantedLoginBootstrapCrossing(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Map<String, Object>> bootstrap(String issuer, String subject) {
        return jdbcClient.sql("select * from ipaas_login_bootstrap(:issuer, :subject)")
                .param("issuer", issuer)
                .param("subject", subject)
                .query()
                .listOfRows();
    }
}
