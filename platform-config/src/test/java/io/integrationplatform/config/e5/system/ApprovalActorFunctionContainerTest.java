package io.integrationplatform.config.e5.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Row 544 (option A, P1) against a real PostgreSQL: after V2_4_1500 the approver of a flow version or a contract
 * version is the session actor the application stamped ({@code app.actor_id}), exactly as the DB-table approval reads
 * it. A stamped actor is recorded; no actor is refused ({@code REFUSED_NO_ACTOR}) and so is a stamp that disagrees
 * with the argument ({@code REFUSED_ACTOR_MISMATCH}), both with no state change and no refusal row; the existing
 * verdicts are unchanged.
 *
 */
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovalActorFunctionContainerTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PostgreSQLContainer postgres;
    private String appUser;
    private String appPassword;
    private final UUID tenant = UUID.randomUUID();
    private final UUID author = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID system = UUID.randomUUID();
    private final UUID contract = UUID.randomUUID();
    private final UUID flow = UUID.randomUUID();
    private final UUID sourceContractVersion = UUID.randomUUID();

    @BeforeAll
    void migrateAndSeed() throws Exception {
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
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new)).baselineOnMigrate(true).baselineVersion("0").load()
                .migrate();

        appUser = "app_login_" + Long.toUnsignedString(RANDOM.nextLong(), 36);
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        appPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        try (Connection owner = owner(); Statement sql = owner.createStatement()) {
            sql.execute("CREATE ROLE \"" + appUser + "\" LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + appPassword + "'");
            sql.execute("GRANT ipaas_app TO \"" + appUser + "\"");
            sql.execute("INSERT INTO public.tenant (id, code, status, time_zone) VALUES ('" + tenant
                    + "', 'row544-approval', 'ACTIVE', 'Etc/UTC')");
            sql.execute("INSERT INTO public.system (id, tenant_id, code, name, direction) VALUES ('" + system + "', '"
                    + tenant + "', 'ERP544', 'ERP', 'BOTH')");
            sql.execute("INSERT INTO public.contract (id, tenant_id, system_id, code, role, name) VALUES ('" + contract
                    + "', '" + tenant + "', '" + system + "', 'C544', 'SOURCE_REQUEST', 'c')");
            sql.execute("INSERT INTO public.interface (id, tenant_id, code, name, mode) VALUES ('" + flow + "', '"
                    + tenant + "', 'F544', 'f', 'ASYNC')");
            insertContractVersion(sql, sourceContractVersion, "1.0");
        }
    }

    @AfterAll
    void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private Connection owner() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void insertContractVersion(Statement sql, UUID id, String label) throws SQLException {
        sql.execute("INSERT INTO public.contract_version (id, tenant_id, contract_id, version_label, status,"
                + " json_schema_draft_2020_12, author_id) VALUES ('" + id + "', '" + tenant + "', '" + contract + "', '"
                + label + "', 'DRAFT', '{\"type\":\"object\"}'::jsonb, '" + author + "')");
        sql.execute("UPDATE public.contract_version SET status = 'PENDING_APPROVAL' WHERE id = '" + id + "'");
    }

    private UUID pendingContractVersion(String label) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = owner(); Statement sql = owner.createStatement()) {
            insertContractVersion(sql, id, label);
        }
        return id;
    }

    private int flowVersionNumber = 0;

    private UUID pendingFlowVersion() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = owner(); Statement sql = owner.createStatement()) {
            sql.execute("INSERT INTO public.interface_version (id, tenant_id, interface_id, version, business_key_rule,"
                    + " source_contract_version_id, author_id, snapshot) VALUES ('" + id + "', '" + tenant + "', '"
                    + flow + "', " + (++flowVersionNumber) + ", 'body.orderId', '" + sourceContractVersion + "', '"
                    + author + "', '{\"interface\":\"F544\"}'::jsonb)");
            sql.execute("UPDATE public.interface_version SET status = 'PENDING_APPROVAL', change_note = 'row544'"
                    + " WHERE id = '" + id + "'");
        }
        return id;
    }

    /** The definer call as the app login, tenant stamped, the actor stamped only when {@code actor} is not null. */
    private String call(String function, UUID actor, UUID versionId, UUID argument) throws SQLException {
        try (Connection app = DriverManager.getConnection(postgres.getJdbcUrl(), appUser, appPassword)) {
            app.setAutoCommit(false);
            try (PreparedStatement stamp = app.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                stamp.setString(1, tenant.toString());
                stamp.execute();
            }
            if (actor != null) {
                try (PreparedStatement stamp = app.prepareStatement("select set_config('app.actor_id', ?, true)")) {
                    stamp.setString(1, actor.toString());
                    stamp.execute();
                }
            }
            try (PreparedStatement call = app.prepareStatement("select public." + function + "(?::uuid, ?::uuid)")) {
                call.setString(1, versionId.toString());
                call.setString(2, argument.toString());
                try (ResultSet rs = call.executeQuery()) {
                    rs.next();
                    String verdict = rs.getString(1);
                    app.commit();
                    return verdict;
                }
            }
        }
    }

    private String scalar(String sql) throws SQLException {
        try (Connection owner = owner(); Statement statement = owner.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    // ---------------------------------------------------------------- flow versions

    @Test
    void aFlowApprovalRecordsTheStampedActorAsApprover() throws SQLException {
        UUID version = pendingFlowVersion();

        assertThat(call("config_approve_version", other, version, other)).isEqualTo("APPROVED");

        assertThat(scalar("select status || '/' || approver_id from public.interface_version where id = '" + version
                + "'")).isEqualTo("APPROVED/" + other);
    }

    @Test
    @Tag("negative")
    void aFlowApprovalWithNoStampedActorIsRefusedAndChangesNothing() throws SQLException {
        UUID version = pendingFlowVersion();

        assertThat(call("config_approve_version", null, version, other)).isEqualTo("REFUSED_NO_ACTOR");

        assertThat(scalar("select status || '/' || coalesce(approver_id::text, 'none') from public.interface_version"
                + " where id = '" + version + "'")).isEqualTo("PENDING_APPROVAL/none");
        assertThat(scalar("select count(*) from public.config_version_refusal where version_id = '" + version + "'"))
                .isEqualTo("0");
    }

    @Test
    @Tag("negative")
    void aFlowApprovalWhoseArgumentDisagreesWithTheStampIsRefusedAndChangesNothing() throws SQLException {
        UUID version = pendingFlowVersion();

        assertThat(call("config_approve_version", author, version, other)).isEqualTo("REFUSED_ACTOR_MISMATCH");

        assertThat(scalar("select status || '/' || coalesce(approver_id::text, 'none') from public.interface_version"
                + " where id = '" + version + "'")).isEqualTo("PENDING_APPROVAL/none");
        assertThat(scalar("select count(*) from public.config_version_refusal where version_id = '" + version + "'"))
                .isEqualTo("0");
    }

    @Test
    void aFlowVersionThatIsNotPendingStaysRefusedAndTheRefusalNamesTheStampedActor() throws SQLException {
        UUID version = pendingFlowVersion();
        assertThat(call("config_approve_version", author, version, author)).isEqualTo("APPROVED");

        assertThat(call("config_approve_version", other, version, other)).isEqualTo("REFUSED_APPROVED");

        assertThat(scalar("select actor_id from public.config_version_refusal where version_id = '" + version + "'"))
                .isEqualTo(other.toString());
    }

    @Test
    void anUnknownFlowVersionIsStillNotFound() throws SQLException {
        assertThat(call("config_approve_version", other, UUID.randomUUID(), other)).isEqualTo("VERSION_NOT_FOUND");
    }

    // ---------------------------------------------------------------- contract versions

    @Test
    void aContractApprovalRecordsTheStampedActorAsApprover() throws SQLException {
        UUID version = pendingContractVersion("2.0");

        assertThat(call("config_approve_contract_version", other, version, other)).isEqualTo("APPROVED");

        assertThat(scalar("select status || '/' || approver_id from public.contract_version where id = '" + version
                + "'")).isEqualTo("APPROVED/" + other);
    }

    @Test
    @Tag("negative")
    void aContractApprovalWithNoStampedActorOrAMismatchIsRefusedAndChangesNothing() throws SQLException {
        UUID version = pendingContractVersion("3.0");

        assertThat(call("config_approve_contract_version", null, version, other)).isEqualTo("REFUSED_NO_ACTOR");
        assertThat(call("config_approve_contract_version", author, version, other))
                .isEqualTo("REFUSED_ACTOR_MISMATCH");

        assertThat(scalar("select status || '/' || coalesce(approver_id::text, 'none') from public.contract_version"
                + " where id = '" + version + "'")).isEqualTo("PENDING_APPROVAL/none");
    }

    @Test
    void aContractVersionThatIsNotPendingAndAnUnknownOneKeepTheirVerdicts() throws SQLException {
        UUID version = pendingContractVersion("4.0");
        assertThat(call("config_approve_contract_version", author, version, author)).isEqualTo("APPROVED");

        assertThat(call("config_approve_contract_version", other, version, other)).isEqualTo("REFUSED_APPROVED");
        assertThat(call("config_approve_contract_version", other, UUID.randomUUID(), other))
                .isEqualTo("VERSION_NOT_FOUND");
    }
}
