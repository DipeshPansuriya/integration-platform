package io.integrationplatform.delivery.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T190 (review A blocker): V2_4_1370 only ADDs the CHECK NOT VALID and V2_4_1400 validates it in its own Flyway
 * transaction - on a database that ALREADY holds delivery rows. The validation takes only SHARE UPDATE EXCLUSIVE (a
 * concurrent writer is not blocked), and a leftover violator aborts the migration with a clear message instead of
 * leaving a NOT VALID constraint that would fail the violator's next UPDATE.
 */
class DeliveryContractPinMigrationContainerTest {

    private static final String OWNER = "ipaas_migrator";
    private static final String OWNER_PASSWORD = "owner-" + UUID.randomUUID();
    private static final UUID TENANT = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    private static final UUID MAPPING = UUID.fromString("33333333-0000-4000-8000-000000000003");
    private static final UUID CONTRACT = UUID.fromString("44444444-0000-4000-8000-000000000004");

    private PostgreSQLContainer container;

    @BeforeEach
    void start() throws SQLException {
        container = new PostgreSQLContainer("postgres:17.11-alpine");
        container.start();
        try (Connection admin = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(),
                container.getPassword()); Statement sql = admin.createStatement()) {
            sql.execute("CREATE ROLE " + OWNER + " LOGIN CREATEROLE PASSWORD '" + OWNER_PASSWORD + "'");
            sql.execute("ALTER DATABASE " + container.getDatabaseName() + " OWNER TO " + OWNER);
        }
        flyway("2.4.1300").migrate();
        try (Connection owner = owner(); Statement sql = owner.createStatement()) {
            sql.execute("insert into public.tenant (id, code, status, time_zone) values ('" + TENANT
                    + "', 'tenant-a', 'ACTIVE', 'Etc/UTC')");
        }
    }

    @AfterEach
    void stop() {
        container.stop();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(container.getJdbcUrl(), OWNER, OWNER_PASSWORD)
                .locations("classpath:db/migration").target(target).load();
    }

    private Connection owner() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), OWNER, OWNER_PASSWORD);
    }

    private void insertDelivery(Connection connection, UUID mapping, UUID contract) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("insert into public.delivery (id, tenant_id, state, created_at, updated_at, target_id,"
                    + " mapping_version_id, contract_version_id, message_id, route_id) values ('" + UUID.randomUUID()
                    + "', '" + TENANT + "', 'PENDING', now(), now(), gen_random_uuid(), "
                    + (mapping == null ? "null" : "'" + mapping + "'") + ", "
                    + (contract == null ? "null" : "'" + contract + "'") + ", gen_random_uuid(), gen_random_uuid())");
        }
    }

    private boolean validated() throws SQLException {
        try (Connection owner = owner(); Statement sql = owner.createStatement();
                ResultSet rs = sql.executeQuery("select convalidated from pg_constraint"
                        + " where conname = 'delivery_contract_pin_check' and conrelid = 'public.delivery'::regclass")) {
            assertThat(rs.next()).as("the constraint exists").isTrue();
            return rs.getBoolean(1);
        }
    }

    @Test
    @DisplayName("V2_4_1370 alone only ADDs the CHECK NOT VALID: existing rows are not scanned, new violators are refused")
    void v1370AddsTheConstraintNotValidWithoutScanning() throws Exception {
        try (Connection owner = owner()) {
            insertDelivery(owner, MAPPING, null); // an existing violator: an add that scanned would fail here
        }

        flyway("2.4.1370").migrate();

        assertThat(validated()).as("V2_4_1370 leaves the validation to V2_4_1400").isFalse();
        try (Connection owner = owner()) {
            assertThatThrownBy(() -> insertDelivery(owner, MAPPING, null))
                    .hasMessageContaining("delivery_contract_pin_check");
        }
    }

    @Test
    @DisplayName("V2_4_1400 on a database with existing (compliant) rows validates the constraint")
    void v1400ValidatesAnExistingCompliantTable() throws Exception {
        try (Connection owner = owner()) {
            insertDelivery(owner, MAPPING, CONTRACT);
            insertDelivery(owner, null, null); // a mappingless route is allowed to have no pin
        }

        flyway("2.4.1400").migrate();

        assertThat(validated()).isTrue();
    }

    @Test
    @DisplayName("a leftover violator ABORTS V2_4_1400 with a clear message; after the repair the migration runs again")
    void aViolatorAbortsTheValidationAndTheRepairLetsItThrough() throws Exception {
        try (Connection owner = owner()) {
            insertDelivery(owner, MAPPING, null);
        }
        flyway("2.4.1370").migrate();

        assertThatThrownBy(() -> flyway("2.4.1400").migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("delivery_contract_pin_check").hasMessageContaining("contract version");
        assertThat(validated()).as("still NOT VALID; nothing half-applied").isFalse();

        try (Connection owner = owner(); Statement sql = owner.createStatement()) {
            // the sticking behaviour the abort prevents: a NOT VALID leftover violator fails its next UPDATE
            assertThatThrownBy(() -> sql.execute("update public.delivery set state = 'SENDING'"))
                    .hasMessageContaining("delivery_contract_pin_check");
            sql.execute("update public.delivery set mapping_version_id = null");
        }
        flyway("2.4.1400").migrate();
        assertThat(validated()).isTrue();
    }

    @Test
    @DisplayName("the validation holds only SHARE UPDATE EXCLUSIVE on delivery: no ACCESS EXCLUSIVE, a writer is not blocked")
    void theValidationDoesNotBlockAWriter() throws Exception {
        try (Connection owner = owner()) {
            insertDelivery(owner, MAPPING, CONTRACT);
        }
        flyway("2.4.1370").migrate();
        String body;
        try (var in = getClass().getResourceAsStream("/db/migration/V2_4_1400__delivery_contract_pin_validate.sql")) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        try (Connection validator = owner(); Connection writer = owner()) {
            validator.setAutoCommit(false); // like Flyway: the whole file in one transaction, not yet committed
            try (Statement sql = validator.createStatement()) {
                sql.execute(body);
            }
            List<String> modes = new ArrayList<>();
            try (Statement sql = writer.createStatement(); ResultSet rs = sql.executeQuery(
                    "select mode from pg_locks where relation = 'public.delivery'::regclass and granted"
                            + " and pid = " + backendPid(validator))) {
                while (rs.next()) {
                    modes.add(rs.getString(1));
                }
            }
            assertThat(modes).contains("ShareUpdateExclusiveLock").doesNotContain("AccessExclusiveLock");
            try (Statement sql = writer.createStatement()) {
                sql.execute("set lock_timeout = '3s'"); // a blocked insert would time out and fail the test
            }
            insertDelivery(writer, MAPPING, CONTRACT);
            validator.commit();
        }
        assertThat(validated()).isTrue();
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement(); ResultSet rs = sql.executeQuery("select pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
