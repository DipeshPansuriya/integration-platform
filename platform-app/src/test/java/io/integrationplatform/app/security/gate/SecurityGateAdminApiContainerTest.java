package io.integrationplatform.app.security.gate;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.e11.DipeshExampleSeed;
import io.integrationplatform.identity.ConsoleBrowser;
import io.integrationplatform.identity.KeycloakRealmContainer;
import io.integrationplatform.identity.KeycloakRealmContainer.RealmUser;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T171 gate cases over the admin API with a console session (TENANT_ADMIN of tenant A, tenant B beside it), the
 * Dipesh example tenants as the seed: S5-A1 (malformed and oversized mapping save), S5-A2 (JSONata handling on the
 * save and sample-run path), S5-A4 (content-hash spoofing; the publish pin-check leg is NOT driven here - see the
 * method), S5-A5 (cross-tenant mapping-version reference), S5-A6 (hostile lookup keys), S8-A2 (write-only secret API)
 * and S8-A3 (snapshot holds ids only). Container tag: runs only under {@code -Pcontainer}.
 */
@Tag("container")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class SecurityGateAdminApiContainerTest {

    private static final int APP_PORT = freePort();
    private static final URI BACKEND = URI.create("http://localhost:" + APP_PORT);
    private static final String KEK_BASE64 = Base64.getEncoder().encodeToString(randomBytes(32));
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Container
    static final KeycloakRealmContainer KEYCLOAK = new KeycloakRealmContainer(PlatformTestWiring.OIDC_AUDIENCE,
            BACKEND + "/login/oauth2/code/keycloak", BACKEND + "/");

    private static PlatformTestWiring.RestrictedLogin login;
    private static DipeshExampleSeed.Tenant tenantA;
    private static DipeshExampleSeed.Tenant tenantB;
    private static RealmUser adminA;
    private static UUID adminAUserId;
    private static UUID lookupA = UUID.randomUUID();
    private static UUID lookupB = UUID.randomUUID();

    private static final String LOOKUP_JDM = "{\"nodes\":[{\"id\":\"request\",\"type\":\"inputNode\",\"name\":\"Request\","
            + "\"position\":{\"x\":0,\"y\":0}},{\"id\":\"codes\",\"type\":\"decisionTableNode\",\"name\":\"Codes\","
            + "\"position\":{\"x\":240,\"y\":0},\"content\":{\"hitPolicy\":\"first\",\"inputs\":[{\"id\":\"c1\","
            + "\"name\":\"Code\",\"field\":\"code\"}],\"outputs\":[{\"id\":\"o1\",\"name\":\"Name\","
            + "\"field\":\"name\"}],\"rules\":[{\"_id\":\"r1\",\"c1\":\"\\\"NL\\\"\",\"o1\":\"\\\"Netherlands\\\"\"},"
            + "{\"_id\":\"r2\",\"c1\":\"\\\"DE\\\"\",\"o1\":\"\\\"Germany\\\"\"}]}},"
            + "{\"id\":\"response\",\"type\":\"outputNode\",\"name\":\"Response\",\"position\":{\"x\":480,\"y\":0}}],"
            + "\"edges\":[{\"id\":\"e1\",\"sourceId\":\"request\",\"targetId\":\"codes\",\"type\":\"edge\"},"
            + "{\"id\":\"e2\",\"sourceId\":\"codes\",\"targetId\":\"response\",\"type\":\"edge\"}]}";

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
        registry.add("PLATFORM_CREDENTIAL_KEK_K1", () -> KEK_BASE64);
        registry.add("PLATFORM_CREDENTIAL_KEK_ACTIVE", () -> "K1");
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
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
        var clientA = KEYCLOAK.createConfidentialClient("t171adm-a-" + suffix, true);
        var clientB = KEYCLOAK.createConfidentialClient("t171adm-b-" + suffix, true);
        adminA = KEYCLOAK.createUser("adm-admin-" + suffix);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            tenantA = DipeshExampleSeed.onboard(owner, "alpha", "T171ADM-CUST-A", clientA.clientId(),
                    KEYCLOAK.issuerUri());
            tenantB = DipeshExampleSeed.onboard(owner, "bravo", "T171ADM-CUST-B", clientB.clientId(),
                    KEYCLOAK.issuerUri());
            adminAUserId = UUID.randomUUID();
            execute(owner, "INSERT INTO app_user (id, tenant_id, issuer, subject, status) VALUES"
                    + " (?::uuid, ?::uuid, ?, ?, 'ACTIVE')", adminAUserId, tenantA.id(), KEYCLOAK.issuerUri(),
                    adminA.subject());
            execute(owner, "INSERT INTO role_grant (tenant_id, user_id, role) VALUES (?::uuid, ?::uuid, 'TENANT_ADMIN')",
                    tenantA.id(), adminAUserId);
            for (var pair : List.of(Map.entry(tenantA, lookupA), Map.entry(tenantB, lookupB))) {
                var t = pair.getKey();
                execute(owner, "INSERT INTO public.rule_set (id, tenant_id, interface_id, kind, name, content,"
                        + " content_text, author_id) VALUES (?::uuid, ?::uuid, ?::uuid, 'LOOKUP', ?, ?::jsonb, ?,"
                        + " ?::uuid)", pair.getValue(), t.id(), t.interfaceId(), "countries-" + t.word(), LOOKUP_JDM,
                        LOOKUP_JDM, authorOf(owner, t));
                execute(owner, "INSERT INTO public.lookup_table (id, tenant_id, rule_set_id, name)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?)", UUID.randomUUID(), t.id(), pair.getValue(),
                        "countries-" + t.word());
            }
        }
    }

    /** Any user id of the tenant, for the rule_set author column (tenant B has none of ours: use one from its flows). */
    private static UUID authorOf(Connection owner, DipeshExampleSeed.Tenant t) throws Exception {
        String id = scalar(owner, "select author_id::text from public.contract_version where tenant_id = '"
                + t.id() + "' limit 1");
        return UUID.fromString(id);
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
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String db(String sql) throws Exception {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            return scalar(owner, sql);
        }
    }

    private ConsoleBrowser adminBrowser() {
        ConsoleBrowser browser = KEYCLOAK.browser(BACKEND);
        assertThat(browser.login(adminA).status()).as("console login").isEqualTo(302);
        browser.get("/api/v1/me");
        return browser;
    }

    private record Facts(String mappingId, String source, String target) {
    }

    private static Facts mappingOf(DipeshExampleSeed.Tenant t) throws Exception {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement st = owner.createStatement();
                ResultSet rs = st.executeQuery("select id::text, source_contract_version_id::text,"
                        + " target_contract_version_id::text from public.mapping where route_id = '" + t.routeA() + "'")) {
            rs.next();
            return new Facts(rs.getString(1), rs.getString(2), rs.getString(3));
        }
    }

    /** The mapping row plus its version count: what a refused save must leave exactly as it was. */
    private static String mappingState(String mappingId) throws Exception {
        return db("select m.compiled_expression || '|' || m.field_mappings::text || '|' || (select count(*)"
                + " from public.mapping_version v where v.mapping_id = m.id) from public.mapping m where m.id = '"
                + mappingId + "'");
    }

    private static String saveBody(String fieldMappings, String expressionJsonText, Facts f) {
        return "{\"fieldMappings\":" + fieldMappings + ",\"compiledExpression\":" + expressionJsonText
                + ",\"sourceContractVersionId\":\"" + f.source() + "\",\"targetContractVersionId\":\"" + f.target()
                + "\"}";
    }

    private static String quote(String text) {
        return JSON.writeValueAsString(text);
    }

    private static final String ROUTES = "/api/v1/admin/routes/";

    /**
     * T196: outbox rows only. Every audit event is exactly one outbox row, kept after the relay stamps relayed_at
     * (V2_4_3 grants no DELETE), so this is exact and monotonic. Adding audit_event (the relay's copy of the same
     * event) double-counted a row seen mid-relay and could hide one missing event.
     */
    private static long auditCount(UUID tenant) throws Exception {
        return Long.parseLong(db("select count(*) from public.audit_outbox where action = 'LOOKUP_ENTRY_CHANGED'"
                + " and tenant_id = '" + tenant + "'"));
    }

    // ------------------------------------------------------------------ S5-A1

    /**
     * S5-A1: Malformed and oversized input on mapping save.
     * Expected: Clean 4xx (no 5xx stack); server-side size/depth limits; **no mapping_version row created on a failed save** (ADR-013 rule 2)
     */
    @Test
    void s5_a1_malformed_and_oversized_mapping_save_is_a_clean_4xx_and_writes_nothing() throws Exception {
        ConsoleBrowser admin = adminBrowser();
        Facts f = mappingOf(tenantA);
        String path = ROUTES + tenantA.routeA() + "/mapping";
        ConsoleBrowser.Exchange control = admin.api("PUT", path, saveBody("[]", quote("{\"customer_code\": customerId}"), f), true);
        assertThat(control.status()).as("positive control (a valid save): %s", control.body()).isEqualTo(200);
        String before = mappingState(f.mappingId());

        String big = "A".repeat(10 * 1024 * 1024);
        Map<String, String> hostile = new LinkedHashMap<>();
        hostile.put("truncated JSON", "{\"fieldMappings\":[{\"source\":\"$.a\",\"target\":\"customer_code\"");
        hostile.put("not JSON at all", "\u0001\u0002 not json");
        hostile.put("nesting depth 10000", "{\"fieldMappings\":" + "[".repeat(10_000) + "]".repeat(10_000)
                + ",\"compiledExpression\":\"$\",\"sourceContractVersionId\":\"" + f.source()
                + "\",\"targetContractVersionId\":\"" + f.target() + "\"}");
        hostile.put("10 MB field_mappings", saveBody("[{\"source\":\"$.a\",\"target\":\"customer_code\",\"note\":\""
                + big + "\"}]", quote("$"), f));
        hostile.put("10 MB compiledExpression", saveBody("[]", quote("$" + big), f));
        hostile.put("null byte in the expression", saveBody("[]", "\"$\\u0000x\"", f));
        hostile.put("field_mappings is not an array", saveBody("{\"a\":1}", quote("$"), f));
        hostile.put("link target outside the target schema", saveBody(
                "[{\"source\":\"$.a\",\"target\":\"not_a_field_of_the_contract\"}]", quote("$"), f));
        // contract-version ids that are not UUIDs (T171 review B1): the SQL casts them, so they were a database error
        String good = "\"" + f.source() + "\"";
        for (String badId : new String[] {"\"abc\"", "\"\"", "\" \"", "\"5555\"", "\"$\\u0000x\"",
                "\"'; drop table mapping; --\"", "123", "{\"a\":1}"}) {
            hostile.put("source id " + badId, "{\"fieldMappings\":[],\"compiledExpression\":\"$\","
                    + "\"sourceContractVersionId\":" + badId + ",\"targetContractVersionId\":\"" + f.target() + "\"}");
            hostile.put("target id " + badId, "{\"fieldMappings\":[],\"compiledExpression\":\"$\","
                    + "\"sourceContractVersionId\":" + good + ",\"targetContractVersionId\":" + badId + "}");
        }
        List<String> failures = new ArrayList<>();
        for (var attempt : hostile.entrySet()) {
            ConsoleBrowser.Exchange r = admin.api("PUT", path, attempt.getValue(), true);
            // L15: a body over the admin API's 2 MB request limit is a 413, before the body is read
            int expected = attempt.getKey().startsWith("10 MB") ? 413 : 400;
            if (r.status() != expected) { // review C4: the specific refusal, not any 4xx (a 401/403/404 would hide a broken call)
                failures.add(attempt.getKey() + " -> " + r.status() + " (body " + r.body().length() + " chars)");
            } else if (r.body().length() > 20_000 || r.body().contains("\tat ") || r.body().contains("org.postgresql")
                    || !r.body().contains("\"error\"")) {
                failures.add(attempt.getKey() + " -> refusal body not clean (" + r.body().length() + " chars)");
            }
        }
        // a route id that is not a UUID: not found, never a database error
        ConsoleBrowser.Exchange badRoute = admin.api("PUT", ROUTES + "not-a-uuid/mapping",
                saveBody("[]", quote("$"), f), true);
        if (badRoute.status() != 404) {
            failures.add("route id not-a-uuid -> " + badRoute.status());
        }
        // a wrong content type is a 415, not the catch-all 500 (review C2)
        ConsoleBrowser.Exchange wrongType = admin.api("PUT", path, "hello", true);
        assertThat(wrongType.status()).as("text body: %s", wrongType.body()).isEqualTo(415);
        assertThat(failures).as("attempts that were not a clean 400 (server-side size/depth/validation limits)").isEmpty();
        assertThat(mappingState(f.mappingId())).as("no mapping row change / mapping_version row on a failed save")
                .isEqualTo(before);
        // duplicate JSON keys: either refused, or accepted with exactly one new version (no half state, no 5xx)
        String dup = "{\"fieldMappings\":[],\"compiledExpression\":\"$\",\"compiledExpression\":\"$$\","
                + "\"sourceContractVersionId\":\"" + f.source() + "\",\"targetContractVersionId\":\"" + f.target() + "\"}";
        ConsoleBrowser.Exchange dupResult = admin.api("PUT", path, dup, true);
        assertThat(dupResult.status()).as("duplicate keys: %s", dupResult.body()).isLessThan(500);
        System.out.println("S5-A1 duplicate keys -> " + dupResult.status());
    }

    // ------------------------------------------------------------------ S5-A2

    /**
     * S5-A2: JSONata expression handling.
     * Expected: The sandbox treats expression as data: no file/network/process escape from the compile or sample-run path; budget overrun fails clean (not a hang)
     */
    @Test
    void s5_a2_jsonata_expressions_are_data_and_overruns_fail_clean() throws Exception {
        ConsoleBrowser admin = adminBrowser();
        Facts f = mappingOf(tenantA);
        String save = ROUTES + tenantA.routeA() + "/mapping";
        String test = "/api/v1/admin/mappings/" + f.mappingId() + "/test";
        String sample = "{\"sample\":{\"customerId\":\"c1\",\"orderId\":\"o1\",\"amount\":1}}";
        Map<String, String> expressions = new LinkedHashMap<>();
        expressions.put("read a file", "$readFile(\"/etc/passwd\")");
        expressions.put("run a process", "$exec(\"id\")");
        expressions.put("require a module", "$require(\"fs\")");
        expressions.put("eval", "$eval(\"1+1\")");
        expressions.put("network fetch (metadata address)", "$fetch(\"http://169.254.169.254/latest/meta-data/\")");
        expressions.put("http client", "$http(\"http://127.0.0.1:1/\")");
        expressions.put("java class reach", "$.class.forName(\"java.lang.Runtime\")");
        expressions.put("constructor call", "$new(\"java.io.File\", \"/\")");
        expressions.put("embedded js payload", "function(){ return process.env }()");
        expressions.put("template injection", "\"${7*7}\" & \"{{7*7}}\"");
        expressions.put("lookup chain", "$lookup($lookup($lookup($,\"a\"),\"b\"),\"c\")");
        expressions.put("unbounded recursion", "($f := function($n){ 1 + $f($n+1) }; $f(0))");
        expressions.put("endless loop (budget)", "($f := function($n){ $f($n) }; $f(1))");
        expressions.put("huge range (budget)", "$sum($map([1..2000000000], function($v){$v*2}))");
        List<String> notes = new ArrayList<>();
        for (var e : expressions.entrySet()) {
            ConsoleBrowser.Exchange saved = admin.api("PUT", save, saveBody("[]", quote(e.getValue()), f), true);
            assertThat(saved.status()).as("%s: save is 200 or a clean 4xx, got %s", e.getKey(), saved.body())
                    .isBetween(200, 499).isNotIn(201, 202, 204);
            Instant start = Instant.now();
            ConsoleBrowser.Exchange ran = admin.api("POST", test, sample, true);
            Duration took = Duration.between(start, Instant.now());
            assertThat(took).as("%s: the run returns inside the budget, no hang", e.getKey())
                    .isLessThan(Duration.ofSeconds(45));
            assertThat(ran.status()).as("%s: sample run answers cleanly: %s", e.getKey(), ran.body())
                    .isBetween(200, 499);
            if (ran.status() == 200) {
                JsonNode body = JSON.readTree(ran.body());
                assertThat(body.path("status").asString()).as("%s: run status", e.getKey())
                        .isIn("OK", "ERROR", "MAPPING_TIMEOUT");
                assertThat(ran.body()).as("%s: nothing from the host reached the output", e.getKey())
                        .doesNotContain("root:x:").doesNotContain("uid=").doesNotContain("ami-id")
                        .doesNotContain("java.lang.Runtime@").doesNotContain("\tat ");
                notes.add(e.getKey() + "=" + saved.status() + "/" + body.path("status").asString() + "/"
                        + took.toMillis() + "ms");
            } else {
                notes.add(e.getKey() + "=" + saved.status() + "/" + ran.status());
            }
        }
        // the application is still alive and runs a clean expression after the abuse (positive control)
        ConsoleBrowser.Exchange ok = admin.api("PUT", save,
                saveBody("[]", quote("{\"customer_code\": customerId}"), f), true);
        assertThat(ok.status()).isEqualTo(200);
        ConsoleBrowser.Exchange ran = admin.api("POST", test, sample, true);
        assertThat(JSON.readTree(ran.body()).path("status").asString()).as("control run: %s", ran.body()).isEqualTo("OK");
        System.out.println("S5-A2 outcomes: " + notes);
    }

    // ------------------------------------------------------------------ S5-A4

    /**
     * S5-A4: content_sha256 spoofing attempts (ADR-013).
     * Expected: Publish pin check refuses on hash mismatch; the immutability guard trigger refuses content change of the stored version; mismatch is a named refusal
     *
     * <p>Driven here: (1) the application role cannot UPDATE or DELETE a stored mapping_version (its content, its hash);
     * (2) an API re-save with new content leaves the old version row byte-identical and adds a version whose hash is
     * not the old one (the hash follows the content, the caller cannot name it); (3) the interface_version guard
     * trigger refuses a snapshot change even for the owner. NOT driven: the publish endpoint's pin-check refusal on a
     * hash mismatch (needs a full draft - submit - approve - publish walk of a flow; covered by the
     * config-module pin-check tests, not re-proved here), so the gate counts this case as partial.
     */
    @Test
    void s5_a4_content_hash_cannot_be_spoofed_or_rewritten() throws Exception {
        ConsoleBrowser admin = adminBrowser();
        Facts f = mappingOf(tenantA);
        String path = ROUTES + tenantA.routeA() + "/mapping";
        assertThat(admin.api("PUT", path, saveBody("[]", quote("{\"customer_code\": customerId}"), f), true).status())
                .isEqualTo(200);
        String oldVersion = db("select id::text from public.mapping_version where mapping_id = '" + f.mappingId()
                + "' order by version desc limit 1");
        String oldRow = db("select row_to_json(v)::text from public.mapping_version v where id = '" + oldVersion + "'");
        // (1) the application role: no UPDATE, no DELETE on the immutable content
        try (Connection app = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), login.username(), login.password());
                Statement st = app.createStatement()) {
            st.execute("select set_config('app.tenant_id', '" + tenantA.id() + "', false)");
            for (String sql : List.of(
                    "update public.mapping_version set compiled_expression = '$$' where id = '" + oldVersion + "'",
                    "update public.mapping_version set content_sha256 = decode(repeat('00', 32), 'hex') where id = '"
                            + oldVersion + "'",
                    "delete from public.mapping_version where id = '" + oldVersion + "'")) {
                try {
                    st.executeUpdate(sql);
                    throw new AssertionError("the application role changed an immutable mapping_version: " + sql);
                } catch (SQLException refused) {
                    assertThat(refused.getSQLState()).as(sql).isEqualTo("42501");
                }
            }
        }
        // (2) new content through the API: the old version row is untouched, the new one has its own hash
        assertThat(admin.api("PUT", path, saveBody("[]", quote("{\"customer_code\": orderId}"), f), true).status())
                .isEqualTo(200);
        assertThat(db("select row_to_json(v)::text from public.mapping_version v where id = '" + oldVersion + "'"))
                .as("the stored version is byte-identical").isEqualTo(oldRow);
        assertThat(db("select count(distinct content_sha256) = count(*) from public.mapping_version where mapping_id = '"
                + f.mappingId() + "'")).as("one hash per distinct content").isEqualTo("t");
        // a caller-named hash is not an input: the save body ignores or refuses it, and the stored hash is the content's
        ConsoleBrowser.Exchange named = admin.api("PUT", path, "{\"fieldMappings\":[],\"compiledExpression\":\"$\","
                + "\"contentSha256\":\"" + "00".repeat(32) + "\",\"sourceContractVersionId\":\"" + f.source()
                + "\",\"targetContractVersionId\":\"" + f.target() + "\"}", true);
        assertThat(named.status()).as("a body naming a hash: %s", named.body()).isLessThan(500);
        assertThat(db("select count(*) from public.mapping_version where mapping_id = '" + f.mappingId()
                + "' and content_sha256 = decode('" + "00".repeat(32) + "', 'hex')")).isEqualTo("0");
        // (3) the lifecycle guard: a published snapshot cannot be rewritten, even by the owner
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement st = owner.createStatement()) {
            try {
                st.executeUpdate("update public.interface_version set snapshot = '{}'::jsonb where tenant_id = '"
                        + tenantA.id() + "'");
                throw new AssertionError("the owner rewrote a config version snapshot");
            } catch (SQLException refused) {
                assertThat(refused.getMessage()).contains("immutable");
            }
        }
    }

    // ------------------------------------------------------------------ S5-A5

    /**
     * S5-A5: Cross-tenant mapping-version reference (ADR-013 conformance 3).
     * Expected: Composite FKs refuse; 403/4xx + audit; matches S2-A7 from the input side
     */
    @Test
    void s5_a5_cross_tenant_mapping_references_are_refused() throws Exception {
        ConsoleBrowser admin = adminBrowser();
        Facts a = mappingOf(tenantA);
        Facts b = mappingOf(tenantB);
        String stateA = mappingState(a.mappingId());
        String stateB = mappingState(b.mappingId());
        String versionsB = db("select count(*) || ':' || string_agg(id::text, ',' order by id) from public.mapping_version"
                + " where tenant_id = '" + tenantB.id() + "'");
        // A's admin on B's route and B's mapping: not visible
        ConsoleBrowser.Exchange onB = admin.api("PUT", ROUTES + tenantB.routeA() + "/mapping",
                saveBody("[]", quote("$"), b), true);
        assertThat(onB.status()).as("save on tenant B's route: %s", onB.body()).isIn(403, 404);
        assertThat(onB.body()).doesNotContain(tenantB.id().toString()).doesNotContain(b.mappingId());
        ConsoleBrowser.Exchange readB = admin.api("GET", ROUTES + tenantB.routeA() + "/mapping", null, false);
        assertThat(readB.status()).isIn(403, 404);
        ConsoleBrowser.Exchange testB = admin.api("POST", "/api/v1/admin/mappings/" + b.mappingId() + "/test",
                "{\"sample\":{\"a\":1}}", true);
        assertThat(testB.status()).as("sample run on tenant B's mapping: %s", testB.body()).isIn(403, 404);
        // A's own route bound to B's contract versions (and to a mix): refused
        for (String[] pair : new String[][] {{b.source(), b.target()}, {a.source(), b.target()}, {b.source(), a.target()}}) {
            String body = "{\"fieldMappings\":[],\"compiledExpression\":\"$\",\"sourceContractVersionId\":\"" + pair[0]
                    + "\",\"targetContractVersionId\":\"" + pair[1] + "\"}";
            ConsoleBrowser.Exchange r = admin.api("PUT", ROUTES + tenantA.routeA() + "/mapping", body, true);
            assertThat(r.status()).as("A's route bound to B's contract version(s): %s", r.body()).isBetween(400, 499);
            assertThat(r.body()).doesNotContain(tenantB.id().toString());
        }
        assertThat(mappingState(a.mappingId())).as("A's mapping unchanged").isEqualTo(stateA);
        assertThat(mappingState(b.mappingId())).as("B's mapping unchanged").isEqualTo(stateB);
        assertThat(db("select count(*) || ':' || string_agg(id::text, ',' order by id) from public.mapping_version"
                + " where tenant_id = '" + tenantB.id() + "'")).isEqualTo(versionsB);
        // the database refuses the same shapes with no application in the way (composite foreign keys)
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES); Statement st = owner.createStatement()) {
            for (String sql : List.of(
                    "update public.mapping set source_contract_version_id = '" + b.source() + "' where id = '"
                            + a.mappingId() + "'",
                    "insert into public.mapping_version (tenant_id, mapping_id, version, source_contract_version_id,"
                            + " target_contract_version_id, engine, field_mappings, compiled_expression, content_sha256,"
                            + " author_id) values ('" + tenantA.id() + "', '" + a.mappingId() + "', 9999, '" + b.source()
                            + "', '" + a.target() + "', 'JSONATA', '[]', '$', decode(repeat('ab', 32), 'hex'), '"
                            + adminAUserId + "')",
                    "insert into public.mapping_version (tenant_id, mapping_id, version, source_contract_version_id,"
                            + " target_contract_version_id, engine, field_mappings, compiled_expression, content_sha256,"
                            + " author_id) values ('" + tenantA.id() + "', '" + b.mappingId() + "', 9998, '" + a.source()
                            + "', '" + a.target() + "', 'JSONATA', '[]', '$', decode(repeat('cd', 32), 'hex'), '"
                            + adminAUserId + "')")) {
                try {
                    st.executeUpdate(sql);
                    throw new AssertionError("a cross-tenant reference was accepted by the database: " + sql);
                } catch (SQLException refused) {
                    assertThat(refused.getSQLState()).as(sql).isEqualTo("23503");
                }
            }
        }
    }

    // ------------------------------------------------------------------ S5-A6

    /**
     * S5-A6: Lookup edits (E7-F3) with hostile keys.
     * Expected: Edits validated and audited (E7-C12); lookups cannot inject execution into the transform path; no cross-tenant lookup reference
     */
    @Test
    void s5_a6_lookup_edits_with_hostile_keys() throws Exception {
        ConsoleBrowser admin = adminBrowser();
        String entry = "/api/v1/admin/lookups/" + lookupA + "/entries/r1";
        ConsoleBrowser.Exchange control = admin.api("PUT", entry, "{\"key\":\"NL\",\"value\":\"Kingdom of the Netherlands\"}", true);
        assertThat(control.status()).as("positive control: %s", control.body()).isEqualTo(200);
        String contentB = db("select content::text from public.rule_set where id = '" + lookupB + "'");
        long auditBase = 0; // the control edit is audited; wait for it, then take the baseline (review C6)
        for (int i = 0; i < 30 && auditBase < 1; i++) {
            auditBase = auditCount(tenantA.id());
            if (auditBase < 1) {
                Thread.sleep(1000);
            }
        }
        Thread.sleep(2000);
        auditBase = auditCount(tenantA.id());

        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("JSONata fragment", "$exec(\"id\")");
        keys.put("JSONata function literal", "function(){ $readFile(\"/etc/passwd\") }()");
        keys.put("quote and comment", "\" or 1=1 --");
        keys.put("JSON injection of a rule", "NL\"},{\"_id\":\"r9\",\"c1\":\"\\\"x\\\"\",\"o1\":\"\\\"y\\\"\"}]}},{\"id\":\"");
        keys.put("control characters", "NL\n\r\t\u0007");
        keys.put("200k character key", "K".repeat(200_000));
        keys.put("another tenant's flow code", "src-bravo");
        int accepted = 0;
        List<String> failures = new ArrayList<>();
        for (var k : keys.entrySet()) {
            String body = "{\"key\":" + quote(k.getValue()) + ",\"value\":\"v\"}";
            ConsoleBrowser.Exchange r = admin.api("PUT", entry, body, true);
            if (r.status() >= 500) {
                failures.add(k.getKey() + " -> " + r.status());
                continue;
            }
            assertThat(r.body()).doesNotContain("\tat ").doesNotContain(tenantB.id().toString());
            String content = db("select content::text from public.rule_set where id = '" + lookupA + "'");
            JsonNode model = JSON.readTree(content); // still valid JSON, whatever was typed
            int rules = 0;
            for (JsonNode node : model.path("nodes")) {
                rules += node.path("content").path("rules").size();
            }
            assertThat(rules).as("%s: no rule injected through the key (%s)", k.getKey(), r.status()).isEqualTo(2);
            assertThat(model.path("nodes").size()).as("%s: no node injected", k.getKey()).isEqualTo(3);
            if (r.status() == 200) {
                accepted++;
            }
        }
        assertThat(failures).as("hostile keys answered with a 5xx").isEmpty();
        // no cross-tenant lookup reference
        ConsoleBrowser.Exchange onB = admin.api("PUT", "/api/v1/admin/lookups/" + lookupB + "/entries/r1",
                "{\"key\":\"NL\",\"value\":\"stolen\"}", true);
        assertThat(onB.status()).as("edit of tenant B's lookup: %s", onB.body()).isIn(403, 404);
        assertThat(admin.api("GET", "/api/v1/admin/lookups/" + lookupB + "/entries", null, false).status()).isIn(403, 404);
        assertThat(db("select content::text from public.rule_set where id = '" + lookupB + "'"))
                .as("tenant B's lookup unchanged").isEqualTo(contentB);
        // audited: the successful edits left LOOKUP_ENTRY_CHANGED events (outbox, then the event table)
        Instant until = Instant.now().plusSeconds(40);
        long audited = auditCount(tenantA.id());
        while (Instant.now().isBefore(until) && audited < auditBase + accepted) {
            Thread.sleep(1000);
            audited = auditCount(tenantA.id());
        }
        assertThat(audited).as("every accepted hostile-key edit (%s) added an audit event over the baseline %s", accepted,
                auditBase).isGreaterThanOrEqualTo(auditBase + accepted);
        System.out.println("S5-A6 hostile keys accepted as data: " + accepted + " of " + keys.size());
    }

    // ------------------------------------------------------------------ S8-A2 / S8-A3

    private record Secret(String value, String credentialRowsText) {
    }

    /** Everything a credential could leak through: the secret, the row's ciphertext and wrapped key in every encoding. */
    private static List<String> leakNeedles(String secret, Connection owner) throws Exception {
        List<String> needles = new ArrayList<>(List.of(secret, KEK_BASE64));
        try (Statement st = owner.createStatement();
                ResultSet rs = st.executeQuery("select ciphertext, wrapped_key from public.target_credential where"
                        + " tenant_id = '" + tenantA.id() + "'")) {
            while (rs.next()) {
                for (int i = 1; i <= 2; i++) {
                    byte[] blob = rs.getBytes(i);
                    needles.add(HexFormat.of().formatHex(blob));
                    needles.add(Base64.getEncoder().encodeToString(blob));
                    needles.add(Base64.getUrlEncoder().withoutPadding().encodeToString(blob));
                }
            }
        }
        return needles;
    }

    /** The text of every audit and snapshot row of the tenant. */
    private static String storedEvidenceText(Connection owner) throws Exception {
        StringBuilder all = new StringBuilder();
        for (String table : List.of("audit_event", "audit_outbox", "interface_version")) {
            try (Statement st = owner.createStatement();
                    ResultSet rs = st.executeQuery("select row_to_json(t)::text from public." + table
                            + " t where t.tenant_id = '" + tenantA.id() + "'")) {
                while (rs.next()) {
                    all.append(rs.getString(1)).append('\n');
                }
            }
        }
        return all.toString();
    }

    private String saveSecret(ConsoleBrowser admin, String secret) {
        ConsoleBrowser.Exchange saved = admin.api("PUT",
                "/api/v1/admin/target-endpoints/" + tenantA.endpointA() + "/credentials",
                "{\"fields\":{\"apiKey\":" + quote(secret) + "}}", true);
        assertThat(saved.status()).as("target credential save: %s", saved.body()).isEqualTo(200);
        return saved.body();
    }

    /**
     * S8-A2: Write-only secret API (rule 8).
     * Expected: Response shows masked/absent secret values only - never the value, ciphertext or wrapped key, in API responses, exports or audit rows
     */
    @Test
    void s8_a2_write_only_secret_api(CapturedOutput output) throws Exception {
        ConsoleBrowser admin = adminBrowser();
        int logFrom = output.getAll().length();
        String secret = "s3cret-" + UUID.randomUUID();
        String putBody = saveSecret(admin, secret);
        String url = "/api/v1/admin/target-endpoints/" + tenantA.endpointA() + "/credentials";
        String getBody = admin.api("GET", url, null, false).body();
        String systems = admin.api("GET", "/api/v1/admin/systems", null, false).body();
        String flows = admin.api("GET", "/api/v1/admin/flows", null, false).body();
        // T201 (T171-3): the first save's ciphertext and wrapped key are replaced by the second save, so capture
        // them as needles NOW; the first PUT and GET responses are then scanned for them too
        List<String> firstNeedles;
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            firstNeedles = leakNeedles(secret, owner);
        }
        assertThat(firstNeedles).as("secret, KEK and the first row's ciphertext and wrapped key in 3 encodings each")
                .hasSizeGreaterThanOrEqualTo(2 + 6);
        // a second update mints a fresh data key (S8-A6 through the API)
        String wrapped1 = db("select encode(wrapped_key, 'hex') from public.target_credential where tenant_id = '"
                + tenantA.id() + "' limit 1");
        String secret2 = "s3cret2-" + UUID.randomUUID();
        String putBody2 = saveSecret(admin, secret2);
        String wrapped2 = db("select encode(wrapped_key, 'hex') from public.target_credential where tenant_id = '"
                + tenantA.id() + "' limit 1");
        assertThat(wrapped2).as("a new wrapped data key on update").isNotEqualTo(wrapped1);
        assertThat(JSON.readTree(getBody).path("fields").get(0).path("value").asString()).as("masked").isEqualTo("********");
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            List<String> needles = new ArrayList<>(firstNeedles);
            needles.addAll(leakNeedles(secret2, owner));
            String evidence = storedEvidenceText(owner);
            String logText = output.getAll().substring(logFrom);
            Map<String, String> surfaces = Map.of("PUT response", putBody, "second PUT response", putBody2,
                    "GET response", getBody, "systems list", systems, "flows list", flows,
                    "audit rows + snapshots", evidence, "backend log", logText);
            surfaces.forEach((name, text) -> needles.forEach(needle -> assertThat(text)
                    .as("%s must not carry a secret, ciphertext, wrapped key or KEK", name).doesNotContain(needle)));
        }
        // the secret exists sealed, and tenant B's admin surface does not expose A's endpoint
        assertThat(db("select count(*) from public.target_credential where tenant_id = '" + tenantA.id() + "'"))
                .isNotEqualTo("0");
        assertThat(admin.api("GET", "/api/v1/admin/target-endpoints/" + tenantB.endpointA() + "/credentials", null,
                false).status()).isIn(403, 404);
    }

    /**
     * S8-A3: Snapshot holds ids only (rule 9).
     * Expected: Only credential **ids**; 0 occurrences of target_credential value/ciphertext/wrapped-key fields
     *
     * <p>Driven: a credential exists on the published flow's target; every stored interface_version snapshot of the
     * tenant is scanned for the value, the ciphertext, the wrapped key and the KEK, and for credential field names; and
     * the snapshot's own type (FlowBundle and its nested records) has no component that could carry one. NOT driven: a
     * fresh publish after the credential was saved (the seeded snapshot is written by the same serializer as publish,
     * but the draft - approve - publish walk is not repeated), so the gate counts this case as partial.
     */
    @Test
    void s8_a3_snapshot_holds_ids_only() throws Exception {
        ConsoleBrowser admin = adminBrowser();
        String secret = "snap-" + UUID.randomUUID();
        saveSecret(admin, secret);
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            List<String> needles = leakNeedles(secret, owner);
            StringBuilder snapshots = new StringBuilder();
            try (Statement st = owner.createStatement();
                    ResultSet rs = st.executeQuery("select snapshot::text from public.interface_version where tenant_id = '"
                            + tenantA.id() + "'")) {
                while (rs.next()) {
                    snapshots.append(rs.getString(1));
                }
            }
            assertThat(snapshots.length()).as("there is a snapshot to scan").isGreaterThan(100);
            needles.forEach(n -> assertThat(snapshots.toString()).doesNotContain(n));
            for (String field : List.of("ciphertext", "wrapped_key", "wrappedKey", "secret", "password", "apiKey",
                    "kek", "target_credential", "targetCredential")) {
                assertThat(snapshots.toString().toLowerCase()).as("snapshot field name %s", field)
                        .doesNotContain("\"" + field.toLowerCase() + "\"");
            }
        }
        List<String> components = new ArrayList<>();
        collect(io.integrationplatform.config.flow.application.FlowBundle.class, components, new java.util.HashSet<>());
        assertThat(components).as("record components reachable from the snapshot type: %s", components)
                .noneMatch(c -> c.toLowerCase().matches(".*(ciphertext|wrappedkey|wrapped_key|secret|password|kek).*"));
    }

    private static void collect(Class<?> type, List<String> out, java.util.Set<Class<?>> seen) {
        if (!seen.add(type)) {
            return;
        }
        if (type.isRecord()) {
            for (var c : type.getRecordComponents()) {
                out.add(type.getSimpleName() + "." + c.getName());
                collect(c.getType(), out, seen);
                if (c.getGenericType() instanceof java.lang.reflect.ParameterizedType p) {
                    for (var arg : p.getActualTypeArguments()) {
                        if (arg instanceof Class<?> cls) {
                            collect(cls, out, seen);
                        }
                    }
                }
            }
        } else {
            for (Class<?> nested : type.getDeclaredClasses()) {
                collect(nested, out, seen);
            }
        }
    }
}
