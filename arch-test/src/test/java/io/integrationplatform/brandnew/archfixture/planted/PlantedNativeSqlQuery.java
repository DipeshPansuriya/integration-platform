package io.integrationplatform.brandnew.archfixture.planted;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Planted violation for E1-F2-S1, example "native SQL string | a brand-new module": a native SQL string run through
 * JdbcClient in a package no module owns yet. Test class only - never in the production scan.
 */
public class PlantedNativeSqlQuery {

    private final JdbcClient jdbcClient;

    public PlantedNativeSqlQuery(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Map<String, Object>> loadEverything() {
        return jdbcClient.sql("select * from brand_new_table").query().listOfRows();
    }
}
