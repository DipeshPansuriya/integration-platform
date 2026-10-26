package io.integrationplatform.routing.stage;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.stage1.JdbcStage1Deliveries;
import io.integrationplatform.delivery.port.Stage1Delivery;
import io.integrationplatform.ingest.port.CorrelationContext;
import io.integrationplatform.routing.stage2.LayerOneRouteBuilder;
import io.integrationplatform.routing.stage2.LayerOneWaits;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-064 container legs (row 278 T107, E12-F1-S1 "One id end to end", the hops that exist on main): against the real
 * broker and the migrated database,
 * <ul>
 *   <li><b>route</b> - a {@code message.accepted} record read from Kafka runs its handler inside the record's
 *       journey: the handler's log fields carry the record's correlation id, the row's tenant (resolved through
 *       {@code ipaas_work_tenant}) and the message id, and the stage-1 write the handler runs - production's
 *       fan-out insert through {@code JdbcStage1Deliveries} - appends the {@code delivery.due} outbox row with the
 *       same traceparent: the deliver hop's Kafka header (the relay restores it, proven by
 *       KafkaPublishPortContainerTest);</li>
 *   <li><b>deliver</b> - a {@code delivery.due} record carrying that traceparent runs its send through the Camel
 *       layer-1 route, and every try of the target call (a redelivery included) logs under the same correlation
 *       id, the delivery row's tenant (resolved through {@code ipaas_work_tenant}) and the delivery id.</li>
 * </ul>
 * The transform hop and the follow-one-POST test need the end-to-end composition (T106) and are not here; in that
 * composition the transform runs inside the deliver stage's target call, on the same thread, so the deliver leg's
 * fields are the ones its log lines carry.
 */
@Tag("container")
class StageCorrelationContainerTest {

    private static final String TRACEPARENT = "00-5c1e9b2a7d3f4e6a8b0c1d2e3f405162-1a2b3c4d5e6f7081-01";
    private static final String CORRELATION_ID = "5c1e9b2a7d3f4e6a8b0c1d2e3f405162";

    private final StageFixture db = StageFixture.get();

    @Test
    void theRouteAndDeliverStagesContinueTheJourneyTheKafkaRecordCarries() throws Exception {
        UUID message = db.seedMessage(StageFixture.TENANT_A);
        AtomicReference<UUID> delivery = new AtomicReference<>();
        List<Map<String, String>> routeFields = new CopyOnWriteArrayList<>();

        // route: message.accepted from the broker -> the handler logs and runs the stage-1 write inside the journey
        try (Wire route = new Wire()) {
            route.send(message, "message.accepted", TRACEPARENT);
            StageConsumer stage = route.stage(db.protectedRows(), (record, tenantId) -> {
                routeFields.add(MDC.getCopyOfContextMap());
                delivery.set(stageOneWrite(record.id(), tenantId));
            });
            drainUntil(stage, () -> !routeFields.isEmpty());
        }
        assertThat(routeFields).singleElement().satisfies(fields -> assertThat(fields)
                .containsEntry(CorrelationContext.CORRELATION_ID, CORRELATION_ID)
                .containsEntry(CorrelationContext.TENANT_ID, StageFixture.TENANT_A.toString())
                .containsEntry(CorrelationContext.MESSAGE_ID, message.toString()));
        String deliverHeader = storedTraceparent(delivery.get());
        assertThat(deliverHeader).as("the delivery.due outbox row carries the journey to the deliver hop")
                .isEqualTo(TRACEPARENT);

        // deliver: delivery.due from the broker -> the Camel layer-1 route -> the target call, one redelivery
        List<Map<String, String>> fieldsPerTry = new CopyOnWriteArrayList<>();
        DefaultCamelContext camel = new DefaultCamelContext();
        camel.addRoutes(new LayerOneRouteBuilder(send -> {
            fieldsPerTry.add(MDC.getCopyOfContextMap());
            if (fieldsPerTry.size() == 1) {
                throw new TargetFaultException.TransientFaultException("the target blinked");
            }
        }, (deliveryId, layer, outcome, startedAt, finishedAt, detail) -> { },
                (deliveryId, claimToken, attemptsSoFar, outcome, idempotent) -> { },
                new LayerOneWaits(2, Duration.ofMillis(1), Duration.ofSeconds(5))));
        camel.start();
        try (Wire deliver = new Wire(); ProducerTemplate producer = camel.createProducerTemplate()) {
            deliver.send(delivery.get(), "delivery.due", deliverHeader);
            StageConsumer stage = deliver.stage(db.protectedRows(), (record, tenantId) -> producer.sendBody(
                    LayerOneRouteBuilder.LAYER_ONE_SEND,
                    new Stage2Send(record.id(), UUID.randomUUID(), "key-1", UUID.randomUUID(), false)));
            drainUntil(stage, () -> fieldsPerTry.size() == 2);
        } finally {
            camel.stop();
        }
        assertThat(fieldsPerTry).as("the target call's first try and its redelivery").hasSize(2)
                .allSatisfy(fields -> assertThat(fields)
                        .containsEntry(CorrelationContext.CORRELATION_ID, CORRELATION_ID)
                        .containsEntry(CorrelationContext.TENANT_ID, StageFixture.TENANT_A.toString())
                        .containsEntry(CorrelationContext.DELIVERY_ID, delivery.get().toString()));
    }

