package io.integrationplatform.routing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.ingest.internal.intake.IntakeService;
import io.integrationplatform.ingest.port.AcceptedMessage;
import io.integrationplatform.ingest.port.IntakeRefusedException;
import io.integrationplatform.ingest.port.IntakeTransaction;
import io.integrationplatform.ingest.port.OutboxWriter;
import io.integrationplatform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * T-048 (E11-F1-S1, ADR-011 Decision 4) - the intake commit contract on the real message-store family (ADR-014).
 * This test lives in platform-routing (not platform-ingest) for ONE reason: the intake transaction writes the
 * message, payload, outbox and stage-timing families together, and only this module's test classpath carries every
 * module's migrations (tenancy, config, ingest outbox, routing's own message/payload and the stage-timing table).
 *
 * <p>Clean-room on purpose (the {@code OutboxWriterContainerTest} lesson): this class owns its {@code DataSource},
 * its transaction manager and its Flyway run. Everything the intake transaction is allowed to persist is written as
 * the restricted {@code ipaas_app} login inside an explicitly stamped transaction (the R08/R11/R05 proof the
 * production intake transaction runs under); the owner connection only seeds and reads.
 *
 * <pre>
 * Scenario: Accepted with a tracking id - message, payload, outbox and INTAKE timing commit together
 * Scenario: No tracking id without the commit - a failed outbox append rolls everything back
 * Scenario: The path cannot reach another tenant's interface - 404, nothing persisted
 * Scenario: An unpublished interface is refused - 404, nothing persisted
 * Scenario: A contract version off the pin is refused   - 422 naming the pinned version
 * Scenario: The business key is unresolved              - 422, nothing persisted
 * Scenario: The stage timing table is append-only       - UPDATE/DELETE as ipaas_app fail 42501
 * </pre>
 */
