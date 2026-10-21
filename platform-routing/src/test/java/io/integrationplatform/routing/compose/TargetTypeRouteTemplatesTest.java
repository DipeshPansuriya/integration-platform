package io.integrationplatform.routing.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.delivery.port.SendOutcomes;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.LayerOneRouteBuilder;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.templates.RouteBuildRefusedException;
import io.integrationplatform.routing.templates.RouteConfigRow;
import io.integrationplatform.routing.templates.RouteInstance;
import io.integrationplatform.routing.templates.RouteInstanceFactory;
import io.integrationplatform.routing.templates.RouteTemplate;
import io.integrationplatform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Route templates for every target type (E18 run 2): MQ, KAFKA, DB and FILE each get a template that hands the
 * claimed {@code Stage2Send} to the ONE layer-1 route, exactly as {@link ApiRouteTemplate} does; the layer-1 target
 * call is picked by the stored system type there. A non-API template exists only when its adapter gate is on, so a gate
 * that is off still refuses the type - PERMANENT at stage 2, never dropped, never dialled through another adapter.
 */
@Tag("e18-route-templates")
class TargetTypeRouteTemplatesTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final String BOOTSTRAP = "IPASS_KAFKA_BOOTSTRAP_SERVERS";
    private static final String KAFKA_GATE = "IPASS_KAFKA_TOPIC_TARGET_ENABLED";
    private static final String DB_GATE = "IPASS_DB_TARGET_ENABLED";
    private static final String FILE_GATE = "IPASS_FILE_TARGET_ENABLED";

    private DefaultCamelContext camel;
    private ProducerTemplate producer;
    private final List<Object> layerOneBodies = new ArrayList<>();

    @BeforeEach
    void aRunningContextWithAStandInForLayerOne() throws Exception {
        camel = new DefaultCamelContext();
        camel.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from(LayerOneRouteBuilder.LAYER_ONE_SEND).process(e -> layerOneBodies.add(e.getIn().getBody()));
            }
        });
        camel.start();
        producer = camel.createProducerTemplate();
    }

    @AfterEach
    void stop() throws Exception {
        camel.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MQ", "KAFKA", "DB", "FILE"})
    void theFactoryBuildsAnInstanceOfTheTypeAndItsSendGoesThroughLayerOne(String type) {
        RouteInstanceFactory factory = factory(runner(BOOTSTRAP, "true", KAFKA_GATE, "true", DB_GATE, "true",
                FILE_GATE, "true"));
        RouteConfigRow row = row(type);
        Stage2Send send = send(row.targetEndpointId());

        RouteInstance instance = factory.ensure(row);
        Exchange done = producer.send(instance.consumerUri(), e -> e.getIn().setBody(send));

        assertThat(layerOneBodies).as("%s: the claimed send reached the one layer-1 route", type).containsExactly(send);
        assertThat(done.getException()).isNull();
        assertThat(done.getProperty(Exchange.EXCEPTION_CAUGHT)).isNull();
    }

    @Test
    void theApiTemplateIsUnchanged() {
        ApiRouteTemplate api = new ApiRouteTemplate(producer);
        assertThat(api.targetType()).isEqualTo("API").isEqualTo(ApiRouteTemplate.TARGET_TYPE);
    }

    @Test
    void aTemplateIsRegisteredOnlyWhenItsAdapterGateIsOn() {
        assertThat(typesOf(runner(BOOTSTRAP, "true"))).containsExactly("MQ");
        assertThat(typesOf(runner(BOOTSTRAP, "true", KAFKA_GATE, "true"))).containsExactlyInAnyOrder("MQ", "KAFKA");
        assertThat(typesOf(runner(BOOTSTRAP, "true", DB_GATE, "true"))).containsExactlyInAnyOrder("MQ", "DB");
        assertThat(typesOf(runner(BOOTSTRAP, "true", FILE_GATE, "true"))).containsExactlyInAnyOrder("MQ", "FILE");
        assertThat(typesOf(runner(BOOTSTRAP, "true", KAFKA_GATE, "false", DB_GATE, "false", FILE_GATE, "false")))
                .as("a gate set to false is off, as the adapter configuration reads it").containsExactly("MQ");
        assertThat(typesOf(runner())).as("no pipeline gate: no template at all").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"KAFKA", "DB", "FILE"})
    void aGatedOffTypeIsStillRefusedAndStageTwoRecordsItPermanent(String type) {
        RouteInstanceFactory factory = factory(runner(BOOTSTRAP, "true")); // only MQ (and API) are composed
        RouteConfigRow row = row(type);
        assertThatThrownBy(() -> factory.ensure(row)).isInstanceOf(RouteBuildRefusedException.class)
                .hasMessageContaining("no route template for target type '" + type + "'");

        UUID delivery = seed("delivery-" + type);
        UUID claim = UUID.randomUUID();
        JdbcDueDeliveries deliveries = mock(JdbcDueDeliveries.class);
        when(deliveries.claimed(delivery)).thenReturn(Optional.of(new JdbcDueDeliveries.DueDelivery(delivery,
                row.routeId(), row.targetEndpointId(), seed("iface"), "key-1")));
        TenantContext tenants = mock(TenantContext.class);
        when(tenants.requireTenantId()).thenReturn(seed("tenant"));
        SendOutcomes outcomes = mock(SendOutcomes.class);
        RouteInstanceSendDispatch stage2 = new RouteInstanceSendDispatch(TransactionOperations.withoutTransaction(),
                deliveries, tenants, (t, i) -> List.of(row), factory, producer, outcomes, () -> NOW);

        stage2.dispatch(new Stage2Send(delivery, claim, "key-1", row.targetEndpointId(), false));

        verify(outcomes).recordFailure(eq(delivery), eq(claim), eq(0), eq(OutcomeClass.PERMANENT), eq(false));
        assertThat(layerOneBodies).as("nothing reached layer 1").isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    @Configuration(proxyBeanMethods = false)
    @Import(TargetTypeRouteTemplatesConfiguration.class)
    static class Wiring {
        @Bean
        ProducerTemplate producerTemplate() {
            return mock(ProducerTemplate.class); // the templates only hold it; sends are driven by the test
        }
    }

    private ApplicationContextRunner runner(String... properties) {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);
        for (int i = 0; i < properties.length; i += 2) {
            runner = runner.withPropertyValues(properties[i] + "=" + properties[i + 1]);
        }
        return runner;
    }

    private List<String> typesOf(ApplicationContextRunner runner) {
        List<String> types = new ArrayList<>();
        runner.run(context -> context.getBeansOfType(RouteTemplate.class).values()
                .forEach(t -> types.add(t.targetType())));
        return types;
    }

    /** The factory over the templates the runner's wiring yields, over the REAL producer so the send is live. */
    private RouteInstanceFactory factory(ApplicationContextRunner runner) {
        List<RouteTemplate> templates = new ArrayList<>();
        runner.withBean("producerTemplate", ProducerTemplate.class, () -> producer,
                        bd -> ((org.springframework.beans.factory.support.AbstractBeanDefinition) bd)
                                .setDestroyMethodName("")) // the test owns the producer; the context must not close it
                .withAllowBeanDefinitionOverriding(true)
                .run(context -> templates.addAll(context.getBeansOfType(RouteTemplate.class).values()));
        templates.add(new ApiRouteTemplate(producer));
        return new RouteInstanceFactory((t, i) -> List.of(), camel, templates, Set.of("direct", "https"),
                Duration.ofMinutes(30), () -> NOW);
    }

    private static RouteConfigRow row(String type) {
        return new RouteConfigRow(seed("route-" + type), seed("endpoint-" + type), type,
                "https://target.example/" + type, null, null);
    }

    private static Stage2Send send(UUID endpoint) {
        return new Stage2Send(seed("delivery"), UUID.randomUUID(), "key-1", endpoint, false);
    }

    private static UUID seed(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
