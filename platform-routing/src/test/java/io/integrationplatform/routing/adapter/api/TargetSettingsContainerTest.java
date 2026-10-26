package io.integrationplatform.routing.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.delivery.port.TargetHalfOpenCap;
import io.integrationplatform.domain.delivery.OutcomeClass;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * E10 T96 on the real schema (every migration is on this module's test classpath): the half-open cap is the
 * {@code halfOpenTrialCalls} of the target's system settings (default 3, never outside 1 to 100), and the error
 * dictionary the adapter classifies with is the {@code error_dictionary} column overlaid by the system's
 * {@code settings.errorDictionary} - the one the console actually saves.
 */
@Tag("container")
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class TargetSettingsContainerTest {

    private static final UUID TENANT = UUID.fromString("eeee1000-0000-0000-0000-00000000000a");
    private static final UUID OTHER_TENANT = UUID.fromString("eeee1000-0000-0000-0000-00000000000b");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine");

    private DataSource owner;

    @BeforeAll
    void migrateAndSeed() throws SQLException {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = new SimpleDriverDataSource(new org.postgresql.Driver(), POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection sql = owner.getConnection()) {
            int code = 0;
            for (UUID tenant : new UUID[] {TENANT, OTHER_TENANT}) {
                PreparedStatement insert = sql.prepareStatement(
                        "insert into public.tenant (id, code, status, time_zone) values (?, ?, 'ACTIVE', 'Etc/UTC')");
                insert.setObject(1, tenant);
                insert.setString(2, "t-e10-" + code++);
                insert.executeUpdate();
            }
            seed(sql, 1, "five", "{\"halfOpenTrialCalls\":5}", "{\"503\":\"PERMANENT\",\"418\":\"PERMANENT\"}",
                    "{\"503\":\"TRANSIENT\",\"502\":\"TRANSIENT\"}");
            seed(sql, 2, "absent", "{\"baseUrl\":\"https://x.example\"}", "{}", null);
            seed(sql, 3, "range", "{\"halfOpenTrialCalls\":0}", "{}", null);
            seed(sql, 4, "nosettings", null, "{}", null);
        }
    }

    private static UUID system(int n) {
        return UUID.fromString("eeee1000-0000-0000-0000-0000000001" + n + "0");
    }

    private static UUID endpoint(int n) {
        return UUID.fromString("eeee1000-0000-0000-0000-0000000002" + n + "0");
    }

    /** One system of {@code TENANT} with one endpoint; {@code settings} may be null (then the type is too). */
    private static void seed(Connection sql, int n, String code, String settings, String columnDictionary,
            String settingsDictionary) throws SQLException {
        String merged = settings;
        if (settings != null && settingsDictionary != null) {
            merged = settings.substring(0, settings.length() - 1) + ",\"errorDictionary\":" + settingsDictionary + "}";
        }
        PreparedStatement system = sql.prepareStatement(
                "insert into public.system (id, tenant_id, code, name, direction, type, settings) values"
                        + " (?, ?, ?, 'Target', 'TARGET', ?, ?::jsonb)");
        system.setObject(1, system(n));
        system.setObject(2, TENANT);
        system.setString(3, code);
        system.setString(4, merged == null ? null : "API");
        system.setString(5, merged);
        system.executeUpdate();
        PreparedStatement endpoint = sql.prepareStatement(
                "insert into public.target_endpoint (id, tenant_id, system_id, code, name, base_url, http_method,"
                        + " auth_method, timeout_ms, error_dictionary) values (?, ?, ?, ?, 'Endpoint',"
                        + " 'https://x.example/o', 'POST', 'NO_AUTH', 1500, ?::jsonb)");
        endpoint.setObject(1, endpoint(n));
        endpoint.setObject(2, TENANT);
        endpoint.setObject(3, system(n));
        endpoint.setString(4, code + "-ep");
        endpoint.setString(5, columnDictionary);
        endpoint.executeUpdate();
    }

    private <T> T asTenant(UUID tenant, Supplier<T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(owner)).execute(status -> {
            JdbcClient.create(owner).sql("select set_config('app.tenant_id', :t, true)")
                    .param("t", tenant.toString()).query().singleRow();
            return work.get();
        });
    }

    private TargetHalfOpenCap cap(UUID tenant) {
        return new JdbcTargetHalfOpenCap(JdbcClient.create(owner), () -> Optional.of(tenant));
    }

    @Test
    @DisplayName("halfOpenTrialCalls of the target's system settings is the cap")
    void theCapIsTheSettingsValue() {
        assertThat(asTenant(TENANT, () -> cap(TENANT).trialCalls(endpoint(1)))).isEqualTo(5);
    }

    @Test
    @DisplayName("an absent key, an out-of-range value, a system without settings and an unknown target give 3")
    void theCapDefaultsToThree() {
        assertThat(asTenant(TENANT, () -> cap(TENANT).trialCalls(endpoint(2)))).isEqualTo(3);
        assertThat(asTenant(TENANT, () -> cap(TENANT).trialCalls(endpoint(3)))).isEqualTo(3);
        assertThat(asTenant(TENANT, () -> cap(TENANT).trialCalls(endpoint(4)))).isEqualTo(3);
        assertThat(asTenant(TENANT, () -> cap(TENANT).trialCalls(UUID.randomUUID()))).isEqualTo(3);
    }

    @Test
    @Tag("negative")
    @DisplayName("another tenant's target is not readable: the default, never that tenant's setting")
    void anotherTenantsSettingIsNotRead() {
        assertThat(asTenant(OTHER_TENANT, () -> cap(OTHER_TENANT).trialCalls(endpoint(1)))).isEqualTo(3);
    }

    @Test
    @DisplayName("error dictionary: the settings entry wins over the column, entries of either are kept")
    void theDictionaryIsTheColumnOverlaidBySettings() {
        TargetEndpointViews views = new JdbcTargetEndpointViews(JdbcClient.create(owner), () -> Optional.of(TENANT));

        TargetEndpointView view = asTenant(TENANT, () -> views.byEndpointId(endpoint(1))).orElseThrow();

        assertThat(view.errorDictionary()).containsEntry(503, OutcomeClass.TRANSIENT)
                .containsEntry(502, OutcomeClass.TRANSIENT).containsEntry(418, OutcomeClass.PERMANENT).hasSize(3);
        assertThat(asTenant(TENANT, () -> views.byEndpointId(endpoint(2))).orElseThrow().errorDictionary()).isEmpty();
    }
}
