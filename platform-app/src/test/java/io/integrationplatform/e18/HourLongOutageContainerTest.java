package io.integrationplatform.e18;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.delivery.replay.DeliveryOperatorActions;
import io.integrationplatform.domain.scheduler.JobHandler;
import io.integrationplatform.identity.KeycloakRealmContainer.ClientCredentials;
import io.integrationplatform.routing.stage2.TargetCall;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.testcontainers.containers.GenericContainer;

/**
 * T-152 (E18-F1-S2): an hour-long outage loses nothing - a target, Kafka, the JMS broker.
 *
 * <p><b>Time compression, stated.</b> Nothing sleeps an hour. The platform's retry schedule lives in
 * {@code delivery.next_attempt_at} and the retry job ({@code DELIVERY_RETRY}, the production
 * {@code JobHandler}) selects rows whose time has come, so the suite plays the hour by moving each waiting delivery's
 * {@code next_attempt_at} to "now" and running the real job - one backoff wait is skipped per round, and the skipped
 * waits are summed as <i>simulated outage time</i> until it passes 3600 s. The platform's {@code AdjustableClock}
 * is not used: the retry trigger reads the wall clock ({@code DeliveryRetryTrigger.redriveDue}), so that seam would
 * not move it. The breaker's 30 s open wait (resilience4j, nanoTime) is NOT compressed: the suite waits it for real.
 * Kafka and the JMS broker are paused with {@code docker pause} for 60 s of real time, standing for 3600 s (60:1).
 *
 * <p><b>What this does not prove:</b> that a real timer fires after an hour (db-scheduler cadence, process uptime),
 * connection-pool or TCP keepalive ageing over an hour, broker retention or session expiry beyond 60 s, memory growth
 * during a real hour of backlog, or the jitter distribution (the draws are the platform's own).
 */
@DisplayName("E18-F1-S2 an hour-long outage loses nothing (T-152)")
class HourLongOutageContainerTest extends E18Base {

    private static final long SIMULATED_OUTAGE_SECONDS = 3600;
    private static final Duration BREAKER_OPEN_WAIT = Duration.ofSeconds(31);
    private static final Duration PAUSE = Duration.ofSeconds(60);

    @Autowired
    List<JobHandler> jobHandlers;

    @Autowired
    DeliveryOperatorActions operatorActions;

    @Autowired
    @Qualifier("apiTargetCall")
    TargetCall apiTargetCall;

    // ---------------------------------------------------------------------------------------------- a target

