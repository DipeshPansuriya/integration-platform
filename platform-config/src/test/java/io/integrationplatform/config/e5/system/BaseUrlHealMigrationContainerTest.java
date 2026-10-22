package io.integrationplatform.config.e5.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Review A-C1 against a real PostgreSQL: V2_4_1350 heals the endpoints of API systems saved before the base-URL fix
 * (they still hold {@code https://endpoint.invalid/} although {@code settings.baseUrl} is set). The database is
 * migrated to 2.4.1340, seeded with the legacy shapes, then migrated on; nothing else may change, and running the
 * statement again changes nothing. The migration runs no destination guard (no Java): that guard still runs at SEND
 * time on every call, and the save-time check already ran when the settings were saved.
 */
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BaseUrlHealMigrationContainerTest {

    private static final String PLACEHOLDER = "https://endpoint.invalid/";
    private static final String HEAL_FILE = "V2_4_1350__heal_placeholder_api_endpoint_base_url.sql";

    private PostgreSQLContainer postgres;
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID legacyA = UUID.randomUUID();
    private final UUID legacyB = UUID.randomUUID();
    private final UUID spacedA = UUID.randomUUID();
    private final UUID customised = UUID.randomUUID();
    private final UUID inbound = UUID.randomUUID();
    private final UUID dbSystem = UUID.randomUUID();
    private final UUID untyped = UUID.randomUUID();
    private final UUID noBaseUrl = UUID.randomUUID();
    private final UUID blankBaseUrl = UUID.randomUUID();
    private final UUID nonStringBaseUrl = UUID.randomUUID();
    private final UUID nearPlaceholder = UUID.randomUUID();
    private Path migrationDir;

    @BeforeAll
    void migrateSeedAndMigrateOn() throws Exception {
        postgres = new PostgreSQLContainer("postgres:17.11-alpine");
        postgres.start();
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        List<String> locations = new ArrayList<>();
        for (Path folder : List.of(basedir.getParent().resolve("platform-tenancy/src/main/resources/db/migration"),
                basedir.resolve("src/main/resources/db/migration"))) {
            if (Files.isDirectory(folder)) {
                locations.add("filesystem:" + folder.toString().replace('\\', '/'));
            }
        }
        migrationDir = basedir.resolve("src/main/resources/db/migration");
        flyway(locations).target("2.4.1340").load().migrate();

        try (Connection owner = owner()) {
            tenant(owner, tenantA, "heal-a");
            tenant(owner, tenantB, "heal-b");
            seed(owner, tenantA, legacyA, "TARGET", "API", "{\"baseUrl\":\"https://one.partner.example/v1/orders\"}",
                    PLACEHOLDER);
            seed(owner, tenantB, legacyB, "TARGET", "API", "{\"baseUrl\":\"https://two.partner.example/v2\"}",
                    PLACEHOLDER);
            seed(owner, tenantA, spacedA, "TARGET", "API", "{\"baseUrl\":\"  https://padded.partner.example/p  \"}",
                    PLACEHOLDER);
            seed(owner, tenantA, customised, "TARGET", "API", "{\"baseUrl\":\"https://settings.partner.example/\"}",
                    "https://edited.partner.example/own");
            seed(owner, tenantA, inbound, "SOURCE", "API", "{\"baseUrl\":\"https://in.partner.example/\"}",
                    PLACEHOLDER);
            seed(owner, tenantA, dbSystem, "TARGET", "DB", "{\"baseUrl\":\"https://db.partner.example/\"}",
                    PLACEHOLDER);
            seed(owner, tenantA, untyped, "TARGET", null, null, PLACEHOLDER);
            seed(owner, tenantA, noBaseUrl, "TARGET", "API", "{\"timeoutMs\":3000}", PLACEHOLDER);
            seed(owner, tenantA, blankBaseUrl, "TARGET", "API", "{\"baseUrl\":\"   \"}", PLACEHOLDER);
            seed(owner, tenantA, nonStringBaseUrl, "TARGET", "API", "{\"baseUrl\":42}", PLACEHOLDER);
            seed(owner, tenantA, nearPlaceholder, "TARGET", "API", "{\"baseUrl\":\"https://near.partner.example/\"}",
                    "https://endpoint.invalid");
        }
        flyway(locations).load().migrate();
    }

    @AfterAll
    void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("an OUTBOUND API system still on the placeholder takes its settings.baseUrl (trimmed), in its own tenant")
    void legacyPlaceholderHeals() throws Exception {
        assertThat(base(legacyA)).isEqualTo("https://one.partner.example/v1/orders");
        assertThat(base(legacyB)).as("another tenant's system takes its own settings, never a neighbour's")
                .isEqualTo("https://two.partner.example/v2");
        assertThat(base(spacedA)).isEqualTo("https://padded.partner.example/p");
    }

    @Test
    @DisplayName("everything else is untouched: a non-placeholder address, INBOUND, non-API, untyped, missing, blank or"
            + " non-text baseUrl, and a near-miss of the placeholder")
    void everythingElseIsUntouched() throws Exception {
        assertThat(base(customised)).isEqualTo("https://edited.partner.example/own");
        assertThat(base(inbound)).isEqualTo(PLACEHOLDER);
        assertThat(base(dbSystem)).isEqualTo(PLACEHOLDER);
        assertThat(base(untyped)).isEqualTo(PLACEHOLDER);
        assertThat(base(noBaseUrl)).isEqualTo(PLACEHOLDER);
        assertThat(base(blankBaseUrl)).isEqualTo(PLACEHOLDER);
        assertThat(base(nonStringBaseUrl)).isEqualTo(PLACEHOLDER);
        assertThat(base(nearPlaceholder)).isEqualTo("https://endpoint.invalid");
    }

    @Test
    @DisplayName("running the migration statement again changes nothing")
    void runningTwiceChangesNothing() throws Exception {
        String before = snapshot();
        try (Connection owner = owner(); Statement sql = owner.createStatement()) {
            sql.execute(Files.readString(migrationDir.resolve(HEAL_FILE)));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    private String snapshot() throws SQLException {
        return scalar("SELECT string_agg(system_id::text || '=' || base_url, ',' ORDER BY system_id)"
                + " FROM public.target_endpoint");
    }

    private String base(UUID system) throws SQLException {
        return scalar("SELECT string_agg(base_url, ',') FROM public.target_endpoint WHERE system_id = '" + system + "'");
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(List<String> locations) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new)).baselineOnMigrate(true).baselineVersion("0");
    }

    private Connection owner() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private String scalar(String sql) throws SQLException {
        try (Connection owner = owner(); Statement statement = owner.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void tenant(Connection owner, UUID id, String code) throws SQLException {
        try (PreparedStatement sql = owner.prepareStatement(
                "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
            sql.setString(1, id.toString());
            sql.setString(2, code + "-" + id.toString().substring(0, 8));
            sql.executeUpdate();
        }
    }

    private static void seed(Connection owner, UUID tenant, UUID system, String direction, String type,
            String settings, String baseUrl) throws SQLException {
        String suffix = system.toString().substring(0, 8);
        try (PreparedStatement sql = owner.prepareStatement("INSERT INTO public.system (id, tenant_id, code, name,"
                + " direction, type, settings) VALUES (?::uuid, ?::uuid, ?, 'n', ?, ?, ?::jsonb)")) {
            sql.setString(1, system.toString());
            sql.setString(2, tenant.toString());
            sql.setString(3, "sys-" + suffix);
            sql.setString(4, direction);
            sql.setString(5, type);
            sql.setString(6, settings);
            sql.executeUpdate();
        }
        try (PreparedStatement sql = owner.prepareStatement("INSERT INTO public.target_endpoint (tenant_id, system_id,"
                + " code, name, base_url, http_method, auth_method, timeout_ms) VALUES (?::uuid, ?::uuid, ?, 'e', ?,"
                + " 'POST', 'NO_AUTH', 5000)")) {
            sql.setString(1, tenant.toString());
            sql.setString(2, system.toString());
            sql.setString(3, "ep-" + suffix);
            sql.setString(4, baseUrl);
            sql.executeUpdate();
        }
    }
}
