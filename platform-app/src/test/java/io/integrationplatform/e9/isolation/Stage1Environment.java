package io.integrationplatform.e9.isolation;

import io.integrationplatform.PlatformTestWiring;
import java.sql.Connection;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The E9 container environment (T-118/T-122), run inside platform-app beside the whole application (E9 reopen B1):
 * the caller's {@code @SpringBootTest} owns the PostgreSQL and Kafka containers and the application migrates the
 * database itself; this fixture seeds the tenant directory, a published interface and the target endpoints and
 * routes as the owner, reads as the restricted application login (row-level security on), and produces the
 * {@code message.accepted} records the running stage 1 consumes.
 *
 * <p>Passwords come from {@link PlatformTestWiring} per run and live only in memory (R07). Target endpoint URLs
 * use {@link TargetStub}'s test-only host, which {@link E9TestNetwork} pins to the in-process stub.
 */
public final class Stage1Environment {

    public static final UUID TENANT = UUID.fromString("0000000d-0000-4000-8000-00000000000d");
    public static final UUID INTERFACE_ID = UUID.fromString("13131313-0000-4000-8000-000000000001");

    private final String kafkaBootstrap;
    private org.apache.kafka.clients.producer.KafkaProducer<String, String> producer;
    private final SimpleDriverDataSource appDataSource;
    private final TransactionTemplate transactionTemplate;
    private final JdbcClient ownerJdbc;

    /** Call after the application context started (Flyway ran) and the application role was granted. */
    public Stage1Environment(PostgreSQLContainer postgres, PlatformTestWiring.RestrictedLogin login,
            String kafkaBootstrap) {
        this.kafkaBootstrap = kafkaBootstrap;
        appDataSource = new SimpleDriverDataSource(new Driver(), postgres.getJdbcUrl(), login.username(),
                login.password());
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
        ownerJdbc = JdbcClient.create(new SimpleDriverDataSource(new Driver(), postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword()));
        seedTenantDirectoryAndInterface();
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
            config.setProperty("bootstrap.servers", kafkaBootstrap);
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