    @Test
    @DisplayName("a target down for an hour of simulated time: retries, backoff cap, breaker open/half-open, no duplicate")
    void apiTargetDownForAnHourRecoversWithoutDuplicates() throws Exception {
        grantAppRoleOnce();
        String path = "/outage-target/orders";
        int messages = 5;
        ClientCredentials source = newSource("e18-outage");
        E18Seed.Tenant tenant = onboardApi("outage", source, path);
        List<String> transitions = new CopyOnWriteArrayList<>();
        breakerOf(tenant.endpointId()).getEventPublisher().onStateTransition(
                event -> transitions.add(event.getStateTransition().name()));

        PARTNER.down(); // connect refused: a real outage, not a 503
        long realStart = System.nanoTime();
        List<UUID> accepted = new ArrayList<>();
        for (int i = 1; i <= messages; i++) {
            accepted.add(post(source, E18Seed.INTERFACE_CODE,
                    "{\"customerId\":\"CUST-OUTAGE\",\"orderId\":\"ORD-OUT-" + i + "\",\"amount\":" + i + "}", null));
        }
        assertThat(accepted).as("every message was accepted with a tracking id while the target was down")
                .hasSize(messages);

        await("stage 1 to create the " + messages + " delivery rows (the loop below needs them)",
                () -> ownerLong("select count(*) from public.delivery where tenant_id = ?::uuid", tenant.id()) == messages);

        // phase A: the target stays down while one hour of backoff waits is played
        double simulated = 0;
        List<Double> waitsSeconds = new ArrayList<>();
        int round = 0;
        while (simulated < SIMULATED_OUTAGE_SECONDS && round < 12 && nonFinal(tenant) > 0) {
            round++;
            awaitAllWaiting(tenant, messages, "round " + round + ": every delivery to reach RETRY_WAIT");
            double longest = longestWaitSeconds(tenant);
            double cap = Math.min(30.0 * Math.pow(2, round), 3600.0) + 5; // ADR-010: base 30 s, doubling, 1 h ceiling
            assertThat(longest).as("round %d: the scheduled wait stays inside the backoff bound/cap", round)
                    .isLessThanOrEqualTo(cap);
            waitsSeconds.add(longest);
            simulated += longest;
            makeDueAndRun(tenant);
        }
        long deadLetteredInOutage = countState(tenant, "DEAD_LETTERED");

        // phase B: the target returns; the breaker's real open wait passes; the retries complete
        PARTNER.up();
        if (!transitions.isEmpty()) {
            TimeUnit.MILLISECONDS.sleep(BREAKER_OPEN_WAIT.toMillis());
        }
        for (int again = 0; again < 14 && nonFinal(tenant) > 0; again++) {
            awaitAllWaitingOrFinal(tenant, "retry round after the target returned");
            makeDueAndRun(tenant);
            TimeUnit.SECONDS.sleep(2);
        }
        await("every delivery to settle after the target returned", Duration.ofSeconds(120),
                () -> nonFinal(tenant) == 0);

        double realSeconds = (System.nanoTime() - realStart) / 1e9;
        note("apiTargetDown: simulated outage " + Math.round(simulated) + " s over " + round + " rounds (waits "
                + waitsSeconds + "), real " + Math.round(realSeconds) + " s, compression ~"
                + Math.round(simulated / realSeconds) + ":1, dead-lettered during the outage: " + deadLetteredInOutage
                + ", breaker transitions " + transitions);

        // acceptance (Mira 2026-10-22): no accepted message is lost; each is delivered or replayable
        assertThat(simulated >= SIMULATED_OUTAGE_SECONDS || deadLetteredInOutage > 0)
                .as("the outage reached one hour of simulated time, or the attempts ran out first (waits %s s)", waitsSeconds)
                .isTrue();
        List<String> afterRecovery = states(tenant);
        assertThat(afterRecovery).as("no accepted message is lost: one delivery row per message, all final")
                .hasSize(messages).allMatch(state -> state.equals("DELIVERED") || state.equals("DEAD_LETTERED"));
        // a dead-lettered delivery must be replayable by the operator, and then complete
        for (UUID deadLettered : deadLetteredDeliveries(tenant)) {
            var result = readTenant.as(tenant.id(), () -> operatorActions.replay(deadLettered, "e18-suite"));
            assertThat(result.outcome()).as("operator replay of %s", deadLettered)
                    .isEqualTo(DeliveryOperatorActions.Outcome.MOVED_TO_DUE);
        }
        await("every replayed delivery to be delivered", Duration.ofSeconds(120),
                () -> countState(tenant, "DELIVERED") == messages);
        assertThat(states(tenant)).as("every accepted message ends DELIVERED (dead-lettered ones after replay)")
                .hasSize(messages).containsOnly("DELIVERED");
        List<String> keys = PARTNER.acceptedKeysOn(path);
        assertThat(keys).as("the partner received each delivery exactly once - no duplicate").hasSize(messages)
                .doesNotHaveDuplicates();
        assertThat(transitions).as("the breaker opened during the outage").contains("CLOSED_TO_OPEN");
        assertThat(transitions).as("the breaker went half-open after its real wait, then closed on recovery")
                .contains("OPEN_TO_HALF_OPEN", "HALF_OPEN_TO_CLOSED");
        assertThat(ownerLong("select count(*) from public.delivery_attempt a join public.delivery d on d.id ="
                + " a.delivery_id where d.tenant_id = ?::uuid", tenant.id()))
                .as("retries are recorded as append-only attempts, more than one per delivery")
                .isGreaterThan(messages);
    }

    // ----------------------------------------------------------------------------------------------- Kafka

