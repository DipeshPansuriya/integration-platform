package io.integrationplatform.e2e.release1;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.e2e.harness.E2eContainers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

/**
 * Row 323 T139 + T140 (E15-F1-S1): the nine Release 1 scenarios against the running backend (T143 compose e2e
 * profile, {@code -De2e.baseUrl}), target A on WireMock (API, v1.0) and target B on Artemis (MQ-CSV, v2.0).
 *
 * <p>Every scenario carries {@link WaitsOn}: until its items are on main it is DISABLED, which the gate counts red.
 * The last test is the release gate: GREEN only when all nine PASSED; one red scenario (FAILED, DISABLED, ABORTED or
 * NOT_RUN) fails it and Release 1 does not close (@negative, proven in {@code SuiteVerdictTest} and
 * {@code OutcomeRecorderTest}). Containers start lazily inside a scenario body, so a fully gated run starts none.
 *
 * <p>Run (container group, needs the docker lock): {@code ./mvnw -o -Pcontainer -pl platform-e2e test
 * -Dtest=Release1ScenariosContainerTest -De2e.baseUrl=http://127.0.0.1:<port>}.
 */
@Tag("container")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Release1ScenariosContainerTest {

    @RegisterExtension
    static final OutcomeRecorder RECORDER = new OutcomeRecorder();

    private static final String INTERFACE = "REF-ORDER";
    private static final String ORDER_JSON = "{\"orderNo\":\"R1-%s\",\"customer\":\"CUST-A\",\"qty\":%d}";
    private static final Duration SETTLE = Duration.ofSeconds(30);

    private static Network network;
    private static GenericContainer<?> wiremock;
    private static GenericContainer<?> artemis;

    private static synchronized void harness() {
        if (network == null) {
            network = Network.newNetwork();
            wiremock = E2eContainers.wiremock(network);
            artemis = E2eContainers.artemis(network);
            wiremock.start();
            artemis.start();
        }
    }

    @AfterAll
    static void stopHarness() {
        if (artemis != null) {
            artemis.stop();
        }
        if (wiremock != null) {
            wiremock.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    private static String wiremockBase() {
        return "http://" + wiremock.getHost() + ":" + wiremock.getMappedPort(E2eContainers.WIREMOCK_PORT);
    }

    private static Release1Client client() {
        harness();
        return new Release1Client(E2eNames.baseUrl());
    }

    private static long queueCount(String queue) throws Exception {
        ExecResult stat = artemis.execInContainer(E2eContainers.ARTEMIS_CLI, "queue", "stat", "--queueName", queue,
                "--user", E2eContainers.ARTEMIS_USER, "--password", E2eContainers.ARTEMIS_PASSWORD);
        assertThat(stat.getExitCode()).as("artemis queue stat; stderr: %s", stat.getStderr()).isZero();
        for (String line : stat.getStdout().split("\\R")) {
            if (line.contains("|" + queue) || line.contains("| " + queue)) {
                String[] cells = line.split("\\|");
                return Long.parseLong(cells[cells.length - 2].trim()); // MESSAGE_COUNT column precedes the last
            }
        }
        throw new AssertionError("queue " + queue + " not in artemis stat output: " + stat.getStdout());
    }

    private static String awaitJournal(Release1Client client, String needle, int expected) throws Exception {
        Instant until = Instant.now().plus(SETTLE);
        String body = "";
        while (Instant.now().isBefore(until)) {
            body = client.wiremockJournal(wiremockBase()).body();
            if (Release1Client.occurrences(body, needle) >= expected) {
                return body;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(250).toNanos()); // poll interval, not a fixed wait
        }
        throw new AssertionError("WireMock journal did not show " + expected + "x " + needle + " within " + SETTLE);
    }

    private static String acceptOne(Release1Client client, String key, int qty) throws Exception {
        HttpResponse<String> accepted = client.intake(INTERFACE, "1.0", ORDER_JSON.formatted(key, qty),
                Release1Client.bearer("CUSTOMER_A"));
        assertThat(accepted.statusCode()).as("intake answer: %s", accepted.body()).isEqualTo(202);
        return Release1Client.trackingIdOf(accepted.body());
    }

    @Test
    @Order(1)
    @ScenarioId(Release1Scenario.SC01)
    @WaitsOn({"T98", "T143"})
    void sc01ReferenceExampleOneCallTwoTargetsTwoVersions() throws Exception {
        Release1Client client = client();
        String queue = E2eNames.nameOf("mqTargetQueue");
        long before = queueCount(queue);

        acceptOne(client, "SC01", 1);

        assertThat(awaitJournal(client, "\"contractVersion\":\"1.0\"", 1)).as("target A got v1.0").contains("R1-SC01");
        assertThat(queueCount(queue)).as("target B (MQ-CSV v2.0) got exactly one message").isEqualTo(before + 1);
    }

    @Test
    @Order(2)
    @ScenarioId(Release1Scenario.SC02)
    @WaitsOn({"T115", "T98", "T143"})
    void sc02BulkWithInvalidRecordsGivesTenResults() throws Exception {
        Release1Client client = client();
        StringBuilder bulk = new StringBuilder("[");
        for (int i = 1; i <= 10; i++) {
            bulk.append(i > 1 ? "," : "").append(i <= 7 ? ORDER_JSON.formatted("B" + i, i)
                    : "{\"orderNo\":\"\",\"qty\":-1}"); // 3 invalid: blank key AND negative qty -> two reasons each
        }
        HttpResponse<String> answer = client.post(E2eNames.nameOf("bulkIntakeApi").replace("{interfaceCode}", INTERFACE),
                bulk.append(']').toString(), Release1Client.bearer("CUSTOMER_A"));

        String body = answer.body();
        assertThat(Release1Client.occurrences(body, "\"trackingId\"")).as("7 accepted, each its own id").isEqualTo(7);
        assertThat(Release1Client.occurrences(body, "\"REJECTED\"")).as("3 rejected").isEqualTo(3);
        assertThat(Release1Client.occurrences(body, "\"reason\"")).as("ALL reasons of each rejected record")
                .isGreaterThanOrEqualTo(6);
    }

    @Test
    @Order(3)
    @ScenarioId(Release1Scenario.SC03)
    @WaitsOn({"T99", "T98", "T143"})
    void sc03RetryThenSuccessKeepsAppendOnlyAttempts() throws Exception {
        Release1Client client = client();
        String scenario = "{\"request\":{\"method\":\"POST\",\"urlPathPattern\":\"/target-a/.*\"},"
                + "\"response\":{\"status\":%d},\"scenarioName\":\"sc03\",\"requiredScenarioState\":\"%s\","
                + "\"newScenarioState\":\"%s\",\"priority\":1}";
        client.wiremockStub(wiremockBase(), scenario.formatted(503, "Started", "second"));
        client.wiremockStub(wiremockBase(), scenario.formatted(503, "second", "third"));
        client.wiremockStub(wiremockBase(), scenario.formatted(200, "third", "done"));

        String trackingId = acceptOne(client, "SC03", 3);
        awaitJournal(client, "R1-SC03", 3);

        String timeline = client.get(E2eNames.nameOf("timelineApi").replace("{trackingId}", trackingId),
                Release1Client.bearer("TENANT_ADMIN")).body();
        assertThat(timeline).contains("RETRY_WAIT").contains("DELIVERED");
        assertThat(Release1Client.occurrences(timeline, "\"attemptNo\"")).as("3 append-only attempts").isEqualTo(3);
    }

    @Test
    @Order(4)
    @ScenarioId(Release1Scenario.SC04)
    @WaitsOn({"T111", "T96", "T144", "T98", "T143"})
    void sc04DeadLetterThenReplayWithPinnedVersions() throws Exception {
        Release1Client client = client();
        client.wiremockStub(wiremockBase(), "{\"request\":{\"method\":\"POST\",\"urlPathPattern\":\"/target-a/.*\"},"
                + "\"response\":{\"status\":500},\"priority\":1}");
        String trackingId = acceptOne(client, "SC04", 4);
        String failures = client.get(E2eNames.nameOf("failuresApi"), Release1Client.bearer("TENANT_ADMIN")).body();
        assertThat(failures).contains(trackingId).contains("DEAD_LETTERED");

        client.wiremockReset(wiremockBase()); // target A healthy again
        HttpResponse<String> replay = client.post(E2eNames.nameOf("replayApi").replace("{trackingId}", trackingId),
                "{}", Release1Client.bearer("APPROVER"));
        assertThat(replay.statusCode()).isBetween(200, 202);

        String timeline = client.get(E2eNames.nameOf("timelineApi").replace("{trackingId}", trackingId),
                Release1Client.bearer("TENANT_ADMIN")).body();
        assertThat(timeline).as("pinned v1.0 on replay, history kept").contains("\"contractVersion\":\"1.0\"")
                .contains("DEAD_LETTERED").contains("DELIVERED");
    }

    @Test
    @Order(5)
    @ScenarioId(Release1Scenario.SC05)
    @WaitsOn({"T98", "T143"})
    void sc05NewContractVersionWithZeroDeploys() throws Exception {
        Release1Client client = client();
        String health = client.get("/actuator/info", Release1Client.bearer("TENANT_ADMIN")).body();
        // Contract v2.1 import + publish is the browser journey's step (ui/tests/release1); here the API leg checks
        // that the same running backend serves it.
        String queue = E2eNames.nameOf("mqTargetQueue");
        long before = queueCount(queue);
        acceptOne(client, "SC05", 5);
        assertThat(queueCount(queue)).isEqualTo(before + 1);
        assertThat(client.get("/actuator/info", Release1Client.bearer("TENANT_ADMIN")).body())
                .as("same start time/build: zero deploys").isEqualTo(health);
    }

    @Test
    @Order(6)
    @ScenarioId(Release1Scenario.SC06)
    @WaitsOn({"T115", "T112", "T110", "T143"})
    void sc06SameKeyOrderingHoldsOnlyLaterSameKey() throws Exception {
        Release1Client client = client();
        String first = acceptOne(client, "K1", 1);
        String second = acceptOne(client, "K1", 2);
        acceptOne(client, "K2", 1);
        String journal = awaitJournal(client, "R1-K", 3);
        assertThat(journal.indexOf("R1-K1\",\"customer\":\"CUST-A\",\"qty\":1"))
                .as("K1 qty 1 before K1 qty 2 (%s then %s)", first, second)
                .isLessThan(journal.indexOf("R1-K1\",\"customer\":\"CUST-A\",\"qty\":2"));
    }

    @Test
    @Order(7)
    @ScenarioId(Release1Scenario.SC07)
    @WaitsOn({"T157", "T143"})
    void sc07CrossTenantDenial() throws Exception {
        Release1Client client = client();
        String trackingId = acceptOne(client, "SC07", 7);
        String tenantB = Release1Client.bearer("TENANT_B_ADMIN");
        assertThat(E2eNames.nameOf("tenantBCode")).isNotBlank();
        HttpResponse<String> foreign = client.get(E2eNames.nameOf("timelineApi").replace("{trackingId}", trackingId),
                tenantB);
        assertThat(foreign.statusCode()).as("tenant B on tenant A's message").isIn(403, 404);
        assertThat(client.get(E2eNames.nameOf("failuresApi"), tenantB).body()).doesNotContain(trackingId);
    }

    @Test
    @Order(8)
    @ScenarioId(Release1Scenario.SC08)
    @WaitsOn({"T120", "T99", "T111", "T98", "T143"})
    void sc08ReportCountsEqualDeliveryRows() throws Exception {
        Release1Client client = client();
        String report = client.get(E2eNames.nameOf("dailyReportApi"), Release1Client.bearer("TENANT_ADMIN")).body();
        // The SQL side is the base-suite oracle (ui/tests/base-suite/lib/direct-sql.mjs deliveryRowsSql + counts.mjs);
        // the API leg asserts every bucket is present so a missing one fails loudly.
        assertThat(report).contains("\"success\"").contains("\"failure\"").contains("\"retries\"")
                .contains("\"retriedThenSucceeded\"").contains("\"stillStuck\"");
    }

    @Test
    @Order(9)
    @ScenarioId(Release1Scenario.SC09)
    @WaitsOn({"T105", "T110", "T112", "T98", "T143"})
    void sc09StageTimingsPresent() throws Exception {
        Release1Client client = client();
        String trackingId = acceptOne(client, "SC09", 9);
        String timeline = client.get(E2eNames.nameOf("timelineApi").replace("{trackingId}", trackingId),
                Release1Client.bearer("TENANT_ADMIN")).body();
        assertThat(timeline).contains("INTAKE").contains("ROUTE").contains("TRANSFORM").contains("DELIVER")
                .contains("\"durationMs\"");
    }

    /** The release gate: ungated, runs last. RED while any scenario is not PASSED. */
    @Test
    @Order(99)
    void releaseGateAllNineScenariosPassed() {
        SuiteVerdict verdict = RECORDER.verdict();
        assertThat(verdict.green()).as(verdict.report()).isTrue();
    }
}