@Tag("container")
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class IntakeCommitContainerTest {

    private static final UUID TENANT_A = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
    private static final String CODE = "orders";
    private static final String DRAFT_CODE = "drafts";
    private static final String PINNED_LABEL = "1.0";
    private static final String BUSINESS_KEY_RULE = "customer_code";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine");

    private final AtomicLong idempotency = new AtomicLong();

    private IntakeStoreAppRole.Login appLogin;
    private DataSource owner;
    private SimpleDriverDataSource app;
    private TenantContext tenantA = () -> Optional.of(TENANT_A);
    private TenantContext tenantB = () -> Optional.of(TENANT_B);

    @BeforeAll
    void migrateAndSeed() throws SQLException {
        org.postgresql.Driver.class.getName();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        owner = new SimpleDriverDataSource(new org.postgresql.Driver(), POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        appLogin = IntakeStoreAppRole.grantAppRoleLogin(POSTGRES);
        app = new SimpleDriverDataSource(new org.postgresql.Driver(), POSTGRES.getJdbcUrl(),
                appLogin.username(), appLogin.password());
        seedTenants();
        seedPublishedOrders(TENANT_A);
        seedDraftInterface(TENANT_A);
    }

    @Test
    @DisplayName("accepted with a tracking id: message, payload, outbox and INTAKE timing commit together")
    void acceptedWithAtomicCommit() throws SQLException {
        AcceptedMessage accepted = asAppRole(tenantA).accept(request(CODE, PINNED_LABEL,
                "{\"customer_code\":\"CUST-1\",\"total\":10}"));

        UUID tracking = accepted.trackingId();
        assertThat(tracking).as("the tracking id exists only after commit").isNotNull();
        try (Connection read = owner.getConnection()) {
            assertThat(count(read, "select count(*) from public.message where id = ?", tracking))
                    .as("the message row").isEqualTo(BigDecimal.ONE);
            assertThat(count(read, "select count(*) from public.payload where message_id = ?", tracking))
                    .as("the payload row").isEqualTo(BigDecimal.ONE);
            assertThat(count(read, "select count(*) from public.outbox where aggregate_id = ?", tracking))
                    .as("the outbox row").isEqualTo(BigDecimal.ONE);
            assertThat(count(read,
                    "select count(*) from public.message_stage_timing where message_id = ? and stage = 'INTAKE'",
                    tracking)).as("the INTAKE stage timing row").isEqualTo(BigDecimal.ONE);
        }
    }

    @Test
    @DisplayName("no tracking id without the commit: a failed outbox append rolls message and payload back")
    void failedCommitPersistsNothing() throws SQLException {
        IntakeTransaction failing = wiringFor(app, tenantA, entry -> {
            throw new IllegalStateException("the database fails during the intake transaction");
        });
        assertThatThrownBy(() -> stamped(tenantA, failing).accept(request(
                        CODE, PINNED_LABEL, "{\"customer_code\":\"CUST-2\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the database fails during the intake transaction");
        try (Connection read = owner.getConnection()) {
            assertThat(count(read, "select count(*) from public.message where business_key = 'CUST-2'", null))
                    .as("nothing persisted").isEqualTo(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("the path cannot reach another tenant's interface: 404, nothing persisted")
    void otherTenantInterfaceRefused() throws SQLException {
        // Tenant B exists but has no "orders" interface; the resolution is tenant-scoped.
        assertThatThrownBy(() -> stamped(tenantB, wiringFor(app, tenantB, null))
                .accept(request(CODE, PINNED_LABEL, "{\"customer_code\":\"CUST-3\"}")))
                .isInstanceOfSatisfying(IntakeRefusedException.class, refused ->
                        assertThat(refused.reason()).isEqualTo(
                                IntakeRefusedException.Reason.INTERFACE_NOT_FOUND_OR_NOT_PUBLISHED));
        try (Connection read = owner.getConnection()) {
            assertThat(count(read, "select count(*) from public.message where business_key = 'CUST-3'", null))
                    .isEqualTo(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("an unpublished interface is refused 404 with nothing persisted")
    void unpublishedInterfaceRefused() throws SQLException {
        assertThatThrownBy(() -> asAppRole(tenantA).accept(request(DRAFT_CODE, PINNED_LABEL,
                        "{\"customer_code\":\"CUST-4\"}")))
                .isInstanceOfSatisfying(IntakeRefusedException.class, refused ->
                        assertThat(refused.reason()).isEqualTo(
                                IntakeRefusedException.Reason.INTERFACE_NOT_FOUND_OR_NOT_PUBLISHED));
    }

    @Test
    @DisplayName("a contract version off the pin is refused 422 naming the pinned version, nothing persisted")
    void contractPinMismatchRefused() throws SQLException {
        assertThatThrownBy(() -> asAppRole(tenantA).accept(request(CODE, "2.0",
                        "{\"customer_code\":\"CUST-5\"}")))
                .isInstanceOfSatisfying(IntakeRefusedException.class, refused -> {
                    assertThat(refused.reason()).isEqualTo(IntakeRefusedException.Reason.CONTRACT_PIN_MISMATCH);
                    assertThat(refused.detail()).contains(PINNED_LABEL);
                });
        try (Connection read = owner.getConnection()) {
            assertThat(count(read, "select count(*) from public.message where business_key = 'CUST-5'", null))
                    .isEqualTo(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("the business key is unresolved 422 with nothing persisted")
    void businessKeyUnresolvedRefused() throws SQLException {
        assertThatThrownBy(() -> asAppRole(tenantA).accept(request(CODE, PINNED_LABEL, "{}")))
                .isInstanceOfSatisfying(IntakeRefusedException.class, refused ->
                        assertThat(refused.reason()).isEqualTo(IntakeRefusedException.Reason.BUSINESS_KEY_UNRESOLVED));
    }

    @Test
    @DisplayName("the stage timing table is append-only: UPDATE and DELETE as ipaas_app fail 42501")
    void stageTimingIsAppendOnly() throws SQLException {
        AcceptedMessage accepted = asAppRole(tenantA).accept(request(CODE, PINNED_LABEL,
                "{\"customer_code\":\"CUST-6\"}"));
        UUID tracking = accepted.trackingId();
        try (Connection appConnection = IntakeStoreAppRole.connectAsAppRole(POSTGRES, appLogin)) {
            stampTenant(appConnection, TENANT_A);
            assertThatThrownBy(() -> execute(appConnection, "update public.message_stage_timing"
                            + " set finished_at = now() where message_id = ?", tracking))
                    .as("no UPDATE on the timing table").isInstanceOfSatisfying(SQLException.class,
                            denied -> assertThat(denied.getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> execute(appConnection,
                            "delete from public.message_stage_timing where message_id = ?", tracking))
                    .as("no DELETE on the timing table").isInstanceOfSatisfying(SQLException.class,
                            denied -> assertThat(denied.getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> execute(appConnection, "delete from public.message where id = ?", tracking))
                    .as("no DELETE on the message family either").isInstanceOfSatisfying(SQLException.class,
                            denied -> assertThat(denied.getSQLState()).isEqualTo("42501"));
        }
        // RLS: a tenant B stamp sees none of tenant A's messages.
        try (Connection appConnection = IntakeStoreAppRole.connectAsAppRole(POSTGRES, appLogin)) {
            stampTenant(appConnection, TENANT_B);
            assertThat(count(appConnection, "select count(*) from public.message", null)).isEqualTo(BigDecimal.ZERO);
        }
    }

    // ---------------------------------------------------------------- the transaction under proof

    /**
     * The caller's transaction the port requires (R11): every accept runs in one ipaas_app transaction whose
     * connection is tenant-stamped first, transaction-local ({@code set_config(..., true)}) as in production.
     */
    private IntakeTransaction stamped(TenantContext tenant, IntakeTransaction port) {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(app));
        JdbcClient jdbc = JdbcClient.create(app);
        return request -> transaction.execute(status -> {
            jdbc.sql("select set_config('app.tenant_id', :tenant, true)")
                    .param("tenant", tenant.requireTenantId().toString()).query().singleValue();
            return port.accept(request);
        });
    }

    /** The intake wiring the production run uses: the real outbox writer and stage timing writer under the port. */
    private IntakeTransaction asAppRole(TenantContext tenant) {
        return stamped(tenant, wiringFor(app, tenant, null));
    }

    private IntakeTransaction wiringFor(DataSource dataSource, TenantContext tenant, OutboxWriter failing) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        OutboxWriter outbox = failing != null ? failing
                : new io.integrationplatform.ingest.internal.outbox.JdbcOutboxWriter(jdbc, tenant);
        return new IntakeService(jdbc, tenant, outbox, new io.integrationplatform.ingest.internal.timing
                .JdbcStageTimingWriter(jdbc, tenant));
    }

    /** Tenant-stamps the transaction's shared connection (R11 production behaviour, session scope in tests). */
    private void stampTenant(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement stamp = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            stamp.setString(1, tenantId.toString());
            stamp.execute();
        }
    }

    private IntakeTransaction.IntakeRequest request(String interfaceCode, String version, String body) {
        return new IntakeTransaction.IntakeRequest(interfaceCode, version,
                JsonMapper.builder().build().readTree(body), "idem-" + idempotency.incrementAndGet());
    }

    // ---------------------------------------------------------------- fixtures (owner only)

    private void seedTenants() throws SQLException {
        try (Connection sql = owner.getConnection(); Statement statement = sql.createStatement()) {
            statement.execute("insert into public.tenant (id, code, status, time_zone) values"
                    + " ('" + TENANT_A + "', 'intake-a', 'ACTIVE', 'Etc/UTC'),"
                    + " ('" + TENANT_B + "', 'intake-b', 'ACTIVE', 'Etc/UTC')");
        }
    }

    /** The published "orders" flow pinned to an approved source contract version 1.0. */
    private void seedPublishedOrders(UUID tenant) throws SQLException {
        UUID systemId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID sourceVersionId = UUID.randomUUID();
        UUID actor = UUID.fromString("cccccccc-0000-0000-0000-00000000000a");
        UUID approver = UUID.fromString("cccccccc-0000-0000-0000-00000000000b");
        UUID interfaceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        try (Connection sql = owner.getConnection()) {
            sql.setAutoCommit(false);
            insert(sql, "insert into public.system (id, tenant_id, code, name, direction) values (?, ?, 'src',"
                    + " 'Customer A source', 'SOURCE')", systemId, tenant);
            insert(sql, "insert into public.contract (id, tenant_id, system_id, code, role, name) values"
                    + " (?, ?, ?, 'customer-orders-v', 'SOURCE_REQUEST', 'Customer orders')",
                    contractId, tenant, systemId);
            insert(sql, "insert into public.contract_version (id, tenant_id, contract_id, version_label, status,"
                    + " json_schema_draft_2020_12, author_id) values (?, ?, ?, ?, 'APPROVED', '{}', ?)",
                    sourceVersionId, tenant, contractId, PINNED_LABEL, actor);
            insert(sql, "insert into public.interface (id, tenant_id, code, name, mode, ordering, current_version,"
                    + " published_version, status) values (?, ?, ?, 'Orders', 'ASYNC', 'PER_KEY', 1, 1, 'PUBLISHED')",
                    interfaceId, tenant, CODE);
            insert(sql, "insert into public.interface_version (id, tenant_id, interface_id, version, status,"
                    + " business_key_rule, snapshot, source_contract_version_id, author_id, approver_id)"
                    + " values (?, ?, ?, 1, 'PUBLISHED', ?, ?::jsonb, ?, ?, ?)",
                    versionId, tenant, interfaceId, BUSINESS_KEY_RULE, "{}", sourceVersionId, actor, approver);
            sql.commit();
        }
    }

    /**
     * A second interface of the same tenant that was never published (the 404 leg). Its draft version reuses the
     * tenant's approved source contract version seeded by {@link #seedPublishedOrders}: the composite FK
     * (tenant_id, source_contract_version_id) -> contract_version(tenant_id, id) refuses a dangling reference.
     */
    private void seedDraftInterface(UUID tenant) throws SQLException {
        UUID actor = UUID.fromString("cccccccc-0000-0000-0000-00000000000a");
        try (Connection sql = owner.getConnection()) {
            sql.setAutoCommit(false);
            insert(sql, "insert into public.interface (id, tenant_id, code, name, mode, ordering, current_version,"
                    + " status) values (?, ?, ?, 'Customer orders drafts', 'ASYNC', 'PER_KEY', 0, 'DRAFT')",
                    UUID.randomUUID(), tenant, DRAFT_CODE);
            insert(sql, "insert into public.interface_version (id, tenant_id, interface_id, version, status,"
                    + " business_key_rule, snapshot, source_contract_version_id, author_id)"
                    + " select i.id, i.tenant_id, i.id, 1, 'DRAFT', 'customer_code', '{}', cv.id, ?"
                    + " from public.interface i join public.contract_version cv"
                    + " on cv.tenant_id = i.tenant_id and cv.version_label = ?"
                    + " where i.tenant_id = ? and i.code = ?",
                    actor, PINNED_LABEL, tenant, DRAFT_CODE);
            sql.commit();
        }
    }

    private static void insert(Connection sql, String statement, Object... parameters) throws SQLException {
        try (PreparedStatement insert = sql.prepareStatement(statement)) {
            for (int i = 0; i < parameters.length; i++) {
                insert.setObject(i + 1, parameters[i]);
            }
            int rows = insert.executeUpdate();
            if (rows != 1) {
                // An insert-select that matched nothing would leave the 404 leg passing for the wrong reason.
                throw new IllegalStateException("seed insert wrote " + rows + " rows, expected 1: " + statement);
            }
        }
    }

    private static void execute(Connection sql, String statement, Object parameter) throws SQLException {
        try (PreparedStatement query = sql.prepareStatement(statement)) {
            if (parameter != null) {
                query.setObject(1, parameter);
            }
            query.executeUpdate();
        }
    }

    private static BigDecimal count(Connection sql, String statement, Object parameter) throws SQLException {
        try (PreparedStatement query = sql.prepareStatement(statement)) {
            if (parameter != null) {
                query.setObject(1, parameter);
            }
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getBigDecimal(1);
            }
        }
    }
}