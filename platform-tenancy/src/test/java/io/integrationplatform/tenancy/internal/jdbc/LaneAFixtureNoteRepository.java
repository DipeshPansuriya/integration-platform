package io.integrationplatform.tenancy.internal.jdbc;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.TenantScopedRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Test adapter over the lane-A fixture table, written the way a module adapter uses the tenant-scoped base. */
class LaneAFixtureNoteRepository extends TenantScopedRepository {

    private static final String SELECT_TITLES = "select title from lane_a_fixture_note";

    LaneAFixtureNoteRepository(JdbcClient jdbcClient, TenantContext tenantContext) {
        super(jdbcClient, tenantContext);
    }

    List<String> titles() {
        return query(SELECT_TITLES, null, Map.of(), (row, rowNumber) -> row.getString("title"));
    }

    List<String> titlesStartingWith(String prefix) {
        return query(SELECT_TITLES, "title like :prefix", Map.of("prefix", prefix + "%"),
                (row, rowNumber) -> row.getString("title"));
    }

    List<String> titlesClaimingTenant(UUID claimedTenant) {
        return query(SELECT_TITLES, null, Map.of(TENANT_PARAMETER, claimedTenant.toString()),
                (row, rowNumber) -> row.getString("title"));
    }

    int retitle(UUID id, String title) {
        return update("update lane_a_fixture_note set title = :title", "id = :id", Map.of("id", id, "title", title));
    }
}