    @Test
    @DisplayName("Kafka down: intake still accepts, every accepted message stays in the outbox and is published after")
    void kafkaDownIntakeKeepsEveryAcceptedMessage() throws Exception {
        grantAppRoleOnce();
        String path = "/outage-kafka/orders";
        int messages = 5;
        ClientCredentials source = newSource("e18-kafka-out");
        E18Seed.Tenant tenant = onboardApi("kafkaout", source, path);

        var docker = KAFKA.getDockerClient();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        List<UUID> accepted = new ArrayList<>();
        try {
            for (int i = 1; i <= messages; i++) {
                accepted.add(post(source, E18Seed.INTERFACE_CODE, "{\"customerId\":\"CUST-KAFKAOUT\",\"orderId\":"
                        + "\"ORD-KO-" + i + "\",\"amount\":" + i + "}", null));
            }
            TimeUnit.MILLISECONDS.sleep(PAUSE.toMillis()); // 60 s real for the simulated hour (60:1)
            long unpublished = ownerLong("select count(*) from public.outbox where tenant_id = ?::uuid"
                    + " and published_at is null", tenant.id());
            assertThat(unpublished).as("while Kafka is down every accepted message stays unpublished in the outbox "
                    + "(nothing acknowledged that is not committed)").isEqualTo(messages);
            assertThat(ownerLong("select count(*) from public.message where tenant_id = ?::uuid", tenant.id()))
                    .as("the accepted messages are committed rows").isEqualTo(messages);
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        for (UUID message : accepted) {
            awaitSettled(message, 1);
        }
        assertThat(states(tenant)).as("every accepted message was delivered after Kafka returned")
                .hasSize(messages).containsOnly("DELIVERED");
        assertThat(PARTNER.acceptedKeysOn(path)).as("each delivered exactly once").hasSize(messages)
                .doesNotHaveDuplicates();
        note("kafkaDown: real pause " + PAUSE.toSeconds() + " s standing for 3600 s (60:1); outbox held " + messages
                + " rows, all published after unpause");
    }

    // -------------------------------------------------------------------------------------------- JMS broker

    @Test
    @DisplayName("the JMS broker down: MQ-CSV deliveries wait, then resume once each")
    void jmsBrokerDownMqCsvDeliveriesResume() throws Exception {
        grantAppRoleOnce();
        String queue = "e18.orders.outage";
        int messages = 3;
        ClientCredentials source = newSource("e18-jms-out");
        String settings = "{\"host\":\"mq.e18.test\",\"port\":" + ARTEMIS.getMappedPort(ARTEMIS_PORT)
                + ",\"queue\":\"" + queue + "\",\"contract\":{\"columns\":[{\"name\":\"order_no\",\"type\":\"text\"},"
                + "{\"name\":\"customer\",\"type\":\"text\"},{\"name\":\"total\",\"type\":\"number\"}],"
                + "\"delimiter\":\",\",\"quote\":\"\\\"\",\"quoteAll\":false,\"encoding\":\"UTF-8\","
                + "\"lineEnding\":\"CRLF\",\"header\":true}}";
        E18Seed.Tenant tenant;
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            tenant = E18Seed.onboard(owner, "jmsout", "CUST-JMSOUT", source.clientId(), KEYCLOAK.issuerUri(), "MQ",
                    settings, "mq://mq.e18.test/" + queue, true);
        }
        GenericContainer<?> broker = ARTEMIS;
        var docker = broker.getDockerClient();
        docker.pauseContainerCmd(broker.getContainerId()).exec();
        double simulated = 0;
        try {
            for (int i = 1; i <= messages; i++) {
                post(source, E18Seed.INTERFACE_CODE, "{\"customerId\":\"CUST-JMSOUT\",\"orderId\":\"ORD-JO-" + i
                        + "\",\"amount\":" + i + "}", null);
            }
            for (int round = 1; round <= 3; round++) {
                awaitAllWaiting(tenant, messages, "round " + round + ": every MQ delivery to reach RETRY_WAIT while"
                        + " the broker is paused (last attempt: " + "see delivery_attempt" + ")");
                simulated += longestWaitSeconds(tenant);
                makeDueAndRun(tenant);
            }
            TimeUnit.MILLISECONDS.sleep(PAUSE.toMillis());
        } finally {
            docker.unpauseContainerCmd(broker.getContainerId()).exec();
        }
        TimeUnit.MILLISECONDS.sleep(BREAKER_OPEN_WAIT.toMillis());
        for (int again = 0; again < 14 && nonFinal(tenant) > 0; again++) {
            awaitAllWaitingOrFinal(tenant, "MQ retry round after the broker returned");
            makeDueAndRun(tenant);
            TimeUnit.SECONDS.sleep(2);
        }
        await("every MQ delivery to settle after the broker returned", Duration.ofSeconds(120),
                () -> nonFinal(tenant) == 0);
        note("jmsBrokerDown: simulated " + Math.round(simulated) + " s of backoff plus a real " + PAUSE.toSeconds()
                + " s pause");

        assertThat(states(tenant)).as("every accepted message was delivered after the broker returned")
                .hasSize(messages).containsOnly("DELIVERED");
        assertThat(countOnQueue(queue)).as("exactly one message per delivery on the queue - no duplicate")
                .isEqualTo(messages);
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private E18Seed.Tenant onboardApi(String word, ClientCredentials source, String path) throws Exception {
        String url = "http://api.e18.test:" + PARTNER.port() + path;
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            return E18Seed.onboard(owner, word, "CUST-" + word.toUpperCase(), source.clientId(), KEYCLOAK.issuerUri(),
                    "API", "{\"baseUrl\":\"" + url + "\"}", url, true);
        }
    }

