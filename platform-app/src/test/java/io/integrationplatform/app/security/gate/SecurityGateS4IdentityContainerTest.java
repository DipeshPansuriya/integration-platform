package io.integrationplatform.app.security.gate;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.e11.TwoTargetOrdersSeed;
import io.integrationplatform.identity.ConsoleBrowser;
import io.integrationplatform.identity.KeycloakRealmContainer;
import io.integrationplatform.identity.KeycloakRealmContainer.ClientCredentials;
import io.integrationplatform.identity.KeycloakRealmContainer.RealmUser;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T141 / T-147 Release 1 security gate, suite S4 (Identity: the operator agent), from release-1-security-test-plan.md.
 * T171 bodies for S4-A1..A7 over HTTP: the application on a port with a real Keycloak, Postgres with the restricted
 * login, two onboarded tenants (A and B), an operator-agent credential registered in
 * {@code scheduler.operator_credential}, and (for the human legs of S4-A5) console sessions. S4-A8 is the realm-file
 * check. Container tag: runs only under {@code -Pcontainer}.
 *
 * <p>Known limit recorded by S4-A1: the plan's "exactly 6 operations" include alert read and alert acknowledge, but
 * on this tip the job API has no alert route (only {@code RolePolicy} operations READ_JOB_ALERTS / ACKNOWLEDGE_JOB_ALERT
 * exist); the case asserts that fact instead of passing it silently. The plan's "audit-stream entry" leg of S4-A2/A5
 * is not asserted here (no read model for the platform audit stream in the test wiring).
 */
