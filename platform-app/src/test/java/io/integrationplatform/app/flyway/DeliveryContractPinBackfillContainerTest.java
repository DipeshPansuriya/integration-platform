package io.integrationplatform.app.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.TestImages;
import io.integrationplatform.delivery.replay.DeliveryOperatorActions;
import io.integrationplatform.delivery.replay.OperatorActionStore;
import io.integrationplatform.domain.delivery.DeliveryState;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ApplicationEventPublisher;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Operator replay/retry of a delivery created BEFORE the stage-1 pin fix (1904b6dd): such a row has
 * {@code contract_version_id} NULL and the operator answer is 409 "not pinned". V2_4_1360 backfills the pin from the
 * mapping version the delivery already holds ({@code mapping_version.target_contract_version_id}). The database is
 * migrated to 2.4.1350, seeded with the legacy shapes, healed by running the new file as a NON-superuser table owner
 * (so FORCE row level security binds it exactly as in production), run again, then migrated on by Flyway.
 */
@Tag("container")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryContractPinBackfillContainerTest {

    private static final String BACKFILL_FILE = "db/migration/V2_4_1360__backfill_delivery_contract_version_pin.sql";
    private static final Instant TODAY = Instant.now().truncatedTo(ChronoUnit.DAYS);

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID mappingVersionA1 = UUID.randomUUID();
    private final UUID mappingVersionA2 = UUID.randomUUID();
    private final UUID mappingVersionB = UUID.randomUUID();
    private final UUID contractA1 = UUID.randomUUID();
    private final UUID contractA2 = UUID.randomUUID();
    private final UUID contractB = UUID.randomUUID();
    private final UUID alreadyPinnedContract = UUID.randomUUID();

    private final UUID legacyA1 = UUID.randomUUID();
    private final UUID legacyA1Yesterday = UUID.randomUUID();
    private final UUID legacyA2 = UUID.randomUUID();
    private final UUID legacyB = UUID.randomUUID();
    private final UUID pinnedA = UUID.randomUUID();
    private final UUID noMappingVersion = UUID.randomUUID();
    private final UUID danglingMappingVersion = UUID.randomUUID();
    private final UUID otherTenantsMappingVersion = UUID.randomUUID();

    private PostgreSQLContainer postgres;
    private String snapshotAfterFirstRun;

    @BeforeAll
    void migrateSeedHealAndMigrateOn() throws Exception {
        postgres = new PostgreSQLContainer(TestImages.POSTGRES);
        postgres.start();
        flyway().target("2.4.1350").load().migrate();

        try (Connection owner = connect(); Statement s = owner.createStatement()) {
            s.execute("SET session_replication_role = replica"); // seed without the unrelated parent rows
            tenant(owner, tenantA, "pin-a");
            tenant(owner, tenantB, "pin-b");
            mappingVersion(owner, tenantA, mappingVersionA1, contractA1);
            mappingVersion(owner, tenantA, mappingVersionA2, contractA2);
            mappingVersion(owner, tenantB, mappingVersionB, contractB);

            delivery(owner, tenantA, legacyA1, TODAY, mappingVersionA1, null, "DEAD_LETTERED");
            delivery(owner, tenantA, legacyA1Yesterday, TODAY.minus(1, ChronoUnit.DAYS), mappingVersionA1, null,
                    "RETRY_WAIT");
            delivery(owner, tenantA, legacyA2, TODAY, mappingVersionA2, null, "DEAD_LETTERED");
            delivery(owner, tenantB, legacyB, TODAY, mappingVersionB, null, "DEAD_LETTERED");
            delivery(owner, tenantA, pinnedA, TODAY, mappingVersionA1, alreadyPinnedContract, "DEAD_LETTERED");
            delivery(owner, tenantA, noMappingVersion, TODAY, null, null, "DEAD_LETTERED");
            delivery(owner, tenantA, danglingMappingVersion, TODAY, UUID.randomUUID(), null, "DEAD_LETTERED");
            // tenant B's delivery naming tenant A's mapping version: never resolved across tenants
            delivery(owner, tenantB, otherTenantsMappingVersion, TODAY, mappingVersionA1, null, "DEAD_LETTERED");

            // production shape: a table owner that is NOT a superuser, so FORCE RLS binds the migration
            s.execute("CREATE ROLE pin_migrator LOGIN PASSWORD 'pin' NOSUPERUSER NOBYPASSRLS");
            s.execute("ALTER TABLE public.delivery OWNER TO pin_migrator");
            s.execute("ALTER TABLE public.mapping_version OWNER TO pin_migrator");
            s.execute("ALTER TABLE public.tenant OWNER TO pin_migrator");
            s.execute("GRANT USAGE ON SCHEMA public TO pin_migrator");
        }

        String script = backfillScript();
        runAsMigrator(script);
        snapshotAfterFirstRun = snapshot();
        runAsMigrator(script);

        flyway().load().migrate(); // the file is a valid, orderable Flyway migration and applies on top
    }

    @AfterAll
    void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("a legacy row takes the target contract of its own mapping version, in both tenants and any partition")
    void legacyRowsTakeTheMappingVersionsTargetContract() throws Exception {
        assertThat(contract(legacyA1)).isEqualTo(contractA1);
        assertThat(contract(legacyA1Yesterday)).as("a row in another day partition").isEqualTo(contractA1);
        assertThat(contract(legacyA2)).as("each row follows its own mapping version").isEqualTo(contractA2);
        assertThat(contract(legacyB)).as("tenant B is healed from tenant B's mapping version").isEqualTo(contractB);
    }

    @Test
    @DisplayName("an existing pin is never overwritten")
    void anExistingPinIsUntouched() throws Exception {
        assertThat(contract(pinnedA)).isEqualTo(alreadyPinnedContract);
    }

    @Test
    @DisplayName("a row without a derivable pin keeps NULL: no mapping version, a dangling one, or another tenant's")
    void rowsWithoutADerivablePinStayNull() throws Exception {
        assertThat(contract(noMappingVersion)).isNull();
        assertThat(contract(danglingMappingVersion)).isNull();
        assertThat(contract(otherTenantsMappingVersion)).as("never copied across tenants").isNull();
    }

    @Test
    @DisplayName("a second run changes nothing")
    void runningTwiceChangesNothing() throws Exception {
        assertThat(snapshot()).isEqualTo(snapshotAfterFirstRun);
    }

    @Test
    @DisplayName("an operator replay of a healed legacy dead letter is accepted; a row left unpinned is still refused")
    void anOperatorReplayOfAHealedLegacyDeadLetterSucceeds() throws Exception {
        assertThat(replay(tenantA, legacyA1).outcome()).isEqualTo(DeliveryOperatorActions.Outcome.MOVED_TO_DUE);
        assertThat(replay(tenantA, legacyA1).contractVersionId()).isEqualTo(contractA1);
        assertThat(replay(tenantA, noMappingVersion).outcome()).isEqualTo(DeliveryOperatorActions.Outcome.NOT_PINNED);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** The real {@link DeliveryOperatorActions} rules over a store that reads the row the way the JDBC store does. */
    private DeliveryOperatorActions.Result replay(UUID tenant, UUID id) throws Exception {
        OperatorActionStore store = new OperatorActionStore() {
            @Override
            public Optional<Target> lockForAction(UUID deliveryId) {
                try (Connection c = connect(); PreparedStatement p = c.prepareStatement("SELECT state,"
                        + " mapping_version_id, contract_version_id FROM public.delivery WHERE id = ?::uuid")) {
                    p.setString(1, deliveryId.toString());
                    try (ResultSet rs = p.executeQuery()) {
                        return rs.next()
                                ? Optional.of(new Target(deliveryId, DeliveryState.valueOf(rs.getString(1)),
                                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)))
                                : Optional.empty();
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public boolean replay(UUID deliveryId, Instant at) {
                return true;
            }

            @Override
            public boolean retryNow(UUID deliveryId, Instant at) {
                return true;
            }
        };
        return new DeliveryOperatorActions(store, entry -> 1L, (ApplicationEventPublisher) event -> { },
                () -> Optional.of(tenant), action -> action.doInTransaction(null), Clock.systemUTC()).replay(id,
                        "operator-pin-backfill");
    }

    private String backfillScript() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(BACKFILL_FILE)) {
            assertThat(in).as("migration %s is on the classpath", BACKFILL_FILE).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void runAsMigrator(String script) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "pin_migrator", "pin");
                Statement s = c.createStatement()) {
            s.execute(script);
        }
    }

    private String snapshot() throws SQLException {
        return scalar("SELECT string_agg(id::text || '=' || coalesce(contract_version_id::text, '-'), ','"
                + " ORDER BY id) FROM public.delivery");
    }

    private UUID contract(UUID delivery) throws SQLException {
        String value = scalar("SELECT contract_version_id::text FROM public.delivery WHERE id = '" + delivery + "'");
        return value == null ? null : UUID.fromString(value);
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private String scalar(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void tenant(Connection c, UUID id, String code) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
            p.setString(1, id.toString());
            p.setString(2, code + "-" + id.toString().substring(0, 8));
            p.executeUpdate();
        }
    }

    private static void mappingVersion(Connection c, UUID tenant, UUID id, UUID targetContract) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO public.mapping_version (id, tenant_id, mapping_id,"
                + " version, source_contract_version_id, target_contract_version_id, engine, field_mappings,"
                + " compiled_expression, content_sha256, author_id) VALUES (?::uuid, ?::uuid, gen_random_uuid(), 1,"
                + " gen_random_uuid(), ?::uuid, 'JSONATA', '{}'::jsonb, '$', decode(repeat('ab', 32), 'hex'),"
                + " gen_random_uuid())")) {
            p.setString(1, id.toString());
            p.setString(2, tenant.toString());
            p.setString(3, targetContract.toString());
            p.executeUpdate();
        }
    }

    private static void delivery(Connection c, UUID tenant, UUID id, Instant createdAt, UUID mappingVersion,
            UUID contract, String state) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO public.delivery (id, tenant_id, state, created_at,"
                + " updated_at, mapping_version_id, contract_version_id, dead_letter_class) VALUES (?::uuid, ?::uuid,"
                + " ?, ?, ?, ?::uuid, ?::uuid, ?)")) {
            p.setString(1, id.toString());
            p.setString(2, tenant.toString());
            p.setString(3, state);
            p.setTimestamp(4, java.sql.Timestamp.from(createdAt));
            p.setTimestamp(5, java.sql.Timestamp.from(createdAt));
            p.setString(6, mappingVersion == null ? null : mappingVersion.toString());
            p.setString(7, contract == null ? null : contract.toString());
            p.setString(8, "DEAD_LETTERED".equals(state) ? "PERMANENT" : null);
            p.executeUpdate();
        }
    }
}