    /** The platform's own breaker of the endpoint (private to ApiTargetCall): read to observe the state transitions. */
    private CircuitBreaker breakerOf(UUID endpointId) throws Exception {
        Field field = apiTargetCall.getClass().getDeclaredField("breakers");
        field.setAccessible(true);
        return ((CircuitBreakerRegistry) field.get(apiTargetCall)).circuitBreaker(endpointId.toString());
    }

    /** One real run of the production retry job. */
    private void runRetryJob() {
        jobHandlers.stream().filter(handler -> "DELIVERY_RETRY".equals(handler.type())).findFirst()
                .orElseThrow(() -> new AssertionError("no DELIVERY_RETRY job handler is registered"))
                .run(null);
    }

    /** Plays the passing of the schedule: every waiting delivery of the tenant becomes due now, then the job runs. */
    private void makeDueAndRun(E18Seed.Tenant tenant) throws Exception {
        asOwner(owner -> E18Seed.execute(owner, "update public.delivery set next_attempt_at = now() - interval"
                + " '1 second' where tenant_id = ?::uuid and state = 'RETRY_WAIT'", tenant.id()));
        runRetryJob();
    }

    private static double longestWaitSeconds(E18Seed.Tenant tenant) {
        String value = ownerString("select coalesce(max(extract(epoch from (next_attempt_at - now()))), 0)"
                + " from public.delivery where tenant_id = ?::uuid and state = 'RETRY_WAIT'", tenant.id());
        return Math.max(0, Double.parseDouble(value));
    }

    private static long nonFinal(E18Seed.Tenant tenant) {
        return ownerLong("select count(*) from public.delivery where tenant_id = ?::uuid"
                + " and state not in ('DELIVERED', 'DEAD_LETTERED')", tenant.id());
    }

    private static List<UUID> deadLetteredDeliveries(E18Seed.Tenant tenant) throws Exception {
        List<UUID> ids = new ArrayList<>();
        asOwner(owner -> {
            try (var read = owner.prepareStatement("select id from public.delivery where tenant_id = ?::uuid"
                    + " and state = 'DEAD_LETTERED'")) {
                read.setString(1, tenant.id().toString());
                try (var rows = read.executeQuery()) {
                    while (rows.next()) {
                        ids.add(rows.getObject(1, UUID.class));
                    }
                }
            }
        });
        return ids;
    }

    private static long countState(E18Seed.Tenant tenant, String state) {
        return ownerLong("select count(*) from public.delivery where tenant_id = ?::uuid and state = ?", tenant.id(),
                state);
    }

    private static List<String> states(E18Seed.Tenant tenant) throws Exception {
        List<String> states = new ArrayList<>();
        asOwner(owner -> {
            try (var read = owner.prepareStatement("select state from public.delivery where tenant_id = ?::uuid")) {
                read.setString(1, tenant.id().toString());
                try (var rows = read.executeQuery()) {
                    while (rows.next()) {
                        states.add(rows.getString(1));
                    }
                }
            }
        });
        return states;
    }

    private static void awaitAllWaiting(E18Seed.Tenant tenant, int expected, String link) {
        await(link, () -> ownerLong("select count(*) from public.delivery where tenant_id = ?::uuid"
                + " and state = 'RETRY_WAIT' and next_attempt_at > now()", tenant.id()) + countState(tenant,
                        "DEAD_LETTERED") >= expected);
    }

    private static void awaitAllWaitingOrFinal(E18Seed.Tenant tenant, String link) {
        await(link, () -> ownerLong("select count(*) from public.delivery where tenant_id = ?::uuid"
                + " and state in ('PENDING', 'DUE', 'SENDING', 'HELD')", tenant.id()) == 0);
    }

    private static long countOnQueue(String queue) throws Exception {
        long count = 0;
        try (jakarta.jms.Connection connection = new org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory(
                "tcp://127.0.0.1:" + ARTEMIS.getMappedPort(ARTEMIS_PORT)).createConnection()) {
            connection.start();
            jakarta.jms.Session session = connection.createSession(false, jakarta.jms.Session.AUTO_ACKNOWLEDGE);
            jakarta.jms.MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
            while (consumer.receive(count == 0 ? 8000 : 2500) != null) {
                count++;
            }
        }
        return count;
    }

    /** Numbers the run report quotes (compression factor, rounds, transitions). */
    private static void note(String line) throws Exception {
        java.nio.file.Path file = E18Outcomes.FILE.resolveSibling("notes.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        System.out.println("E18 NOTE " + line);
    }

}
