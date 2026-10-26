package io.integrationplatform.routing.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.delivery.replay.ActiveTargetContracts;
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
 * T172 on the real schema: the active contract of a target endpoint is the newest APPROVED TARGET_REQUEST version of
 * its system's contract - not a draft, not a source-role contract, never another tenant's.
 */
@Tag("container")
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class ActiveTargetContractsContainerTest {

    private static final UUID TENANT = UUID.fromString("eeee1720-0000-0000-0000-00000000000a");
    private static final UUID OTHER_TENANT = UUID.fromString("eeee1720-0000-0000-0000-00000000000b");
    private static final UUID SYSTEM = UUID.fromString("eeee1720-0000-0000-0000-000000000100");
    private static final UUID BARE_SYSTEM = UUID.fromString("eeee1720-0000-0000-0000-000000000101");
    private static final UUID ENDPOINT = UUID.fromString("eeee1720-0000-0000-0000-000000000200");
    private static final UUID BARE_ENDPOINT = UUID.fromString("eeee1720-0000-0000-0000-000000000201");
    private static final UUID NEWEST = UUID.fromString("eeee1720-0000-0000-0000-000000000302");

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
                run(sql, "insert into public.tenant (id, code, status, time_zone) values (?, ?, 'ACTIVE', 'Etc/UTC')",
                        tenant, "t-172-" + code++);
            }
            for (UUID[] sys : new UUID[][] {{SYSTEM, ENDPOINT}, {BARE_SYSTEM, BARE_ENDPOINT}}) {
                run(sql, "insert into public.system (id, tenant_id, code, name, direction, type, settings) values"
                        + " (?, ?, ?, 'Target', 'TARGET', 'API', '{}'::jsonb)", sys[0], TENANT, "sys-" + sys[0]);
                run(sql, "insert into public.target_endpoint (id, tenant_id, system_id, code, name, base_url,"
                        + " http_method, auth_method, timeout_ms) values (?, ?, ?, ?, 'Endpoint', 'https://x.example/o',"
                        + " 'POST', 'NO_AUTH', 1500)", sys[1], TENANT, sys[0], "ep-" + sys[1]);
            }
            UUID target = UUID.fromString("eeee1720-0000-0000-0000-000000000400");
            UUID source = UUID.fromString("eeee1720-0000-0000-0000-000000000401");
            run(sql, "insert into public.contract (id, tenant_id, system_id, code, role, name) values"
                    + " (?, ?, ?, 'tgt', 'TARGET_REQUEST', 'T')", target, TENANT, SYSTEM);
            run(sql, "insert into public.contract (id, tenant_id, system_id, code, role, name) values"
                    + " (?, ?, ?, 'src', 'SOURCE_REQUEST', 'S')", source, TENANT, SYSTEM);
            version(sql, "eeee1720-0000-0000-0000-000000000301", target, "1.0", "APPROVED", "2026-10-01T00:00:00Z");
            version(sql, NEWEST.toString(), target, "2.0", "APPROVED", "2026-10-02T00:00:00Z");
            version(sql, "eeee1720-0000-0000-0000-000000000303", target, "3.0", "DRAFT", "2026-10-03T00:00:00Z");
            version(sql, "eeee1720-0000-0000-0000-000000000304", source, "9.0", "APPROVED", "2026-10-04T00:00:00Z");
        }
    }

    private static void version(Connection sql, String id, UUID contract, String label, String status, String at)
            throws SQLException {
        run(sql, "insert into public.contract_version (id, tenant_id, contract_id, version_label, status,"
                + " json_schema_draft_2020_12, author_id, approver_id, created_at) values"
                + " (?::uuid, ?, ?, ?, ?, '{}'::jsonb, gen_random_uuid(), gen_random_uuid(), ?::timestamptz)",
                id, TENANT, contract, label, status, at);
    }

    private static void run(Connection sql, String statement, Object... args) throws SQLException {
        try (PreparedStatement insert = sql.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) {
                insert.setObject(i + 1, args[i]);
            }
            insert.executeUpdate();
        }
    }

    private <T> T asTenant(UUID tenant, Supplier<T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(owner)).execute(status -> {
            JdbcClient.create(owner).sql("select set_config('app.tenant_id', :t, true)")
                    .param("t", tenant.toString()).query().singleRow();
            return work.get();
        });
    }

    private ActiveTargetContracts contracts(UUID tenant) {
        return new JdbcActiveTargetContracts(JdbcClient.create(owner), () -> Optional.of(tenant));
    }

    @Test
    @DisplayName("the newest APPROVED TARGET_REQUEST version of the endpoint's system is the active contract")
    void newestApprovedTargetRequestVersion() {
        assertThat(asTenant(TENANT, () -> contracts(TENANT).activeContractVersionOf(ENDPOINT))).contains(NEWEST);
    }

    @Test
    @DisplayName("an endpoint whose system has no approved target contract, and an unknown endpoint, resolve to nothing")
    void noActiveContract() {
        assertThat(asTenant(TENANT, () -> contracts(TENANT).activeContractVersionOf(BARE_ENDPOINT))).isEmpty();
        assertThat(asTenant(TENANT, () -> contracts(TENANT).activeContractVersionOf(UUID.randomUUID()))).isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("another tenant's endpoint resolves to nothing")
    void anotherTenantsEndpointIsNotResolved() {
        assertThat(asTenant(OTHER_TENANT, () -> contracts(OTHER_TENANT).activeContractVersionOf(ENDPOINT))).isEmpty();
    }
}
