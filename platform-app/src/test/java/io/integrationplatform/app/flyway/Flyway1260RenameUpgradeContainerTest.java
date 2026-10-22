package io.integrationplatform.app.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.TestImages;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Upgrade safety of commit 4f92966, which renamed {@code V2_4_1260__force_rls_route_mapping_system_target.sql} to
 * {@code V2_4_1261} (two migrations had claimed 2.4.1260; the kafka-topic-settings one kept it). A fresh database is
 * unaffected; the risk is a database that already applied the OLD file. For two hours on 2026-10-21 (main
 * da69e9c..3ec65bb) that was the only 2.4.1260 on main.
 *
 * <p>The legacy database is built by running the CURRENT tree's migrations with exactly one change: the force-RLS
 * file is offered under its old version (identical bytes, so identical checksum - 4f92966 was a pure rename) and the
 * kafka file is withheld, as on main in that window. No real database is touched; the repair script under test is
 * {@code documents/ops/flyway-2.4.1260-repair.sql}.
 */
@Tag("container")
@Testcontainers
class Flyway1260RenameUpgradeContainerTest {

    private static final String LEGACY_FILE = "V2_4_1260__force_rls_route_mapping_system_target.sql";
    private static final String RENAMED_FILE = "V2_4_1261__force_rls_route_mapping_system_target.sql";
    private static final String KAFKA_FILE = "V2_4_1260__kafka_topic_settings.sql";
    private static final List<String> K1_TABLES = List.of("route", "mapping", "system", "target_endpoint");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Test
    void currentTreeOnADatabaseThatAppliedTheOldFileIsRefusedByValidate() throws Exception {
        String url = legacyDatabase("legacy_refused", false);

        assertThatThrownBy(() -> current(url).migrate())
                .isInstanceOf(FlywayValidateException.class)
                .satisfies(e -> System.out.println("FLYWAY-1260-ERROR>>>" + e.getMessage() + "<<<"))
                .hasMessageContaining("2.4.1260")
                .hasMessageMatching("(?s).*(checksum|description) mismatch.*");
    }

    @Test
    void theRepairScriptLetsTheCurrentTreeMigrateToTheFreshSchema() throws Exception {
        String url = legacyDatabase("legacy_repaired", false);

        int deleted = runRepairScript(url);
        current(url).migrate();

        assertThat(deleted).isEqualTo(1);
        current(url).validate();
        String fresh = freshDatabase("fresh_parity");
        assertThat(historyIdentity(url)).isEqualTo(historyIdentity(fresh));
        assertThat(tables(url)).isEqualTo(tables(fresh)).contains("kafka_topic_setting");
        for (String table : K1_TABLES) {
            assertThat(forceRls(url, table)).as("FORCE RLS on %s", table).isTrue();
        }
    }

    @Test
    void theRepairScriptIsIdempotentAndDoesNothingOnAFreshDatabase() throws Exception {
        String legacy = legacyDatabase("legacy_twice", false);
        assertThat(runRepairScript(legacy)).isEqualTo(1);
        assertThat(runRepairScript(legacy)).isZero();

        String fresh = freshDatabase("fresh_noop");
        List<String> before = historyIdentity(fresh);
        assertThat(runRepairScript(fresh)).isZero();
        assertThat(historyIdentity(fresh)).isEqualTo(before);
        current(fresh).validate();
    }

    @Test
    void aBlindFlywayRepairIsTheWrongFixBecauseItSilentlyDropsTheKafkaTable() throws Exception {
        String url = legacyDatabase("legacy_blind_repair", false);

        current(url).repair();
        current(url).migrate();

        // validate is green, history says kafka applied - and the table was never created.
        current(url).validate();
        assertThat(tables(url)).doesNotContain("kafka_topic_setting");
    }

    @Test
    void aLegacyDatabaseAlreadyAheadOfTheClashNeedsOutOfOrderForOneRunAfterTheRepair() throws Exception {
        String url = legacyDatabase("legacy_ahead", true);
        assertThat(runRepairScript(url)).isEqualTo(1);

        assertThatThrownBy(() -> current(url).migrate())
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("2.4.1260");

        Flyway.configure().configuration(current(url).getConfiguration()).outOfOrder(true).load().migrate();
        current(url).validate();
        assertThat(tables(url)).contains("kafka_topic_setting");
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static Flyway current(String url) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    /**
     * Migrates a new database with the old tree. {@code ahead} keeps migrations later than 2.4.1260 (a database that
     * also took, say, 2.4.1300); otherwise the database stops at the old 2.4.1260, as every database in the window did.
     */
    private static String legacyDatabase(String name, boolean ahead) throws Exception {
        String url = newDatabase(name);
        Path dir = Files.createTempDirectory("legacy-migrations");
        Resource[] all = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql");
        int copied = 0;
        for (Resource resource : all) {
            String file = resource.getFilename();
            if (file == null || KAFKA_FILE.equals(file)) {
                continue;
            }
            if (!ahead && !RENAMED_FILE.equals(file) && later(file)) {
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                Files.write(dir.resolve(RENAMED_FILE.equals(file) ? LEGACY_FILE : file), in.readAllBytes());
            }
            copied++;
        }
        assertThat(Files.exists(dir.resolve(LEGACY_FILE))).as("legacy file staged").isTrue();
        assertThat(copied).as("legacy migration count").isGreaterThan(30);
        Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + dir.toAbsolutePath())
                .load()
                .migrate();
        return url;
    }

    /** True for a migration whose version is above 2.4.1260 (the repair precondition: the old row is the maximum). */
    private static boolean later(String file) {
        String[] v = file.substring(1, file.indexOf("__")).split("_");
        long[] have = {Long.parseLong(v[0]), Long.parseLong(v[1]), v.length > 2 ? Long.parseLong(v[2]) : 0};
        long[] limit = {2, 4, 1260};
        for (int i = 0; i < 3; i++) {
            if (have[i] != limit[i]) {
                return have[i] > limit[i];
            }
        }
        return false;
    }

    private static String freshDatabase(String name) throws Exception {
        String url = newDatabase(name);
        current(url).migrate();
        return url;
    }

    private static String newDatabase(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
    }

    private static int runRepairScript(String url) throws IOException, SQLException {
        Path script = repoRoot().resolve("documents/ops/flyway-2.4.1260-repair.sql");
        String sql = Files.readString(script, StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = c.createStatement()) {
            return s.executeUpdate(sql);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("documents"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repo root holding documents/").isNotNull();
        return dir;
    }

    private static List<String> historyIdentity(String url) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT version, description, checksum FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY string_to_array(version, '.')::int[]")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
            }
        }
        return rows;
    }

    private static Set<String> tables(String url) throws SQLException {
        Set<String> names = new TreeSet<>();
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static boolean forceRls(String url, String table) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT relforcerowsecurity FROM pg_class WHERE oid = 'public." + table + "'::regclass")) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