@Tag("container")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class SecurityGateS4IdentityContainerTest {

    private static final int APP_PORT = freePort();
    private static final URI BACKEND = URI.create("http://localhost:" + APP_PORT);
    private static final String JOBS = "/api/v1/admin/jobs";
    private static final String JOB = "DELIVERY_RETRY";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Container
    static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(PlatformTestWiring.OIDC_AUDIENCE,
            BACKEND + "/login/oauth2/code/keycloak", BACKEND + "/");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final AtomicBoolean SEEDED = new AtomicBoolean();
    private static PlatformTestWiring.RestrictedLogin login;
    private static TwoTargetOrdersSeed.Tenant tenantA;
    private static TwoTargetOrdersSeed.Tenant tenantB;
    private static ClientCredentials sourceA;
    private static ClientCredentials agent;
    private static ClientCredentials strangerAgent;
    private static RealmUser adminA;
    private static RealmUser readerA;
    private static List<String> markers;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
        KEYCLOAK.start();
        registry.add("server.port", () -> APP_PORT);
        registry.add("OIDC_ISSUER_URI", KEYCLOAK::issuerUri);
        registry.add("CONSOLE_OIDC_CLIENT_ID", () -> KeycloakRealmContainer.CONSOLE_CLIENT);
        registry.add("CONSOLE_OIDC_CLIENT_SECRET", KEYCLOAK::consoleClientSecret);
        registry.add("CONSOLE_OIDC_REDIRECT_URI", () -> BACKEND + "/login/oauth2/code/keycloak");
        registry.add("CONSOLE_POST_LOGOUT_REDIRECT_URI", () -> BACKEND + "/");
        registry.add("CONSOLE_URL", () -> "/");
        registry.add("platform.config.destinations.allowed-hosts", () -> "*.e11.test");
        registry.add("ROUTING_CORE_ALLOWED_SCHEMES", () -> "http,https");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void seedOnce() throws Exception {
        if (!SEEDED.compareAndSet(false, true)) {
            return;
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        sourceA = KEYCLOAK.createConfidentialClient("t171s4-a-" + suffix, true);
        ClientCredentials sourceB = KEYCLOAK.createConfidentialClient("t171s4-b-" + suffix, true);
        agent = KEYCLOAK.createConfidentialClient("t171s4-agent-" + suffix, true);
        strangerAgent = KEYCLOAK.createConfidentialClient("t171s4-stranger-" + suffix, true);
        adminA = KEYCLOAK.createUser("s4-admin-" + suffix);
        readerA = KEYCLOAK.createUser("s4-reader-" + suffix);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            tenantA = TwoTargetOrdersSeed.onboard(owner, "alpha", "T171S4-CUST-A", sourceA.clientId(),
                    KEYCLOAK.issuerUri());
            tenantB = TwoTargetOrdersSeed.onboard(owner, "bravo", "T171S4-CUST-B", sourceB.clientId(),
                    KEYCLOAK.issuerUri());
            try (PreparedStatement st = owner.prepareStatement(
                    "INSERT INTO scheduler.operator_credential (issuer, client_id, status) VALUES (?, ?, 'ACTIVE')")) {
                st.setString(1, KEYCLOAK.issuerUri());
                st.setString(2, agent.clientId());
                st.executeUpdate();
            }
            for (var human : List.of(Map.entry(adminA, "TENANT_ADMIN"), Map.entry(readerA, "READ_ONLY"))) {
                UUID user = UUID.randomUUID();
                execute(owner, "INSERT INTO app_user (id, tenant_id, issuer, subject, status) VALUES"
                        + " (?::uuid, ?::uuid, ?, ?, 'ACTIVE')", user, tenantA.id(), KEYCLOAK.issuerUri(),
                        human.getKey().subject());
                execute(owner, "INSERT INTO role_grant (tenant_id, user_id, role) VALUES (?::uuid, ?::uuid, ?)",
                        tenantA.id(), user, human.getValue());
            }
            String codeA = scalar(owner, "select code from tenant where id = '" + tenantA.id() + "'");
            String codeB = scalar(owner, "select code from tenant where id = '" + tenantB.id() + "'");
            markers = List.of(tenantA.id().toString(), tenantB.id().toString(), codeA, codeB, "T171S4-CUST-A",
                    "T171S4-CUST-B");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void execute(Connection c, String sql, Object... args) throws Exception {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setString(i + 1, String.valueOf(args[i]));
            }
            st.executeUpdate();
        }
    }

    private static String scalar(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Row counts of every base table of the tenant-data schema except the ones a refused call legitimately writes. */
    private static Map<String, Long> fingerprint() throws Exception {
        Map<String, Long> counts = new TreeMap<>();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement st = owner.createStatement();
                ResultSet rs = st.executeQuery("select table_name from information_schema.tables"
                        + " where table_schema = 'public' and table_type = 'BASE TABLE'")) {
            List<String> tables = new ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            for (String table : tables) {
                if (table.contains("audit") || table.contains("denied") || table.contains("session")
                        || table.startsWith("flyway")) {
                    continue;
                }
                counts.put(table, Long.parseLong(scalar(owner, "select count(*) from public.\"" + table + "\"")));
            }
        }
        return counts;
    }

    private static Map<String, String> jobState() throws Exception {
        Map<String, String> state = new TreeMap<>();
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement st = owner.createStatement();
                ResultSet rs = st.executeQuery("select job_type, enabled || '/' || paused || '/' || coalesce(paused_by,'-')"
                        + " from scheduler.job_definition")) {
            while (rs.next()) {
                state.put(rs.getString(1), rs.getString(2));
            }
            state.put("#job_run", scalar(owner, "select count(*) from scheduler.job_run"));
            state.put("#job_alert", scalar(owner, "select count(*) from scheduler.job_alert"));
        }
        return state;
    }

    private HttpResponse<String> call(String method, String pathAndQuery, String bearer, String body,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BACKEND + pathAndQuery))
                .timeout(Duration.ofSeconds(30));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        headers.forEach(request::header);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String agentToken() {
        return KEYCLOAK.clientCredentialsToken(agent.clientId(), agent.secret());
    }

    private HttpResponse<String> intake(String bearer) throws Exception {
        return call("POST", "/api/v1/interfaces/" + TwoTargetOrdersSeed.INTERFACE_CODE + "/messages", bearer,
                "{\"customerId\":\"T171S4-CUST-A\",\"orderId\":\"S4-" + UUID.randomUUID() + "\",\"amount\":1}",
                Map.of("Content-Type", "application/json", "Idempotency-Key", UUID.randomUUID().toString(),
                        "X-Source-Contract-Version", TwoTargetOrdersSeed.SOURCE_CONTRACT_LABEL));
    }

    private void assertOpaque(String what, HttpResponse<String> response) {
        assertThat(response.body().replace(response.uri().getPath(), "<request-path>")).as("%s: no tenant data in the body (the echoed request path is the caller own input)", what).doesNotContain(markers)
                .doesNotContain("Exception").doesNotContain("\tat ").doesNotContain("SQL");
    }

    /** One (method, concrete path) per request mapping under /api, with ids made up. */
    private List<String[]> apiSurface() {
        List<String[]> surface = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            for (var pattern : info.getPathPatternsCondition().getPatterns()) {
                String raw = pattern.getPatternString();
                if (!raw.startsWith("/api/")) {
                    continue;
                }
                String path = raw.replaceAll("\\{[^/}]+}", UUID.randomUUID().toString());
                var methods = info.getMethodsCondition().getMethods();
                if (methods.isEmpty()) {
                    surface.add(new String[] {"GET", path, raw});
                }
                methods.forEach(m -> surface.add(new String[] {m.name(), path, raw}));
            }
        }
        return surface;
    }

    private static boolean isJobFamily(String raw) {
        return raw.startsWith(JOBS);
    }

    private static String bodyFor(String method) {
        return method.equals("GET") || method.equals("DELETE") ? null : "{}";
    }

    // ------------------------------------------------------------------ S4-A1

    /**
     * S4-A1: The exactly-6 operations succeed from the agent credential.
     * Expected: Each of the 6 succeeds; responses contain job/run/alert rows only - **no payload, no configuration, no tenant id** (ADR-012 §5: job_run holds no payload, no tenant id, no per-tenant count)
     *
     * <p>Run for the routes that exist on this tip: list, summaries, inspect, run now, pause, resume. The alert read and
     * acknowledge legs have no route (asserted below), so S4-A1 is executed for 6 of the plan's 8 route shapes only.
     */
    @Test
    void s4_a1_the_agent_operations_succeed_and_carry_no_tenant_data() throws Exception {
        String token = agentToken();
        Map<String, String> noTenant = Map.of("Content-Type", "application/json");
        HttpResponse<String> list = call("GET", JOBS, token, null, noTenant);
        assertThat(list.statusCode()).as("list: %s", list.body()).isEqualTo(200);
        HttpResponse<String> summaries = call("GET", JOBS + "/summaries", token, null, noTenant);
        assertThat(summaries.statusCode()).as("summaries: %s", summaries.body()).isEqualTo(200);
        HttpResponse<String> inspect = call("GET", JOBS + "/" + JOB, token, null, noTenant);
        assertThat(inspect.statusCode()).as("inspect: %s", inspect.body()).isEqualTo(200);
        assertThat(call("POST", JOBS + "/" + JOB + "/pause", token, null, noTenant).statusCode()).isEqualTo(204);
        assertThat(jobState().get(JOB)).as("paused by the agent").contains("/true/");
        assertThat(call("POST", JOBS + "/" + JOB + "/resume", token, null, noTenant).statusCode()).isEqualTo(204);
        assertThat(jobState().get(JOB)).as("resumed").contains("/false/");
        HttpResponse<String> run = call("POST", JOBS + "/" + JOB + "/run", token, null, noTenant);
        assertThat(run.statusCode()).as("run now: %s", run.body()).isEqualTo(202);
        for (HttpResponse<String> response : List.of(list, summaries, inspect, run)) {
            assertThat(response.body()).as("job rows carry no tenant id / customer / payload")
                    .doesNotContain(markers).doesNotContain("\"payload\"");
        }
        // the plan's alert read / acknowledge operations: no route on this tip (gap, reported by the gate)
        assertThat(apiSurface().stream().map(s -> s[2]).filter(p -> p.toLowerCase().contains("alert")).toList())
                .as("alert routes on the API surface (plan expects read + acknowledge)").isEmpty();
    }

    // ------------------------------------------------------------------ S4-A2

    /**
     * S4-A2: Scope-limiting sweep (the "every other operation" rule).
     * Expected: Every call 403 + audit-stream entry; nothing resolves, nothing half-written; the sweep output is a per-family table
     */
    @Test
    void s4_a2_scope_limiting_sweep_every_other_family_refuses_the_agent() throws Exception {
        String token = agentToken();
        Map<String, Long> before = fingerprint();
        Map<String, String> jobsBefore = jobState();
        Map<String, Integer> perFamily = new TreeMap<>();
        for (String[] op : apiSurface()) {
            if (isJobFamily(op[2])) {
                continue;
            }
            HttpResponse<String> response = call(op[0], op[1], token, bodyFor(op[0]),
                    Map.of("Content-Type", "application/json"));
            assertThat(response.statusCode()).as("agent token on %s %s -> %s", op[0], op[2], response.body())
                    .isEqualTo(403);
            assertOpaque(op[0] + " " + op[2], response);
            String[] parts = op[2].split("/");
            perFamily.merge(parts.length > 4 ? parts[3] + "/" + parts[4] : op[2], 1, Integer::sum);
        }
        assertThat(perFamily).as("per-family sweep table: %s", perFamily).isNotEmpty();
        assertThat(perFamily.values().stream().mapToInt(Integer::intValue).sum()).isGreaterThan(20);
        assertThat(fingerprint()).as("nothing half-written in the tenant schema").isEqualTo(before);
        assertThat(jobState()).as("no job state change from the refused calls").isEqualTo(jobsBefore);
        System.out.println("S4-A2 per-family refusals: " + perFamily);
    }

    // ------------------------------------------------------------------ S4-A3

    /**
     * S4-A3: Replay beyond the 5-minute expiry (ADR-007 D4/D3).
     * Expected: All 401 (final); no job state change, no double acknowledge; a second ack of the same alert id changes nothing (ack idempotent per job_alert uniqueness)
     *
     * <p>The realm's token lifespan is set to 5 s for this case (the S1-A5 technique) and the token is replayed after
     * lifetime plus the validator's skew; the alert-acknowledge leg has no route on this tip (see S4-A1).
     */
    @Test
    void s4_a3_replay_of_an_expired_agent_token_is_refused() throws Exception {
        Object previous = KEYCLOAK.realmRepresentation().path("accessTokenLifespan").asInt(300);
        KEYCLOAK.admin("PUT", "", Map.of("accessTokenLifespan", 5));
        try {
            String shortLived = agentToken();
            assertThat(call("GET", JOBS, shortLived, null, Map.of()).statusCode()).as("fresh token (control)")
                    .isEqualTo(200);
            Instant until = Instant.now().plus(Duration.ofSeconds(150));
            int status = 200;
            while (status != 401 && Instant.now().isBefore(until)) {
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofSeconds(5).toNanos());
                status = call("GET", JOBS, shortLived, null, Map.of()).statusCode();
            }
            assertThat(status).as("the expired token is refused within lifetime + skew").isEqualTo(401);
            Map<String, String> jobsBefore = jobState();
            for (String op : List.of("/run", "/pause", "/resume")) {
                HttpResponse<String> replay = call("POST", JOBS + "/" + JOB + op, shortLived, null, Map.of());
                assertThat(replay.statusCode()).as("replay of %s", op).isEqualTo(401);
            }
            assertThat(jobState()).as("no job state change from the replays").isEqualTo(jobsBefore);
        } finally {
            KEYCLOAK.admin("PUT", "", Map.of("accessTokenLifespan", previous));
        }
    }

    // ------------------------------------------------------------------ S4-A4

    /**
     * S4-A4: Confused deputy - tenant scoping attempts on the agent (ADR-012 §5 "never reads tenant data by construction").
     * Expected: No tenant may be bound: the bootstrap resolves nothing, every attempt 403; **nothing resolves to A - not even read-only**; where the endpoint family is source-only, the agent token is refused there too
     */
    @Test
    void s4_a4_confused_deputy_no_tenant_can_be_bound_to_the_agent() throws Exception {
        String token = agentToken();
        Map<String, Long> before = fingerprint();
        String codeA = markers.get(2);
        Map<String, String> tenantHeaders = Map.of("Content-Type", "application/json", "X-Tenant", codeA,
                "X-Tenant-Id", tenantA.id().toString(), "X-Customer-Id", "T171S4-CUST-A");
        String bodyNaming = "{\"customerId\":\"T171S4-CUST-A\",\"tenantId\":\"" + tenantA.id() + "\"}";
        // header / body / path on tenant-owned families: 403, never a read of A
        List<HttpResponse<String>> attempts = new ArrayList<>();
        attempts.add(call("GET", "/api/v1/admin/systems", token, null, tenantHeaders));
        attempts.add(call("GET", "/api/v1/tenants/" + tenantA.id() + "/jobs", token, null, tenantHeaders));
        attempts.add(call("GET", "/api/v1/tenants/" + codeA + "/jobs", token, null, tenantHeaders));
        attempts.add(call("POST", "/api/v1/admin/systems", token, bodyNaming, tenantHeaders));
        attempts.add(call("GET", "/api/v1/admin/flows?tenantId=" + tenantA.id(), token, null, tenantHeaders));
        // a source-only family (the intake) refuses the agent too, and writes nothing
        attempts.add(intake(token));
        for (HttpResponse<String> attempt : attempts) {
            assertThat(attempt.statusCode()).as("confused deputy attempt -> %s", attempt.body()).isIn(403, 404);
            assertOpaque("confused deputy", attempt);
        }
        assertThat(attempts.get(0).statusCode()).isEqualTo(403);
        assertThat(attempts.get(3).statusCode()).isEqualTo(403);
        assertThat(attempts.get(5).statusCode()).isEqualTo(403);
        // the agent's own family with tenant forgery: nothing about A is resolved or returned
        HttpResponse<String> jobs = call("GET", JOBS, token, null, tenantHeaders);
        assertThat(jobs.body()).as("job list with a tenant header carries nothing of A").doesNotContain(markers);
        assertThat(fingerprint()).as("nothing written for A or B").isEqualTo(before);
    }

    // ------------------------------------------------------------------ S4-A5

    /**
     * S4-A5: Cross-kind token confusion (ADR-007 D5 register).
     * Expected: All (i)-(v) 403 + audit; the register is closed: ROLE, ANY_HUMAN, SOURCE_SYSTEM, OPERATOR_AGENT only, and OPERATOR_AGENT bindings are per-client
     */
    @Test
    void s4_a5_cross_kind_token_confusion() throws Exception {
        List<String[]> jobOps = new ArrayList<>(List.of(new String[] {"GET", JOBS}, new String[] {"GET", JOBS + "/summaries"},
                new String[] {"GET", JOBS + "/" + JOB}, new String[] {"POST", JOBS + "/" + JOB + "/run"},
                new String[] {"POST", JOBS + "/" + JOB + "/pause"}, new String[] {"POST", JOBS + "/" + JOB + "/resume"}));
        Map<String, String> jobsBefore = jobState();
        // (i) the agent token on the source's intake: refused, nothing accepted
        assertThat(intake(agentToken()).statusCode()).as("(i) agent token on the intake").isEqualTo(403);
        // (ii) a real source-system token on each of the agent's operations
        String source = KEYCLOAK.clientCredentialsToken(sourceA.clientId(), sourceA.secret());
        for (String[] op : jobOps) {
            HttpResponse<String> r = call(op[0], op[1], source, null, Map.of("Content-Type", "application/json"));
            assertThat(r.statusCode()).as("(ii) source token on %s %s", op[0], op[1]).isEqualTo(403);
            assertOpaque("(ii) " + op[1], r);
        }
        // (v) a second client that is not a registered operator credential: refused (not the agent's binding)
        String stranger = KEYCLOAK.clientCredentialsToken(strangerAgent.clientId(), strangerAgent.secret());
        for (String[] op : jobOps) {
            HttpResponse<String> r = call(op[0], op[1], stranger, null, Map.of("Content-Type", "application/json"));
            assertThat(r.statusCode()).as("(v) unbound second client on %s %s", op[0], op[1]).isIn(401, 403);
        }
        // (iii) READ_ONLY and (iv) TENANT_ADMIN human sessions on each of the six
        for (var human : Map.of("READ_ONLY (iii)", readerA, "TENANT_ADMIN (iv)", adminA).entrySet()) {
            ConsoleBrowser browser = KEYCLOAK.browser(BACKEND);
            assertThat(browser.login(human.getValue()).status()).as("console login %s", human.getKey()).isEqualTo(302);
            browser.get("/api/v1/me");
            for (String[] op : jobOps) {
                ConsoleBrowser.Exchange r = browser.api(op[0], op[1], op[0].equals("POST") ? "{}" : null, true);
                assertThat(r.status()).as("%s session on %s %s -> %s", human.getKey(), op[0], op[1], r.body())
                        .isEqualTo(403);
            }
        }
        assertThat(jobState()).as("no job state change from any refused cross-kind call").isEqualTo(jobsBefore);
        // the register is closed: the caller kinds are exactly these four
        List<String> kinds = new ArrayList<>();
        for (var kind : io.integrationplatform.tenancy.RolePolicy.Caller.values()) {
            kinds.add(kind.name());
        }
        assertThat(kinds).as("closed caller-kind register").containsExactlyInAnyOrder("ROLE", "ANY_HUMAN",
                "SOURCE_SYSTEM", "OPERATOR_AGENT");
    }

    // ------------------------------------------------------------------ S4-A6

    /**
     * S4-A6: Direct API access without the console (the agent has no console session).
     * Expected: Agent works direct on the API (expected); **gains no console route, no BFF session endpoint, no state change beyond its 6 ops**; console routes 401/403
     */
    @Test
    void s4_a6_the_agent_gains_no_console_route_or_session() throws Exception {
        String token = agentToken();
        assertThat(call("GET", JOBS, token, null, Map.of()).statusCode()).as("works direct (control)").isEqualTo(200);
        Map<String, Long> before = fingerprint();
        List<String> consoleRoutes = List.of("/api/v1/me", "/api/v1/session", "/api/v1/csrf", "/console",
                "/console/index.html", "/login/oauth2/code/keycloak?code=x&state=y", "/logout", "/actuator/env",
                "/actuator/heapdump", "/actuator/beans", "/actuator/mappings", "/v3/api-docs/../actuator/env");
        for (String route : consoleRoutes) {
            for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
                HttpResponse<String> r = call(method, route, token, method.equals("GET") ? null : "{}",
                        Map.of("Content-Type", "application/json"));
                assertThat(r.statusCode()).as("%s %s with the agent token -> %s", method, route, r.body())
                        .matches(s -> s < 200 || s >= 300);
                assertThat(r.headers().allValues("Set-Cookie")).as("%s %s sets no session cookie", method, route)
                        .noneMatch(c -> c.startsWith("SESSION=") || c.startsWith("JSESSIONID=")
                                || c.toLowerCase().contains("console"));
            }
        }
        // every state-changing route outside the job family refuses (the S4-A2 sweep), and nothing beyond the jobs moved
        assertThat(fingerprint()).as("no state change outside the agent's own operations").isEqualTo(before);
    }

    // ------------------------------------------------------------------ S4-A7

    /**
     * S4-A7: Tenant-table read errors leak nothing (ADR-012 §5; ADR-007 D5 error path).
     * Expected: No tenant data, tenant id, tenant name or payload fragment in any error body or log; errors are opaque (403/404, named error classes); nothing writes a tenant-scoped row for the agent (fails RLS by design; audit goes to the platform stream)
     */
    @Test
    void s4_a7_tenant_table_read_errors_leak_nothing(CapturedOutput output) throws Exception {
        String token = agentToken();
        int logFrom = output.getAll().length();
        Map<String, Long> before = fingerprint();
        List<HttpResponse<String>> all = new ArrayList<>();
        Map<String, String> hostile = Map.of("Content-Type", "application/json", "X-Tenant", markers.get(2),
                "X-Tenant-Id", tenantA.id().toString());
        for (String[] op : apiSurface()) {
            all.add(call(op[0], op[1], token, bodyFor(op[0]), hostile));
        }
        all.add(call("GET", JOBS + "/NO_SUCH_JOB", token, null, hostile)); // the agent's own error path
        all.add(call("GET", JOBS + "/summaries?since=garbage", token, null, hostile));
        for (HttpResponse<String> response : all) {
            assertThat(response.statusCode()).as("agent response %s", response.uri()).isNotEqualTo(500);
            assertOpaque(response.uri().getPath(), response);
        }
        // the only response statuses are the opaque classes
        assertThat(all.stream().map(HttpResponse::statusCode).distinct().toList())
                .as("statuses seen").allMatch(s -> s == 200 || s == 202 || s == 204 || s == 400 || s == 403
                        || s == 404 || s == 405 || s == 415);
        String log = output.getAll().substring(logFrom);
        for (String marker : markers) {
            assertThat(log).as("backend log lines written during the agent calls carry no %s", marker)
                    .doesNotContain(marker);
        }
        assertThat(log).doesNotContain("payload");
        assertThat(fingerprint()).as("nothing writes a tenant-scoped row for the agent").isEqualTo(before);
    }

    // ------------------------------------------------------------------ S4-A8

    /**
     * S4-A8: Keycloak-side configuration of the agent client.
     * Expected: Agent client holds no role and no mapper that emits a tenant claim (one realm, tenants not in Keycloak - ADR-007 D1); a forged tenant claim on an agent token is only **cross-checked** - mismatch denies and audits (ADR-007 D2), never binds
     */
    @Test
    void s4_a8_keycloak_side_configuration_of_the_agent() throws Exception {
        Path file = Path.of("../compose/keycloak/platform-realm.json");
        if (!Files.exists(file)) {
            file = Path.of("compose/keycloak/platform-realm.json");
        }
        JsonNode realm = JSON.readTree(Files.readString(file));
        JsonNode agentClient = null;
        for (JsonNode client : realm.get("clients")) {
            if ("operator-agent".equals(client.path("clientId").asString())) {
                agentClient = client;
            }
        }
        assertThat(agentClient).as("the operator-agent client in the realm seed").isNotNull();
        // a machine client: client credentials only, no browser flow, no password grant, no implicit
        assertThat(agentClient.path("serviceAccountsEnabled").asBoolean()).isTrue();
        assertThat(agentClient.path("publicClient").asBoolean()).as("confidential").isFalse();
        assertThat(agentClient.path("standardFlowEnabled").asBoolean()).isFalse();
        assertThat(agentClient.path("directAccessGrantsEnabled").asBoolean()).isFalse();
        assertThat(agentClient.path("implicitFlowEnabled").asBoolean()).isFalse();
        // no mapper of its own, and its scopes are exactly the subject and the audience: no role, no tenant claim
        assertThat(agentClient.path("protocolMappers").size()).as("client-level protocol mappers").isZero();
        List<String> scopes = new ArrayList<>();
        agentClient.path("defaultClientScopes").forEach(n -> scopes.add(n.asString()));
        agentClient.path("optionalClientScopes").forEach(n -> scopes.add(n.asString()));
        assertThat(scopes).containsExactlyInAnyOrder("basic", "platform-api-audience");
        for (JsonNode scope : realm.get("clientScopes")) {
            if (scopes.contains(scope.path("name").asString())) {
                for (JsonNode mapper : scope.path("protocolMappers")) {
                    assertThat(mapper.toString().toLowerCase()).as("mapper %s of scope %s",
                            mapper.path("name").asString(), scope.path("name").asString())
                            .doesNotContain("tenant").doesNotContain("realm-management").doesNotContain("role");
                }
            }
        }
        // the realm-wide default scopes do not smuggle a role or tenant mapper onto it either
        for (JsonNode name : realm.path("defaultDefaultClientScopes")) {
            assertThat(name.asString()).isNotEqualTo("platform-admin-roles");
        }
        // its service account holds no realm role, client role or attribute (tenants are not in Keycloak - ADR-007 D1)
        for (JsonNode user : realm.path("users")) {
            if ("operator-agent".equals(user.path("serviceAccountClientId").asString())) {
                assertThat(user.path("realmRoles").size()).isZero();
                assertThat(user.path("clientRoles").size()).isZero();
                assertThat(user.path("attributes").size()).isZero();
            }
        }
        assertThat(realm.path("roles").size()).as("the realm defines no roles at all").isZero();
    }
}
