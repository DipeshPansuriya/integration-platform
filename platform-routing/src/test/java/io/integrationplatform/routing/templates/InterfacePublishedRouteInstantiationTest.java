package io.integrationplatform.routing.templates;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.flow.api.InterfacePublished;
import io.integrationplatform.routing.templates.RouteInstanceFactoryTest.FakeRouteConfigReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.EventNotifierSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * T-116 (row 278 T68, sanjay-developer; E9-F1-S1, ADR-010 section 3): a published interface on the running
 * system runs its routes from the configuration rows - the config publish path's {@link InterfacePublished}
 * announcement reaches the routing module's listener, which validates the rows and builds nothing (ADR-010 s3
 * amendment, row 278 T106); stage 2 instantiates each active row lazily through its target type's template, and a
 * message sent to the instance's consumer runs that route.
 * 0 deploys, 0 restarts: the one Camel context started before the publish is never stopped or started again.
 */
@Tag("e9-t116")
class InterfacePublishedRouteInstantiationTest {

    private static final UUID TENANT = seed("tenant-a");
    private static final UUID INTERFACE_ID = seed("iface-1");
    private static final UUID ENDPOINT_A = seed("endpoint-a");
    private static final UUID ENDPOINT_B = seed("endpoint-b");
    private static final UUID MAPPING_M3 = seed("mapping-m3");

    private final AtomicInteger contextStartsOrStops = new AtomicInteger();
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private DefaultCamelContext camel;
    private FakeRouteConfigReader rows;
    private AnnotationConfigApplicationContext spring;
    private RouteInstanceFactory factory;

    @BeforeEach
    void aRunningSystem() throws Exception {
        camel = new DefaultCamelContext();
        camel.start(); // the system is running before anything is published
        camel.getManagementStrategy().addEventNotifier(new EventNotifierSupport() {
            @Override
            public void notify(CamelEvent event) {
                switch (event.getType()) {
                    case CamelContextStarting, CamelContextStopping, CamelContextReloading -> contextStartsOrStops
                            .incrementAndGet();
                    default -> { }
                }
            }
        });
        rows = new FakeRouteConfigReader();
        RouteTemplate api = new RouteTemplate() {
            @Override
            public String targetType() {
                return "API";
            }

            @Override
            public org.apache.camel.Processor build(RouteConfigRow row) {
                return exchange -> sent.add(row.targetEndpointId() + "<-" + exchange.getMessage().getBody(String.class));
            }
        };
        factory = new RouteInstanceFactory(rows, camel, List.of(api), Set.of("https"),
                Duration.ofMinutes(30), () -> Instant.parse("2026-10-15T12:00:00Z"));

        spring = new AnnotationConfigApplicationContext();
        spring.registerBean(RouteInstanceFactory.class, () -> factory);
        spring.register(RouteTemplatesConfiguration.class);
        spring.refresh();
    }

    @AfterEach
    void stopTheSystem() throws Exception {
        spring.close();
        camel.stop();
    }

    @Test
    void publishingAnInterfaceRunsItsRoutesFromTheConfigurationRowsWithoutARestart() {
        rows.add(new RouteConfigRow(seed("route-1"), ENDPOINT_A, "API", "https://target-a.example/v1", null, MAPPING_M3));
        rows.add(new RouteConfigRow(seed("route-2"), ENDPOINT_B, "API", "https://target-b.example/v1", null, MAPPING_M3));
        assertThat(camel.getRoutes()).as("nothing is instantiated before the publish").isEmpty();

        spring.publishEvent(new InterfacePublished(TENANT, INTERFACE_ID, 3));

        assertThat(camel.getRoutes()).as("AC-1: the publish validates the rows and builds nothing").isEmpty();
        rows.rows.values().forEach(factory::ensure); // stage 2 builds each row's instance lazily on first use
        assertThat(camel.getRoutes()).as("one live route instance per active configuration row").hasSize(2);
        try (ProducerTemplate producer = camel.createProducerTemplate()) {
            producer.sendBody(factory.instanceOf(ENDPOINT_A).orElseThrow().consumerUri(), "m-1");
            producer.sendBody(factory.instanceOf(ENDPOINT_B).orElseThrow().consumerUri(), "m-2");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(sent).as("each published route runs the send its row's template built")
                .containsExactly(ENDPOINT_A + "<-m-1", ENDPOINT_B + "<-m-2");
        assertThat(camel.isStarted()).as("the same running context").isTrue();
        assertThat(contextStartsOrStops).as("0 restarts: the context was never stopped or started again").hasValue(0);
    }

    private static UUID seed(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