    /**
     * Stage 1's write for a routed message as production runs it: the fan-out insert through
     * {@link JdbcStage1Deliveries} - one PENDING delivery and its {@code delivery.due} outbox row - in the
     * tenant-stamped transaction. The route and target ids carry no foreign key; the journey is under proof.
     * Returns the new delivery's id.
     */
    private UUID stageOneWrite(UUID message, UUID tenant) {
        try (Connection connection = db.app()) {
            SingleConnectionDataSource app = new SingleConnectionDataSource(connection, true);
            JdbcClient jdbc = JdbcClient.create(app);
            return new TransactionTemplate(new DataSourceTransactionManager(app)).execute(status -> {
                jdbc.sql("select set_config('app.tenant_id', :tenant, true)")
                        .param("tenant", tenant.toString()).query().singleValue();
                new JdbcStage1Deliveries(new JdbcDeliveryRepository(jdbc, () -> Optional.of(tenant)))
                        .insertPerRoute(List.of(new Stage1Delivery(message, UUID.randomUUID(), Instant.now(),
                                UUID.randomUUID(), null, null, null, null, null, null)));
                return jdbc.sql("select id from public.delivery where message_id = :message::uuid")
                        .param("message", message.toString()).query((row, n) -> row.getObject(1, UUID.class))
                        .single();
            });
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String storedTraceparent(UUID aggregate) throws Exception {
        try (Connection owner = db.owner();
                PreparedStatement row = owner.prepareStatement(
                        "select traceparent from public.outbox where aggregate_id = ?")) {
            row.setObject(1, aggregate);
            try (ResultSet stored = row.executeQuery()) {
                assertThat(stored.next()).as("the delivery.due outbox row exists").isTrue();
                return stored.getString(1);
            }
        }
    }

    private static void drainUntil(StageConsumer stage, java.util.function.BooleanSupplier done) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        while (!done.getAsBoolean() && Instant.now().isBefore(deadline)) {
            stage.drainOnce();
        }
    }

    /** One fresh one-partition topic on the real broker, a producer and a consumer of it. */
    private final class Wire implements AutoCloseable {

        private final String topic = "t107-journey-" + UUID.randomUUID();
        private final KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, db.kafkaBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
        private final KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, db.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "t107-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));

        Wire() throws Exception {
            try (AdminClient admin = AdminClient.create(Map.of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, db.kafkaBootstrapServers()))) {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
            }
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
        }

        void send(UUID rowId, String type, String traceparent) throws Exception {
            ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, 0, ("work-" + rowId).getBytes(),
                    ("{\"v\":1,\"type\":\"" + type + "\",\"id\":\"" + rowId + "\"}").getBytes());
            record.headers().add("traceparent", traceparent.getBytes());
            producer.send(record).get(10, TimeUnit.SECONDS);
        }

        StageConsumer stage(StageConsumer.ProtectedRows rows, StageConsumer.Handler handler) {
            return new StageConsumer(consumer, new WorkRecordParser(), rows, handler, (key, value, reason) -> {
                throw new AssertionError("a journey record is readable: " + reason);
            }, attempt -> Duration.ofMillis(200L * attempt), Duration.ofMillis(100), Clock.systemUTC(), Thread::sleep);
        }

        @Override
        public void close() {
            consumer.close(Duration.ofSeconds(2));
            producer.close(Duration.ofSeconds(2));
        }
    }
}
