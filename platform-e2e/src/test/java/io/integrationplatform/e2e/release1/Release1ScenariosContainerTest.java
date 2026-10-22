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

        private static final String ORDER_JSON = "{\"orderNo\":\"R1-%s\",\"customer\":\"CUST-A\",\"qty\":%d}";
    private static final Duration SETTLE = Duration.ofSeconds(30);

    private static Network network;
    private static GenericContainer<?> wiremock;
    private static GenericContainer<?> artemis;

    private static synchronized void harness() {
        if (E2eNames.externalWiremock() != null) {
            return; // T174b: the runner's WireMock and Artemis (the backend calls THEM); nothing to start here
        }
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
        if (E2eNames.externalWiremock() != null) {
            return E2eNames.externalWiremock();
        }
        return "http://" + wiremock.getHost() + ":" + wiremock.getMappedPort(E2eContainers.WIREMOCK_PORT);
    }

    private static Release1Client client() {
        harness();
        return new Release1Client(E2eNames.baseUrl());
    }

    private static long queueCount(String queue) throws Exception {
        String stdout;
        String external = E2eNames.externalArtemisContainer();
        if (external != null) { // the runner's broker: its login is in this process's environment, never on a command line
            Process process = new ProcessBuilder("docker", "exec", external, "sh", "-c",
                    E2eContainers.ARTEMIS_CLI + " queue stat --queueName " + queue
                            + " --user \"$ARTEMIS_USER\" --password \"$ARTEMIS_PASSWORD\"")
                    .redirectErrorStream(true).start();
            stdout = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as("artemis queue stat; output: %s", stdout).isZero();
        } else {
            ExecResult stat = artemis.execInContainer(E2eContainers.ARTEMIS_CLI, "queue", "stat", "--queueName", queue,
                    "--user", E2eContainers.ARTEMIS_USER, "--password", E2eContainers.ARTEMIS_PASSWORD);
            assertThat(stat.getExitCode()).as("artemis queue stat; stderr: %s", stat.getStderr()).isZero();
            stdout = stat.getStdout();
        }
        int column = -1; // the MESSAGE COUNT column, found by its header (the column order differs between Artemis versions)
        for (String line : stdout.split("\\R")) {
            String[] cells = line.split("\\|");
            if (column < 0 && line.contains("MESSAGES")) { // header row 1: |NAME|ADDRESS|CONSUMER|MESSAGE|MESSAGES|... ("COUNT" is row 2)
                for (int i = 0; i < cells.length; i++) {
                    if (cells[i].trim().equals("MESSAGE")) {
                        column = i;
                    }
                }
            } else if (column > 0 && cells.length > column && cells[1].trim().equals(queue)) {
                return Long.parseLong(cells[column].trim());
            }
        }
        throw new AssertionError("queue " + queue + " not in artemis stat output: " + stdout);
    }

    /** Polls the WireMock journal (request bodies unescaped) until `needle` shows `expected` times; the failure carries the journal's tail. */
    private static String awaitJournal(Release1Client client, String needle, int expected) throws Exception {
        return awaitJournal(client, needle, expected, SETTLE);
    }

    private static String awaitJournal(Release1Client client, String needle, int expected, Duration limit) throws Exception {
        Instant until = Instant.now().plus(limit);
        String body = "";
        while (Instant.now().isBefore(until)) {
            body = Release1Client.journalText(client.wiremockJournal(wiremockBase()).body());
            if (Release1Client.occurrences(body, needle) >= expected) {
                return body;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(250).toNanos()); // poll interval, not a fixed wait
        }
        throw new AssertionError("WireMock journal did not show " + expected + "x " + needle + " within " + SETTLE
                + "; journal tail: " + body.substring(Math.max(0, body.length() - 1500)));
    }

    /** Polls the broker until the queue holds `before + added` messages (the MQ leg is asynchronous); returns the last count. */
    private static long awaitQueue(String queue, long before, int added) throws Exception {
        Instant until = Instant.now().plus(SETTLE);
        long count = queueCount(queue);
        while (count < before + added && Instant.now().isBefore(until)) {
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(500).toNanos()); // poll interval
            count = queueCount(queue);
        }
        return count;
    }

    /** Polls the SQL oracle until the deliveries of the message satisfy `ready`; returns the last rows either way. */
    private static java.util.List<java.util.List<String>> awaitDeliveries(String trackingId, Duration limit,
            java.util.function.Predicate<java.util.List<java.util.List<String>>> ready) throws Exception {
        Instant until = Instant.now().plus(limit);
        java.util.List<java.util.List<String>> rows = Release1Sql.deliveriesOf(trackingId);
        while (!ready.test(rows) && Instant.now().isBefore(until)) {
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(500).toNanos()); // poll interval
            rows = Release1Sql.deliveriesOf(trackingId);
        }
        return rows;
    }

    /** Polls a GET until its body holds every needle (the pipeline is asynchronous), else returns the last body. */
    private static String awaitBody(Release1Client client, String path, String bearer, Duration limit, String... needles)
            throws Exception {
        Instant until = Instant.now().plus(limit);
        String body = "";
        while (Instant.now().isBefore(until)) {
            body = client.get(path, bearer).body();
            boolean all = true;
            for (String needle : needles) {
                all &= body.contains(needle);
            }
            if (all) {
                return body;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(500).toNanos()); // poll interval
        }
        return body;
    }

    private static String acceptOne(Release1Client client, String key, int qty) throws Exception {
        HttpResponse<String> accepted = client.intake(E2eNames.interfaceCode(), "1.0", ORDER_JSON.formatted(key, qty),
                Release1Client.bearer("CUSTOMER_A"));
        assertThat(accepted.statusCode()).as("intake answer: %s", accepted.body()).isEqualTo(202);
        return Release1Client.trackingIdOf(accepted.body());
    }

    /** The delivery rows of a message as one diagnostic line per delivery (id | state | contract | attempts | outcomes | events). */
    private static String describe(java.util.List<java.util.List<String>> deliveries) {
        return deliveries.stream().map(row -> String.join(" | ", row)).collect(java.util.stream.Collectors.joining("\n  "));
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
        assertThat(awaitQueue(queue, before, 1)).as("target B (MQ-CSV v2.0) got exactly one message").isEqualTo(before + 1);
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
                    : "{\"orderNo\":\"\",\"qty\":-1}"); // 3 invalid: blank key AND negative qty
        }
        HttpResponse<String> answer = client.bulk(E2eNames.interfaceCode(), "1.0", bulk.append(']').toString(),
                Release1Client.bearer("CUSTOMER_A"));

        String body = answer.body();
        assertThat(answer.statusCode()).as("bulk answer: %s", body).isEqualTo(202);
        assertThat(Release1Client.occurrences(body, "\"index\"")).as("ten results, one per record: %s", body).isEqualTo(10);
        assertThat(Release1Client.occurrences(body, "\"trackingId\"")).as("7 accepted, each its own id: %s", body).isEqualTo(7);
        assertThat(Release1Client.occurrences(body, "\"REJECTED\"")).as("3 rejected: %s", body).isEqualTo(3);
        assertThat(Release1Client.occurrences(body, "\"code\"")).as("each rejected record names its refusal code: %s", body)
                .isEqualTo(3);
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
        awaitJournal(client, "R1-SC03", 3, Duration.ofSeconds(120)); // the retries ride the retry job tick, not a fixed delay

        // The timeline read carries only each delivery's LAST outcome; the append-only attempt rows are read from the
        // table (the base-suite oracle idiom): target A took 3 attempts - two TRANSIENT (503), then SUCCESS.
        java.util.List<java.util.List<String>> deliveries = awaitDeliveries(trackingId, Duration.ofSeconds(120), rows -> rows.stream()
                .anyMatch(row -> row.get(1).equals("DELIVERED") && row.get(3).equals("3")));
        assertThat(deliveries).as("deliveries of %s:%n  %s", trackingId, describe(deliveries)).anyMatch(row ->
                row.get(1).equals("DELIVERED") && row.get(3).equals("3") && row.get(4).equals("TRANSIENT,TRANSIENT,SUCCESS")
                        && row.get(5).contains("RETRY"));
        String timeline = awaitBody(client, E2eNames.nameOf("timelineApi").replace("{trackingId}", trackingId),
                Release1Client.bearer("TENANT_ADMIN"), SETTLE, "DELIVERED");
        assertThat(timeline).contains("DELIVERED");
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
        String failures = awaitBody(client, E2eNames.nameOf("failuresApi"), Release1Client.bearer("TENANT_ADMIN"),
                Duration.ofSeconds(120), trackingId, "\"failingStage\":\"DELIVER\"");
        // the failures read lists a failed delivery by failingStage / errorClass; it carries no state word (measured
        // 22-Oct on f0a5095f), so the dead-letter is proved on the failures row here and on the event trail below
        assertThat(failures).contains(trackingId).contains("\"failingStage\":\"DELIVER\"");

        java.util.regex.Matcher delivery = java.util.regex.Pattern
                .compile("\"trackingId\"\\s*:\\s*\"" + trackingId + "\"[^}]*?\"deliveryId\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(failures);
        assertThat(delivery.find()).as("the failures row of %s carries its deliveryId: %s", trackingId, failures).isTrue();
        String deliveryId = delivery.group(1);
        String sentBefore = Release1Client.journalText(client.wiremockJournal(wiremockBase()).body());
        assertThat(sentBefore).as("the failed send carried v1.0").contains("\"contractVersion\":\"1.0\"").contains("R1-SC04");
        java.util.List<String> before = Release1Sql.deliveriesOf(trackingId).stream()
                .filter(row -> row.get(0).equals(deliveryId)).findFirst().orElseThrow();

        client.wiremockReset(wiremockBase()); // target A healthy again
        client.wiremockStub(wiremockBase(), "{\"request\":{\"method\":\"POST\",\"urlPathPattern\":\"/target-a/.*\"},"
                + "\"response\":{\"status\":200},\"priority\":10}"); // the reset removed the runner's default 200
        HttpResponse<String> replay = client.post(
                E2eNames.nameOf("replayApi").replace("{deliveryId}", deliveryId), "{}",
                Release1Client.bearer("TENANT_ADMIN")); // REPLAY_DEAD_LETTER: tenant admin and support, never APPROVER
        assertThat(replay.statusCode()).isBetween(200, 202);

        java.util.List<java.util.List<String>> deliveries = awaitDeliveries(trackingId, SETTLE, rows -> rows.stream()
                .anyMatch(row -> row.get(0).equals(deliveryId) && row.get(1).equals("DELIVERED")));
        assertThat(deliveries).as("deliveries of %s after the replay (before it: %s):%n  %s", trackingId, before,
                describe(deliveries)).anyMatch(row -> row.get(0).equals(deliveryId) && row.get(1).equals("DELIVERED")
                        && row.get(2).equals(before.get(2)) // pinned: the same contract version id as before the replay
                        && row.get(5).contains("DEAD_LETTER") && row.get(5).contains("REPLAY") // history kept
                        && row.get(4).endsWith("SUCCESS") && Integer.parseInt(row.get(3)) > Integer.parseInt(before.get(3)));
        assertThat(awaitJournal(client, "\"contractVersion\":\"1.0\"", 1)).as("the replayed send carried v1.0")
                .contains("R1-SC04");
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
        assertThat(awaitQueue(queue, before, 1)).isEqualTo(before + 1);
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
        awaitJournal(client, "R1-K2", 1);
        // The journal lists newest first, so ordering is read from each request's own loggedDate (millis), not its position.
        long k1First = firstLoggedAt(journal, "R1-K1", "1");
        long k1Second = firstLoggedAt(journal, "R1-K1", "2");
        assertThat(k1First).as("K1 qty 1 before K1 qty 2 (%s then %s)", first, second).isLessThanOrEqualTo(k1Second);
    }

    /** The loggedDate of the earliest journal request whose unescaped body matches (WireMock puts loggedDate after the body, inside the same request). */
    private static long firstLoggedAt(String journal, String orderNo, String qty) {
        String bodyRegex = "\\{(?=[^{}]*" + orderNo + "\")(?=[^{}]*\"qty\"\\s*:\\s*" + qty + "[,}\\s])[^{}]*\\}";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)\"loggedDate\"\\s*:\\s*(\\d+)[^{]*?" + bodyRegex).matcher(journal); // loggedDate precedes the body in a request entry
        long earliest = Long.MAX_VALUE;
        while (m.find()) {
            earliest = Math.min(earliest, Long.parseLong(m.group(1)));
        }
        assertThat(earliest).as("journal request for %s qty %s; journal tail: %s", orderNo, qty, journal.substring(Math.max(0, journal.length() - 1200))).isNotEqualTo(Long.MAX_VALUE);
        return earliest;
    }

    @Test
    @Order(7)
    @ScenarioId(Release1Scenario.SC07)
    @WaitsOn({"T157", "T143"})
    void sc07CrossTenantDenial() throws Exception {
        Release1Client client = client();
        String trackingId = acceptOne(client, "SC07", 7);
        String tenantB = Release1Client.bearer("TENANT_B_ADMIN");
        assertThat(E2eNames.tenantBCode()).isNotBlank();
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
        String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        HttpResponse<String> answer = client.get(E2eNames.nameOf("dailyReportApi").replace("{day}", day),
                Release1Client.bearer("TENANT_ADMIN"));
        String report = answer.body();
        assertThat(answer.statusCode()).as("report answer: %s", report).isEqualTo(200);
        long reported = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"successes\"\\s*:\\s*(\\d+)").matcher(report);
        while (m.find()) {
            reported += Long.parseLong(m.group(1));
        }
        // The oracle is the table itself: deliveries of the bound tenant (tenant A) that reached DELIVERED today (UTC).
        long delivered = Long.parseLong(Release1Sql.rows("select count(*) from public.delivery d where d.state = 'DELIVERED'"
                + " and d.created_at >= date_trunc('day', now() at time zone 'utc') at time zone 'utc'"
                + " and d.tenant_id = (select t.id from public.tenant t where t.code = '" + E2eNames.tenantACode() + "')")
                .getFirst().getFirst());
        assertThat(report).as("report for %s: %s", day, report).contains("\"successes\"").contains("\"failuresTransient\"")
                .contains("\"retriesMade\"").contains("\"retriedThenSucceeded\"").contains("\"stillStuck\"");
        assertThat(reported).as("report successes (%s) equal the DELIVERED delivery rows (%s) of %s", reported, delivered, day)
                .isEqualTo(delivered);
    }

    @Test
    @Order(9)
    @ScenarioId(Release1Scenario.SC09)
    @WaitsOn({"T105", "T110", "T112", "T98", "T143"})
    void sc09StageTimingsPresent() throws Exception {
        Release1Client client = client();
        String trackingId = acceptOne(client, "SC09", 9);
        String timeline = awaitBody(client, E2eNames.nameOf("timelineApi").replace("{trackingId}", trackingId),
                Release1Client.bearer("TENANT_ADMIN"), SETTLE, "INTAKE", "ROUTE", "TRANSFORM", "DELIVER\"");
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
