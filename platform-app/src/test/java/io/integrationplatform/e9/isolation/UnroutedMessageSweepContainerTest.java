package io.integrationplatform.e9.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import io.integrationplatform.delivery.port.InstanceReadiness;
import io.integrationplatform.domain.scheduler.JobHandler;
import io.integrationplatform.tenancy.port.ActiveTenantDirectory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * T72 (E9 T-180 routing leg, production wiring): a message left {@code ACCEPTED} - its {@code message.accepted}
 * record was lost, so no consumer will ever route it - is routed by the real {@code DELIVERY_RETRY} job, through the
 * beans the application wires (the delivery-retry trigger, the {@code UnroutedMessageSweep} port, the stage-1
 * reconciler, the real stage 1), against the real message rows.
 *
 * <pre>
 * Scenario: A lost accepted record is repaired by the delivery-retry job
 *   Given a message ACCEPTED ten minutes ago whose record was never produced, and a fresh one accepted just now
 *   When the DELIVERY_RETRY job handler runs
 *   Then the old message is ROUTED with its delivery; the fresh one is left ACCEPTED (inside the threshold)
 * </pre>
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(E9TestNetwork.class)
class UnroutedMessageSweepContainerTest {

    private static final Duration SETTLE = Duration.ofSeconds(60);
    private static final UUID STUCK = UUID.fromString("72727272-0000-4000-8000-000000000001");
    private static final UUID FRESH = UUID.fromString("72727272-0000-4000-8000-000000000002");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(TestImages.KAFKA)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    private static RestrictedLogin login;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
        KAFKA.start();
        E9TestNetwork.createTheThreeTopics(KAFKA.getBootstrapServers());
        registry.add("IPASS_KAFKA_BOOTSTRAP_SERVERS", KAFKA::getBootstrapServers);
        registry.add("IPASS_KAFKA_PUBLISH_CLIENT_ID", () -> "t72-sweep");
        registry.add("platform.config.destinations.allowed-hosts", () -> E9TestNetwork.ALLOWED_HOSTS);
        registry.add("ROUTING_CORE_ALLOWED_SCHEMES", () -> "http,https");
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class GrantAppRoleWiring {
        @org.springframework.context.annotation.Bean
        org.flywaydb.core.api.callback.Callback t72GrantAppRole() {
            return E9TestNetwork.grantAppRoleAfterMigrate(POSTGRES, () -> login);
        }
    }

    @Autowired
    List<JobHandler> jobHandlers;

    @Autowired
    ActiveTenantDirectory activeTenants;

    @Autowired
    InstanceReadiness readiness;

    private static Stage1Environment env;
    private static TargetStub target;

    @BeforeEach
    void givenARouteAndTwoAcceptedMessagesWithNoRecord() throws Exception {
        if (env != null) {
            return;
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        env = new Stage1Environment(POSTGRES, login, KAFKA.getBootstrapServers());
        target = TargetStub.answering("/t72-200", 200);
        env.registerRoute("t72-target", target.baseUrl());
        // no produceAccepted: the record is "lost", only the rows exist
        env.seedStuckAcceptedMessage(STUCK, "t72-stuck", "{\"amount\":1}", Instant.now().minus(Duration.ofMinutes(10)));
        env.seedStuckAcceptedMessage(FRESH, "t72-fresh", "{\"amount\":2}", Instant.now());
    }

    @AfterAll
    static void stopTheTarget() {
        if (target != null) {
            target.stop();
        }
    }

    @Test
    void theDeliveryRetryJobRoutesAMessageStuckAcceptedAndLeavesAFreshOneAlone() {
        assertThat(stateOf(STUCK)).as("before the job: no record, so no consumer routed it").isEqualTo("ACCEPTED");

        assertThat(activeTenants.activeTenantIds()).as("the job loops this tenant")
                .contains(Stage1Environment.TENANT);
        assertThat(readiness.ready(null, null)).as("the readiness gate lets the job pass").isTrue();

        JobHandler deliveryRetry = jobHandlers.stream().filter(h -> "DELIVERY_RETRY".equals(h.type())).findFirst()
                .orElseThrow(() -> new AssertionError("no DELIVERY_RETRY job handler is wired"));
        deliveryRetry.run(null);

        Stage1Environment.pollUntil(SETTLE,
                () -> "ROUTED".equals(stateOf(STUCK)) ? java.util.Optional.of(true) : java.util.Optional.empty(),
                "the stuck message to be ROUTED by the DELIVERY_RETRY job (Stage1Reconciler.sweepOnce has no caller?)");
        assertThat(deliveriesOf(STUCK)).as("the stuck message got its delivery").isEqualTo(1);
        assertThat(stateOf(FRESH)).as("a message inside the threshold is left for its consumer").isEqualTo("ACCEPTED");

        deliveryRetry.run(null);

        assertThat(deliveriesOf(STUCK)).as("a second run re-routes nothing (idempotent)").isEqualTo(1);
    }

    private String stateOf(UUID message) {
        return env.queryListAsApp("select state from public.message where id = ?::uuid", String.class,
                message.toString()).stream().findFirst().orElse("MISSING");
    }

    private int deliveriesOf(UUID message) {
        return env.queryListAsApp("select count(*) from public.delivery where message_id = ?::uuid", Integer.class,
                message.toString()).get(0);
    }
}
