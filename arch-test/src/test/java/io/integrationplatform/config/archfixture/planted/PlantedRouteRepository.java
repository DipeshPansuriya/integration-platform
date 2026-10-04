package io.integrationplatform.config.archfixture.planted;

import java.util.List;
import org.springframework.jdbc.core.support.JdbcDaoSupport;

/**
 * Planted violation for E1-F2-S1, example "repository method | platform-config": a repository type outside the
 * tenant-scoped base whose query carries no tenant predicate. Test class only - never in the production scan.
 */
public class PlantedRouteRepository extends JdbcDaoSupport {

    public List<String> findRouteNames() {
        return getJdbcTemplate().queryForList("select name from route", String.class);
    }
}
