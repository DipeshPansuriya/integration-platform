package io.integrationplatform.routing.compose;

import io.integrationplatform.delivery.DeliveryClaimService;
import io.integrationplatform.delivery.port.SendOutcomes;
import io.integrationplatform.delivery.port.Stage1Deliveries;
import io.integrationplatform.routing.adapter.api.TargetEndpointViews;
import io.integrationplatform.routing.claim.DeliveryClaimHandler;
import io.integrationplatform.routing.poison.PoisonTopic;
import io.integrationplatform.routing.stage.StageConsumer;
import io.integrationplatform.routing.stage1.JdbcStage1Reads;
import io.integrationplatform.routing.stage1.Stage1FanOut;
import io.integrationplatform.routing.stage2.LayerOneRouteBuilder;
import io.integrationplatform.routing.stage2.LayerOneWaits;
import io.integrationplatform.routing.stage2.Stage2AttemptWriter;
import io.integrationplatform.routing.stage2.Stage2OutcomeHandoff;
import io.integrationplatform.routing.stage2.TargetCall;
import io.integrationplatform.routing.templates.JdbcRouteConfigReader;
import io.integrationplatform.routing.templates.RouteConfigReader;
import io.integrationplatform.routing.templates.RouteInstanceFactory;
import io.integrationplatform.routing.templates.RouteTemplate;
import io.integrationplatform.routing.topics.Topics;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import io.integrationplatform.tenancy.port.WorkTenantLookup;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import io.integrationplatform.config.credential.readiness.InstanceKeyReadiness;
import io.integrationplatform.delivery.port.InstanceReadiness;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The running pipeline's wiring (E11 T-049, row 278 T106) - see the package documentation for the flow. Every
 * transaction here is the application's tenant-stamping one; the tenant of a unit of work is the
 * {@link WorkerTenantBinding} a stage handler sets from the protected path, which the application's TenantContext
 * answers on that thread. Kafka and Camel stay in this module (R12, ADR-010 section 1).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("IPASS_KAFKA_BOOTSTRAP_SERVERS")
class PipelineCompositionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PipelineCompositionConfiguration.class);

    /** The idle window of ADR-010 section 3 (route instances are local state, rebuilt on demand). */
    private static final Duration ROUTE_IDLE = Duration.ofMinutes(30);

    @Bean
    WorkerTenantBinding workerTenantBinding() {
        return new WorkerTenantBinding();
    }

    // ------------------------------------------------------------------ stage 2's send: layer 1 + the API route

    /**
     * The ONE layer-1 route (T-119) with the API adapter as its target call (ADR-015, {@code ApiAdapterConfiguration}).
     * The call runs inside one tenant-stamped transaction: the adapter's endpoint view and the transform body read
     * the tenant's rows on it (R08/R11) - the shape the E10 container proof composes. Attempt rows and the outcome
     * hand-off each commit in their own short transaction, as the recorder path does.
     */
    @Bean
    LayerOneRouteBuilder layerOneRoute(TargetCall apiTargetCall, SendOutcomes outcomes,
            PlatformTransactionManager transactionManager) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        TargetCall inTenantTransaction = send -> transactions.executeWithoutResult(status -> apiTargetCall.call(send));
        Stage2AttemptWriter attempts = (deliveryId, layer, outcome, startedAt, finishedAt, detail) ->
                transactions.executeWithoutResult(status ->
                        outcomes.recordAttempt(deliveryId, layer, outcome, startedAt, finishedAt, detail));
        Stage2OutcomeHandoff handoff = (deliveryId, claimToken, attemptsSoFar, outcome, idempotent) ->
                transactions.executeWithoutResult(status ->
                        outcomes.recordFailure(deliveryId, claimToken, attemptsSoFar, outcome, idempotent));
        return new LayerOneRouteBuilder(inTenantTransaction, attempts, handoff, LayerOneWaits.defaults());
    }

    @Bean
    ApiRouteTemplate apiRouteTemplate(ProducerTemplate producerTemplate) {
        return new ApiRouteTemplate(producerTemplate);
    }

    @Bean
    RouteConfigReader routeConfigReader(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcRouteConfigReader(jdbcClient, tenantContext);
    }

    /**
     * The route instances (T-116): built from the configuration rows on publish (the T68 listener finds this bean)
     * and lazily on the first delivery that needs one. The scheme guard's list is {@link #allowedSchemes}.
     */
    @Bean
    RouteInstanceFactory routeInstanceFactory(RouteConfigReader routeConfigReader, CamelContext camelContext,
            List<RouteTemplate> templates, Environment environment) {
        return new RouteInstanceFactory(routeConfigReader, camelContext, templates, allowedSchemes(environment),
                ROUTE_IDLE, Instant::now);
    }

    /** The scheduled idle removal of ADR-010 section 3 (F16): every minute, instances idle past 30 minutes go. */
    @Bean
    RouteIdleSweep routeIdleSweep(RouteInstanceFactory routeInstanceFactory) {
        return new RouteIdleSweep(routeInstanceFactory, Instant::now);
    }

    /**
     * The T-120 seam (amit's T71 owns the allowedSchemes rule; this is only where its list is read): the operator's
     * {@code ROUTING_CORE_ALLOWED_SCHEMES} (comma separated, e.g. {@code http,https}). Unset means no scheme is
     * approved - every route build is refused and its deliveries dead-letter PERMANENT (fail closed, R07: no default).
     */
    static Set<String> allowedSchemes(Environment environment) {
        String configured = environment.getProperty("ROUTING_CORE_ALLOWED_SCHEMES", "");
        Set<String> schemes = Arrays.stream(configured.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (schemes.isEmpty()) {
            log.warn("ROUTING_CORE_ALLOWED_SCHEMES is not set: no endpoint URI scheme is approved, every route build"
                    + " is refused");
        }
        return schemes;
    }

    // ------------------------------------------------------------------ the stage handlers

    @Bean
    Stage1FanOut stage1FanOut(WorkerTenantBinding binding, PlatformTransactionManager transactionManager,
            JdbcClient jdbcClient, TenantContext tenantContext, Stage1Deliveries stage1Deliveries) {
        return new Stage1FanOut(binding, new TransactionTemplate(transactionManager),
                new JdbcStage1Reads(jdbcClient, tenantContext), stage1Deliveries);
    }

    @Bean
    DueDeliverySend dueDeliverySend(WorkerTenantBinding binding, PlatformTransactionManager transactionManager,
            JdbcClient jdbcClient, TenantContext tenantContext, DeliveryClaimService claimService,
            RouteConfigReader routeConfigReader, RouteInstanceFactory routeInstanceFactory,
            TargetEndpointViews targetEndpointViews, ProducerTemplate producerTemplate, SendOutcomes outcomes) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        return new DueDeliverySend(binding, transactions, new JdbcDueDeliveries(jdbcClient, tenantContext),
                new DeliveryClaimHandler(transactions, claimService), routeConfigReader, routeInstanceFactory,
                targetEndpointViews, producerTemplate, outcomes, Instant::now);
    }

    @Bean
    ProtectedWorkRows protectedWorkRows(WorkTenantLookup workTenantLookup, WorkerTenantBinding binding,
            PlatformTransactionManager transactionManager, JdbcClient jdbcClient, TenantContext tenantContext) {
        return new ProtectedWorkRows(workTenantLookup, binding, new TransactionTemplate(transactionManager),
                jdbcClient, tenantContext);
    }

    // ------------------------------------------------------------------ the two stage consumers

    @Bean(destroyMethod = "close")
    Producer<byte[], byte[]> poisonProducer(@Value("${platform.routing.kafka.bootstrap-servers}") String bootstrap) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "platform-poison");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(config);
    }

    @Bean
    StageConsumerLoop stage1Consumer(@Value("${platform.routing.kafka.bootstrap-servers}") String bootstrap,
            ProtectedWorkRows protectedWorkRows, Stage1FanOut stage1FanOut, Producer<byte[], byte[]> poisonProducer,
            ObjectProvider<MeterRegistry> meters, ObjectProvider<ObservationRegistry> observations,
            ObjectProvider<InstanceKeyReadiness> keyReadiness) {
        return loop(bootstrap, "platform-stage1", Topics.MESSAGE_ACCEPTED, protectedWorkRows, stage1FanOut,
                poisonProducer, meters, observations, keyReadiness);
    }

    @Bean
    StageConsumerLoop stage2Consumer(@Value("${platform.routing.kafka.bootstrap-servers}") String bootstrap,
            ProtectedWorkRows protectedWorkRows, DueDeliverySend dueDeliverySend,
            Producer<byte[], byte[]> poisonProducer, ObjectProvider<MeterRegistry> meters,
            ObjectProvider<ObservationRegistry> observations, ObjectProvider<InstanceKeyReadiness> keyReadiness) {
        return loop(bootstrap, "platform-stage2", Topics.DELIVERY_DUE, protectedWorkRows, dueDeliverySend,
                poisonProducer, meters, observations, keyReadiness);
    }

    private static StageConsumerLoop loop(String bootstrap, String group, String topic,
            StageConsumer.ProtectedRows rows, StageConsumer.Handler handler, Producer<byte[], byte[]> poisonProducer,
            ObjectProvider<MeterRegistry> meters, ObjectProvider<ObservationRegistry> observations,
            ObjectProvider<InstanceKeyReadiness> keyReadiness) {
        return new StageConsumerLoop(bootstrap, group, topic, rows, handler, new PoisonTopic(poisonProducer),
                meters.getIfAvailable(SimpleMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP), ready(keyReadiness));
    }

    /** T-201: the delivery retry pass asks the same key readiness as the stage consumers. */
    @Bean
    InstanceReadiness instanceReadiness(ObjectProvider<InstanceKeyReadiness> keyReadiness) {
        BooleanSupplier ready = ready(keyReadiness);
        return (tenantId, deliveryId) -> ready.getAsBoolean();
    }

    private static BooleanSupplier ready(ObjectProvider<InstanceKeyReadiness> keyReadiness) {
        InstanceKeyReadiness keys = keyReadiness.getIfAvailable();
        return keys != null ? keys : () -> true;
    }
}
