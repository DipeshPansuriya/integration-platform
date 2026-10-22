package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E16 F3 (design note task 9) against a real PostgreSQL: the publish-time column check reads the interface's ACTIVE
 * DB routes' live mapping and the pinned target contract version's JSON-schema properties - a mapped column outside
 * the contract answers as missing (named {@code systemCode.table.column}), a mapping with no pinned version carries
 * no columns so every mapped column reads as missing (never "present"), and a route of a system that is not a DB
 * target (no table in the settings) is ignored. Runs through the real {@link JdbcDbRouteColumns} over
 * {@link JdbcClient} with the tenant stamped into the session like the platform does.
 *
 * <p>Authored by arjun-fullstack while the Docker lock was held elsewhere: NOT RUN when committed. The seeds follow
 * the published column lists (V2_2_1, V2_2_2, V2_2_3, V2_2_730); the container-proof lane that runs these may need
 * one pass over NOT NULL columns the migrations add beyond the lists shown here.
 */
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DbRouteColumnsContainerTest {

    private PostgreSQLContainer postgres;
    private JdbcDbRouteColumns columns;
    private TransactionTemplate template;
    private final java.util.Map<String, UUID> interfaces = new java.util.LinkedHashMap<>();

    private final UUID tenant = UUID.randomUUID();
    private final UUID dbSystem = UUID.randomUUID();
    private final UUID apiSystem = UUID.randomUUID();

    @BeforeAll
    void migrateAndSeed() throws Exception {
        postgres = new PostgreSQLContainer("postgres:17.11-alpine");
        postgres.start();
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        List<String> locations = new ArrayList<>();
        for (String module : List.of("platform-tenancy", "platform-config")) {
            Path folder = basedir.getParent().resolve(module + "/src/main/resources/db/migration");
            if (Files.isDirectory(folder)) {
                locations.add("filesystem:" + folder.toString().replace('\\', '/'));
            }
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new)).baselineOnMigrate(true).baselineVersion("0")
                .load().migrate();

        javax.sql.DataSource dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        columns = new JdbcDbRouteColumns(JdbcClient.create(dataSource), () -> Optional.of(tenant));
        template = new TransactionTemplate(new JdbcTransactionManager(dataSource));

        UUID approved;
        try (Connection owner = owner()) {
            tenant(owner, tenant);
            system(owner, tenant, dbSystem, "DB", "{\"host\":\"db.partner.example\",\"port\":5432,"
                    + "\"database\":\"orders\",\"user\":\"restricted\",\"table\":\"orders_in\"}");
            system(owner, tenant, apiSystem, "API", "{\"baseUrl\":\"https://api.example/v1\"}");
            UUID dbEndpoint = endpoint(owner, tenant, dbSystem, "db-ep");
            UUID apiEndpoint = endpoint(owner, tenant, apiSystem, "api-ep");
            UUID contract = contract(owner, tenant, dbSystem, "orders-contract");
            approved = contractVersion(owner, tenant, contract, "{\"properties\":{\"awb\":{\"type\":\"string\"}}}");
            interfaces.put("in-contract", interfaceRow(owner, tenant, dbEndpoint, "in-contract"));
            mapping(owner, interfaces.get("in-contract"), "[{\"target\":\"awb\"}]", approved);
            interfaces.put("outside-contract", interfaceRow(owner, tenant, dbEndpoint, "outside-contract"));
            mapping(owner, interfaces.get("outside-contract"),
                    "[{\"target\":\"awb\"},{\"target\":\"tracking_no.sub\"}]", approved);
            interfaces.put("no-pinned-version", interfaceRow(owner, tenant, dbEndpoint, "no-pinned-version"));
            mapping(owner, interfaces.get("no-pinned-version"), "[{\"target\":\"awb\"}]", null);
            interfaces.put("not-a-db-target", interfaceRow(owner, tenant, apiEndpoint, "not-a-db-target"));
            mapping(owner, interfaces.get("not-a-db-target"), "[{\"target\":\"awb\"}]", approved);
        }
    }

    @AfterAll
    void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("every mapped column in the approved contract: nothing missing")
    void mappedColumnsInTheContractArePresent() {
        assertThat(missing("in-contract")).isEmpty();
    }

    @Test
    @DisplayName("a mapped column outside the contract answers as systemCode.table.column")
    void aMappedColumnOutsideTheContractAnswersNamed() {
        assertThat(missing("outside-contract"))
                .containsExactly("sys-" + dbSystem.toString().substring(0, 8) + ".orders_in.tracking_no");
    }

    @Test
    @DisplayName("a mapping with no pinned target contract version: every mapped column reads as missing")
    void aMappingWithoutPinnedVersionReadsEveryColumnMissing() {
        assertThat(missing("no-pinned-version"))
                .containsExactly("sys-" + dbSystem.toString().substring(0, 8) + ".orders_in.awb");
    }

    @Test
    @DisplayName("a system that is not a DB target (no table in the settings) is ignored")
    void aNonDbRouteIsIgnored() {
        assertThat(missing("not-a-db-target")).isEmpty();
    }

    private Connection owner() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void tenant(Connection owner, UUID id) throws SQLException {
        try (PreparedStatement sql = owner.prepareStatement(
                "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
            sql.setString(1, id.toString());
            sql.setString(2, "t-" + id.toString().substring(0, 8));
            sql.executeUpdate();
        }
    }

    private void system(Connection owner, UUID tenantId, UUID id, String type, String settings) throws SQLException {
        try (PreparedStatement sql = owner.prepareStatement("INSERT INTO public.system (id, tenant_id, code, name,"
                + " direction, type, settings) VALUES (?::uuid, ?::uuid, ?, 'n', 'TARGET', ?, ?::jsonb)")) {
            sql.setString(1, id.toString());
            sql.setString(2, tenantId.toString());
            sql.setString(3, "sys-" + id.toString().substring(0, 8));
            sql.setString(4, type);
            sql.setString(5, settings);
            sql.executeUpdate();
        }
    }

    private UUID endpoint(Connection owner, UUID tenantId, UUID system, String code) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement sql = owner.prepareStatement("INSERT INTO public.target_endpoint (id, tenant_id,"
                + " system_id, code, name, base_url, http_method, auth_method, timeout_ms)"
                + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'n', 'https://x.example', 'POST', 'NO_AUTH', 30000)")) {
            sql.setString(1, id.toString());
            sql.setString(2, tenantId.toString());
            sql.setString(3, system.toString());
            sql.setString(4, code);
            sql.executeUpdate();
        }
        return id;
    }

    private UUID interfaceRow(Connection owner, UUID tenantId, UUID endpoint, String code) throws SQLException {
        UUID ifaceId = UUID.randomUUID();
        try (PreparedStatement sql = owner.prepareStatement("INSERT INTO public.interface (id, tenant_id, code, name)"
                + " VALUES (?::uuid, ?::uuid, ?, 'n')")) {
            sql.setString(1, ifaceId.toString());
            sql.setString(2, tenantId.toString());
            sql.setString(3, code);
            sql.executeUpdate();
        }
        try (PreparedStatement sql = owner.prepareStatement("INSERT INTO public.route (id, tenant_id, interface_id,"
                + " target_endpoint_id, sequence, status) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 1, 'ACTIVE')")) {
            sql.setString(1, UUID.randomUUID().toString());
            sql.setString(2, tenantId.toString());
            sql.setString(3, ifaceId.toString());
            sql.setString(4, endpoint.toString());
            sql.executeUpdate();
        }
        return ifaceId;
    }

    private UUID contract(Connection owner, UUID tenantId, UUID system, String code) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement sql = owner.prepareStatement("INSERT INTO public.contract (id, tenant_id, system_id,"
                + " code, role, name) VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'TARGET_REQUEST', 'n')")) {
            sql.setString(1, id.toString());
            sql.setString(2, tenantId.toString());
            sql.setString(3, system.toString());
            sql.setString(4, code);
            sql.executeUpdate();
        }
        return id;
    }

    private UUID contractVersion(Connection owner, UUID tenantId, UUID contractId, String jsonSchema)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement sql = owner.prepareStatement("INSERT INTO public.contract_version (id, tenant_id,"
                + " contract_id, version_label, status, author_id, json_schema_draft_2020_12)"
                + " VALUES (?::uuid, ?::uuid, ?::uuid, '1.0', 'APPROVED', gen_random_uuid(), ?::jsonb)")) {
            sql.setString(1, id.toString());
            sql.setString(2, tenantId.toString());
            sql.setString(3, contractId.toString());
            sql.setString(4, jsonSchema);
            sql.executeUpdate();
        }
        return id;
    }

    private void mapping(Connection owner, UUID iface, String fieldMappings, UUID targetVersion) throws SQLException {
        try (PreparedStatement sql = owner.prepareStatement("INSERT INTO public.mapping (id, tenant_id, route_id,"
                + " field_mappings, compiled_expression, target_contract_version_id)"
                + " VALUES (?::uuid, ?::uuid, (select r.id from public.route r where r.interface_id = ?::uuid),"
                + " ?::jsonb, '', ?::uuid)")) {
            sql.setString(1, UUID.randomUUID().toString());
            sql.setString(2, tenant.toString());
            sql.setString(3, iface.toString());
            sql.setString(4, fieldMappings);
            sql.setString(5, targetVersion == null ? null : targetVersion.toString());
            sql.executeUpdate();
        }
    }

    /** The read under the tenant-stamped transaction, exactly what the publish listener runs in. */
    private List<String> missing(String interfaceCode) {
        UUID iface = interfaces.get(interfaceCode);
        return template.execute(status -> {
            new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())).execute(
                    (java.sql.Connection connection) -> {
                        try (java.sql.PreparedStatement stamp = connection.prepareStatement(
                                "select set_config('app.tenant_id', ?, true)")) {
                            stamp.setString(1, tenant.toString());
                            stamp.execute();
                        }
                        return null;
                    });
            return columns.ofInterface(iface);
        });
    }
}