package io.integrationplatform.routing.e9.isolation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The E9 wave-0 container environment (T-118/T-122, lane D, vijay-developer): one PostgreSQL (the pinned
 * {@code postgres:17.11-alpine} image, the module-name-free variant of DeliveryTestDatabase because the
 * postgres-specific Testcontainers module is not on this module's test classpath and this fixture may not edit
 * platform-routing/pom.xml), migrated with every module's migrations exactly as a deployment runs them, seeded
 * with the tenant directory, a published interface and its two (or three, T-122) target endpoints and routes,
 * the Kafka harness REUSED unmodified from routing/kafka (T-044, read-only for lane D), and one producer for the
 * {@code message.accepted} records.
 *
 * <p>WAVE-0 NOTE: routing stage 1 (T-117/T-178) and stage 2 (T-177) do not exist on the branch tip yet, so the
 * accepted records this environment produces are consumed by nobody and the assertion polls expire - the tests
 * are RED until lanes A1/C land, by design. The accepted-record JSON here is the minimal envelope; its exact wire
 * contract is E8's message-table work (ADR-014) and lane C owns the consumer side - if lane C's contract differs,
 * adjust {@link #acceptedRecordJson} at integration, never the assertions.
 *
 * <p>Passwords are random per run and live only in memory (R07). Runs inside one JVM so the in-process target
 * stubs of {@link TargetStub} are reachable at their 127.0.0.1 URLs when stage 2 (via Camel) calls them.
 */
public final class Stage1Environment {

    public static final String OWNER_ROLE = "ipaas_migrator";
    public static final String APP_LOGIN = "ipaas_app_login";
    public static final String APP_ROLE = "ipaas_app";

    public static final UUID TENANT = UUID.fromString("0000000d-0000-4000-8000-00000000000d");
    public static final UUID INTERFACE_ID = UUID.fromString("13131313-0000-4000-8000-000000000001");

    private static Stage1Environment instance;
    private static RuntimeException setupFailure;

    private org.apache.kafka.clients.producer.KafkaProducer<String, String> producer;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private final GenericContainer postgres = new GenericContainer("postgres:17.11-alpine")
            .withExposedPorts(5432)
            .withEnv("POSTGRES_USER", "postgres")
            .withEnv("POSTGRES_PASSWORD", randomPassword())
            .waitingFor(Wait.forListeningPort());

    private final String ownerPassword = randomPassword();
    private final String appPassword = randomPassword();
    private final SimpleDriverDataSource appDataSource;
    private final TransactionTemplate transactionTemplate;
    private final JdbcClient ownerJdbc;

    private Stage1Environment() {
        try {
            postgres.start();
            String url = jdbcUrl();
            awaitDatabaseAcceptingConnections(url);
            setUpRolesAndMigrations(url);
            appDataSource = new SimpleDriverDataSource(new org.postgresql.Driver(), url,
                    APP_LOGIN, appPassword);
            transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
            ownerJdbc = JdbcClient.create(new SimpleDriverDataSource(new Driver(), url, OWNER_ROLE, ownerPassword));
            seedTenantDirectoryAndInterface();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("E9 stage-1 environment setup failed: " + e.getMessage(), e);
        }
    }

    public static synchronized Stage1Environment get() {
        if (setupFailure != null) {
            throw setupFailure;
        }
        if (instance == null) {
            try {
                instance = new Stage1Environment();
            } catch (RuntimeException e) {
                setupFailure = e;
                throw e;
            }
        }
        return instance;
    }

    private static String randomPassword() {
        return "e9-" + UUID.randomUUID();
    }

    private String jdbcUrl() {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/postgres?currentSchema=public";
    }

    /** The image answers its port before the init database is ready; never trust the port alone. */
    private void awaitDatabaseAcceptingConnections(String url) throws SQLException, InterruptedException {
        SQLException last = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            try (Connection ignored = DriverManager.getConnection(url, "postgres",
                    (String) postgres.getEnvMap().get("POSTGRES_PASSWORD"))) {
                return;
            } catch (SQLException e) {
                last = e;
                Thread.sleep(500);
            }
        }
        throw last;
    }

    private void setUpRolesAndMigrations(String url) throws SQLException {
        try (Connection admin = DriverManager.getConnection(url, "postgres",
                (String) postgres.getEnvMap().get("POSTGRES_PASSWORD")); var sql = admin.createStatement()) {
            sql.execute("CREATE ROLE " + OWNER_ROLE + " LOGIN CREATEROLE PASSWORD '" + ownerPassword + "'");
            sql.execute("ALTER DATABASE postgres OWNER TO " + OWNER_ROLE);
        }
        org.flywaydb.core.Flyway.configure()
                .dataSource(url, OWNER_ROLE, ownerPassword)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection admin = DriverManager.getConnection(url, "postgres",
                (String) postgres.getEnvMap().get("POSTGRES_PASSWORD")); var sql = admin.createStatement()) {
            sql.execute("CREATE ROLE " + APP_LOGIN + " LOGIN NOBYPASSRLS PASSWORD '" + appPassword
                    + "' IN ROLE " + APP_ROLE);
        }
    }

    /** The directory row, the published interface and the route rows the stage-1 reader selects (owner-written). */
    private void seedTenantDirectoryAndInterface() {
        insertAsOwner("insert into public.tenant (id, code, status, time_zone) values"
                + " ('" + TENANT + "', 'e9-lane-d', 'ACTIVE', 'Etc/UTC')");
        insertAsOwner("insert into public.interface (id, tenant_id, code, name, mode, ordering,"
                + " current_version, published_version, status)"
                + " values ('" + INTERFACE_ID + "', '" + TENANT + "', 'e9-iso', 'E9 lane D interface', 'ASYNC',"
                + " 'PER_KEY', 1, 1, 'PUBLISHED')");
    }

    /**
     * Registers one target endpoint and one ACTIVE route of the interface to it (the configuration rows stage 1
     * reads routing from); the base URL is the caller's target stub. The mapping version is the seam's value
     * ({@code io.integrationplatform.delivery.port.Stage1Delivery}), not a row of this fixture - every module's
     * migrations own the {@code public.mapping} table shape, and lane C pins versions on the delivery.
     *
     * @return {@code routeId/endpointId} - the delivery row's {@code target_id} is the endpoint id
     */
    public RouteRows registerRoute(String endpointCode, String baseUrl) {
        UUID systemId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        insertAsOwner("insert into public.system (id, tenant_id, code, name, direction)"
                + " values ('" + systemId + "', '" + TENANT + "', '" + endpointCode + "-system', '"
                + endpointCode + " system', 'TARGET')");
        insertAsOwner("insert into public.target_endpoint (id, tenant_id, system_id, code, name, base_url,"
                + " http_method, auth_method, timeout_ms)"
                + " values ('" + endpointId + "', '" + TENANT + "', '" + systemId + "', '" + endpointCode
                + "', '" + endpointCode + " endpoint', '" + baseUrl + "', 'POST', 'NO_AUTH', 30000)");
        insertAsOwner("insert into public.route (id, tenant_id, interface_id, target_endpoint_id, sequence,"
                + " status) values ('" + routeId + "', '" + TENANT + "', '" + INTERFACE_ID + "', '" + endpointId
                + "', 1, 'ACTIVE')");
        return new RouteRows(routeId, endpointId);
    }

    /** The two ids one routing registration creates: the route row and its target endpoint. */
    public record RouteRows(UUID routeId, UUID endpointId) {
    }

    private void insertAsOwner(String sql) {
        ownerJdbc.sql(sql).update();
    }

    /** A pooled-style datasource handle of the restricted application login (app role via ipaas_app_login). */
    public javax.sql.DataSource appDataSource() {
        return appDataSource;
    }

    /** Runs work in one transaction with {@code app.tenant_id} stamped (set_config local, R11). */
    public void inTenantTx(java.util.function.Consumer<JdbcClient> work) {
        transactionTemplate.executeWithoutResult(status -> {
            stamp();
            work.accept(JdbcClient.create(appDataSource));
        });
    }

    /** Reads one row through a fresh stamped transaction (each poll opens its own short transaction, no pool). */
    public Optional<java.util.Map<String, Object>> queryOneAsApp(String sql, Object... args) {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
        return tx.execute(status -> {
            stamp();
            var rows = new org.springframework.jdbc.core.JdbcTemplate(appDataSource).queryForList(sql, args);
            return rows.isEmpty() ? Optional.<java.util.Map<String, Object>>empty()
                    : Optional.of(rows.get(0));
        });
    }

    /** Reads one column through a fresh stamped transaction (the no-rollback recheck). */
    public <T> java.util.List<T> queryListAsApp(String sql, Class<T> type, Object... args) {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
        return tx.execute(status -> {
            stamp();
            return new org.springframework.jdbc.core.JdbcTemplate(appDataSource)
                    .queryForList(sql, type, args);
        });
    }

    private void stamp() {
        try {
            Connection connection = org.springframework.jdbc.datasource.DataSourceUtils
                    .doGetConnection(appDataSource);
            try (PreparedStatement set = connection
                    .prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                set.setString(1, TENANT.toString());
                set.executeQuery();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("tenant stamp failed", e);
        }
    }

    /**
     * Produces one {@code platform.message.accepted} record (the work topic the stage-1 consumer subscribes to,
     * {@link io.integrationplatform.routing.topics.Topics#MESSAGE_ACCEPTED}); the record key is the ordering key
     * so same-key messages land on one partition (ADR-011 Decision 6).
     */
    public void produceAccepted(UUID messageId, String businessKey, String payloadJson) {
        produceAccepted(acceptedProducer(), messageId, businessKey, payloadJson);
    }

    /**
     * One producer for a whole run (the perf test produces 1,000 records; one producer, not one per record).
     * Single-threaded use per test - the producer is not shared between threads.
     */
    public org.apache.kafka.clients.producer.KafkaProducer<String, String> acceptedProducer() {
        if (producer == null) {
            Properties config = new Properties();
            config.setProperty("bootstrap.servers", io.integrationplatform.routing.kafka.KafkaHarness
                    .get().bootstrapServers());
            config.setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            config.setProperty("value.serializer",
                    "org.apache.kafka.common.serialization.StringSerializer");
            config.setProperty("acks", "all");
            producer = new org.apache.kafka.clients.producer.KafkaProducer<>(config);
        }
        return producer;
    }

    /** Produces one accepted record on the given producer, waiting for its ack. */
    public void produceAccepted(org.apache.kafka.clients.producer.KafkaProducer<String, String> producer,
            UUID messageId, String businessKey, String payloadJson) {
        try {
            producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(
                    io.integrationplatform.routing.topics.Topics.MESSAGE_ACCEPTED, businessKey,
                    acceptedRecordJson(messageId, businessKey, payloadJson)))
                    .get(90, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("accepted record not produced: " + e.getMessage(), e);
        }
    }

    /** The accepted-record envelope; lane C may narrow the shape at integration (wave-0 note). */
    String acceptedRecordJson(UUID messageId, String businessKey, String payloadJson) {
        return "{\"messageId\":\"" + messageId + "\",\"tenantId\":\"" + TENANT + "\",\"interfaceId\":\""
                + INTERFACE_ID + "\",\"businessKey\":\"" + businessKey + "\",\"keySeq\":1,\"acceptedAt\":\""
                + Instant.now() + "\",\"payload\":" + payloadJson + "}";
    }

    /**
     * Polls until the probe reports something, the deadline expires or the probe throws - the polling of a
     * GREEN run; a RED run expires and the caller's assert names what never appeared.
     */
    public static <T> T pollUntil(Duration deadline, Supplier<Optional<T>> probe, String waitingFor) {
        Instant end = Instant.now().plus(deadline);
        while (Instant.now().isBefore(end)) {
            Optional<T> found;
            try {
                found = probe.get();
            } catch (RuntimeException probing) {
                throw new AssertionError("probe threw while waiting for " + waitingFor, probing);
            }
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + waitingFor, e);
            }
        }
        throw new AssertionError("deadline passed with no result while waiting for " + waitingFor);
    }
}