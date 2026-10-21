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
    /** The published version every seeded message pins; its snapshot lists the routes registered so far. */
    static final UUID INTERFACE_VERSION_ID = UUID.fromString("13131313-0000-4000-8000-000000000002");

    private final java.util.List<RouteRows> routes = new java.util.ArrayList<>();
    private boolean versionSeeded;

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
        return registerRoute(endpointCode, baseUrl, false);
    }

    /**
     * As {@link #registerRoute(String, String)}, with the endpoint's {@code idempotent_receive} flag - it decides
     * whether a 408/5xx is TRANSIENT (retried) or AMBIGUOUS (dead-lettered), ADR-010 outcome table.
     */
    public RouteRows registerRoute(String endpointCode, String baseUrl, boolean idempotentReceive) {
        return registerRoute(endpointCode, baseUrl, idempotentReceive, 30000);
    }

    /** As above with the endpoint's {@code timeout_ms} (100..300000, the config table's check). */
    public RouteRows registerRoute(String endpointCode, String baseUrl, boolean idempotentReceive, int timeoutMs) {
        UUID systemId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        insertAsOwner("insert into public.system (id, tenant_id, code, name, direction, type, settings)"
                + " values ('" + systemId + "', '" + TENANT + "', '" + endpointCode + "-system', '"
                + endpointCode + " system', 'TARGET', 'API', '{}'::jsonb)");
        insertAsOwner("insert into public.target_endpoint (id, tenant_id, system_id, code, name, base_url,"
                + " http_method, auth_method, timeout_ms, idempotent_receive)"
                + " values ('" + endpointId + "', '" + TENANT + "', '" + systemId + "', '" + endpointCode
                + "', '" + endpointCode + " endpoint', '" + baseUrl + "', 'POST', 'NO_AUTH', " + timeoutMs + ", " + idempotentReceive + ")");
        insertAsOwner("insert into public.route (id, tenant_id, interface_id, target_endpoint_id, sequence,"
                + " status) values ('" + routeId + "', '" + TENANT + "', '" + INTERFACE_ID + "', '" + endpointId
                + "', 1, 'ACTIVE')");
        RouteRows rows = new RouteRows(routeId, endpointId);
        routes.add(rows);
        return rows;
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
        seedAcceptedMessage(messageId, businessKey, payloadJson);
        try {
            producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(
                    io.integrationplatform.routing.topics.Topics.MESSAGE_ACCEPTED, businessKey,
                    acceptedRecordJson(messageId, businessKey, payloadJson)))
                    .get(90, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("accepted record not produced: " + e.getMessage(), e);
        }
    }

    /** The ids-only work record the outbox relay publishes (KafkaPublishPort, WorkRecordParser v1). */
    String acceptedRecordJson(UUID messageId, String businessKey, String payloadJson) {
        return "{\"v\":1,\"type\":\"message.accepted\",\"id\":\"" + messageId + "\"}";
    }

    /**
     * The rows the intake path writes before the record exists (IntakeStore): the ACCEPTED message pinning the
     * published version, and its payload. The version is laid once, on the first message, with the routes
     * registered by then (stage 1 routes from the pinned snapshot, Stage1FanOut).
     */
    private void seedAcceptedMessage(UUID messageId, String businessKey, String payloadJson) {
        seedAcceptedMessage(messageId, businessKey, payloadJson, Instant.now());
    }

    /**
     * T72: an ACCEPTED message whose {@code message.accepted} record is never produced - the lost-record case the
     * reconciler's routing leg repairs - accepted {@code acceptedAt} (past the stuck threshold).
     */
    public void seedStuckAcceptedMessage(UUID messageId, String businessKey, String payloadJson, Instant acceptedAt) {
        seedAcceptedMessage(messageId, businessKey, payloadJson, acceptedAt);
    }

    private synchronized void seedAcceptedMessage(UUID messageId, String businessKey, String payloadJson,
            Instant acceptedAtInstant) {
        if (!versionSeeded) {
            StringBuilder snapshot = new StringBuilder("{\"routes\":[");
            for (int i = 0; i < routes.size(); i++) {
                RouteRows route = routes.get(i);
                snapshot.append(i == 0 ? "" : ",").append("{\"id\":\"").append(route.routeId())
                        .append("\",\"sequence\":1,\"isPrimary\":false,\"status\":\"ACTIVE\",\"targetEndpointId\":\"")
                        .append(route.endpointId()).append("\"}");
            }
            // each route pins an identity JSONata mapping version, so stage 2 has a transform input (TransformedApiBody)
            StringBuilder mappingRows = new StringBuilder();
            snapshot.append("],\"mappings\":[");
            for (int i = 0; i < routes.size(); i++) {
                RouteRows route = routes.get(i);
                UUID mappingVersionId = UUID.randomUUID();
                snapshot.append(i == 0 ? "" : ",").append("{\"id\":\"").append(UUID.randomUUID())
                        .append("\",\"routeId\":\"").append(route.routeId())
                        .append("\",\"mappingVersionId\":\"").append(mappingVersionId).append("\"}");
                mappingRows.append(" insert into public.mapping_version (id, tenant_id, mapping_id, version,"
                        + " source_contract_version_id, target_contract_version_id, engine, field_mappings,"
                        + " compiled_expression, content_sha256, author_id) values ('" + mappingVersionId + "', '"
                        + TENANT + "', '" + UUID.randomUUID() + "', 1, '" + UUID.randomUUID() + "', '"
                        + UUID.randomUUID() + "', 'JSONATA', '[]'::jsonb, '$', sha256('" + mappingVersionId
                        + "'::bytea), '" + UUID.randomUUID() + "');");
            }
            snapshot.append("]}");
            // the contract/author parents are not under test, so this one connection skips the FK triggers
            // (as MaskedFieldsJourneyContainerTest does) instead of building the whole config chain
            insertAsOwner("set session_replication_role = replica;"
                    + " insert into public.interface_version (id, tenant_id, interface_id, version, status,"
                    + " business_key_rule, snapshot, source_contract_version_id, author_id) values ('"
                    + INTERFACE_VERSION_ID + "', '" + TENANT + "', '" + INTERFACE_ID + "', 1, 'PUBLISHED', '$.key', '"
                    + snapshot + "'::jsonb, '" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "');"
                    + mappingRows + " set session_replication_role = origin");
            versionSeeded = true;
        }
        String acceptedAt = acceptedAtInstant.toString();
        insertAsOwner("insert into public.message (id, tenant_id, interface_id, interface_version_id,"
                + " source_contract_version_id, business_key, state, accepted_at, updated_at) values ('" + messageId
                + "', '" + TENANT + "', '" + INTERFACE_ID + "', '" + INTERFACE_VERSION_ID + "', '" + UUID.randomUUID()
                + "', '" + businessKey.replace("'", "''") + "', 'ACCEPTED', '" + acceptedAt + "'::timestamptz, '"
                + acceptedAt + "'::timestamptz)");
        insertAsOwner("insert into public.payload (message_id, tenant_id, accepted_at, body) values ('" + messageId
                + "', '" + TENANT + "', '" + acceptedAt + "'::timestamptz, '" + payloadJson.replace("'", "''")
                + "'::jsonb)");
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