package io.integrationplatform.config.e3;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.e3.ConfigAdminApi.Response;
import io.integrationplatform.config.e3.E3ConfigDatabase.RestrictedLogin;
import io.integrationplatform.config.e3.LaneCLifecycle.Actors;
import io.integrationplatform.e3lanec.E3LaneCSuiteApp;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E7-F2-S3 / T-038 (backlog.md E7-F2-S3 "Test round trip under 500 ms", criteria E7-C6/E7-C9): "Given a mapping and its sample, When the
 * configurator runs the test 100 times, Then every round trip is under 500 ms". One stored mapping and one sample
 * go through the REAL endpoint {@code POST /api/v1/admin/mappings/{id}/test} - real HTTP on the real port, the real
 * OIDC resource-server chain and tenant binding (E3LaneCSuiteApp), the stamped transaction on PostgreSQL and the
 * real Dashjoin JSONata TransformPort - 100 times, and EVERY call is asserted under the bound (the per-call max,
 * never an average). min/p50/p95/max are printed for the evidence log.
 *
 * <p>Warm-up: {@value #WARM_UP} calls run first and are printed but not asserted. They absorb the one-time costs
 * no configurator pays twice - the first use of the Hikari pool's connections, class loading and the first JIT
 * tiers of the request path and the JSONata engine, the first JWT verification and Jackson serializer caches -
 * so the 100 measured calls show the steady state the criterion speaks about. Ten is enough: the measured calls
 * themselves are the proof (a warm-up too short would show as a slow measured call, which fails the test).
 *
 * <p>The test bites: its red commit planted a 600 ms servlet filter on ONE measured call (#57) and failed on
 * that call alone while the mean stayed 22.7 ms - the bound is checked per call, never on the average.
 */
@Tag("container")
@SpringBootTest(classes = E3LaneCSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class T038MappingTestRoundTripPerformanceContainerTest {

    static final int WARM_UP = 10;
    static final int ROUND_TRIPS = 100;
    static final long BOUND_MS = 500;

    private static final String ROUTES = "/api/v1/admin/routes";
    private static final String MAPPINGS = "/api/v1/admin/mappings";

    /** A configurator-sized mapping: renames, a nested path, a concatenation, an aggregate and a projection. */
    private static final String FIELD_MAPPINGS = "[{\"source\":\"customerId\",\"target\":\"customer_code\"},"
            + "{\"source\":\"orderId\",\"target\":\"order_ref\"},"
            + "{\"source\":\"address.city\",\"target\":\"ship_to\"},"
            + "{\"source\":\"lines\",\"target\":\"items\"}]";
    private static final String EXPRESSION = "{\"customer_code\": customerId, \"order_ref\": orderId,"
            + " \"ship_to\": address.city & \", \" & address.country,"
            + " \"line_count\": $count(lines), \"total_qty\": $sum(lines.qty),"
            + " \"items\": [lines.{\"sku\": sku, \"qty\": qty}]}";
    private static final String SAMPLE = "{\"sample\":{\"customerId\":\"acme-1\",\"orderId\":\"PO-1001\","
            + "\"address\":{\"city\":\"Pune\",\"country\":\"IN\"},"
            + "\"lines\":[{\"sku\":\"A-1\",\"qty\":2},{\"sku\":\"B-7\",\"qty\":5},{\"sku\":\"C-3\",\"qty\":1}]}}";

    /** The target contract carries every field the mapping writes (the save checks each target root). */
    private static final String TARGET_SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"customer_code\":{\"type\":\"string\"},\"order_ref\":{\"type\":\"string\"},"
            + "\"ship_to\":{\"type\":\"string\"},\"line_count\":{\"type\":\"integer\"},"
            + "\"total_qty\":{\"type\":\"number\"},\"items\":{\"type\":\"array\"}}}";

    private static final AtomicBoolean SEEDED = new AtomicBoolean();
    private static final LaneCIdentities TOKENS = E3LaneCSuiteApp.TOKENS;

    private static RestrictedLogin login;
    private static String approverSubject;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        PostgreSQLContainer postgres = E3ConfigDatabase.postgres();
        if (SEEDED.compareAndSet(false, true)) {
            E3ConfigDatabase.seedDirectory();
            approverSubject = LaneCIdentities.seedApproverOfTenantA();
        }
        login = E3ConfigDatabase.restrictedLogin();
        LaneCIdentities.assertPremise(login, approverSubject);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> login.username());
        registry.add("spring.datasource.password", () -> login.password());
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> E3ConfigDatabase.OIDC_ISSUER.toString());
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences",
                () -> E3ConfigDatabase.OIDC_AUDIENCE);
        registry.add("spring.security.oauth2.resourceserver.jwt.jws-algorithms", () -> "RS256");
    }

    @Test
    @DisplayName("Scenario: Test round trip under 500 ms - 100 runs of one mapping on its sample, every one under the bound")
    void everyOfTheHundredRoundTripsIsUnderHalfASecond() throws SQLException {
        String base = ConfigAdminApi.baseUrl(port);
        Actors actor = Actors.of(TOKENS, E3ConfigDatabase.tenantA().subject(), approverSubject,
                E3ConfigDatabase.tenantB().subject());
        LaneCLifecycle.Draft draft = draftWithApprovedSource(base, actor, "1.0");
        UUID route = LaneCLifecycle.routeIdOfLiveRow(draft.flowId(), 1);
        UUID mappingId = saveMapping(base, actor, route, draft, FIELD_MAPPINGS, EXPRESSION);
        String url = base + MAPPINGS + "/" + mappingId + "/test";
        String bearer = actor.author();

        List<Long> warmUpMs = new ArrayList<>();
        for (int i = 0; i < WARM_UP; i++) {
            warmUpMs.add(timedRoundTrip(url, bearer, i, "warm-up") / 1_000_000L);
        }

        List<Long> nanos = new ArrayList<>(ROUND_TRIPS);
        for (int i = 0; i < ROUND_TRIPS; i++) {
            nanos.add(timedRoundTrip(url, bearer, i, "measured"));
        }

        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        double meanMs = nanos.stream().mapToLong(Long::longValue).average().orElseThrow() / 1_000_000d;
        String stats = String.format(
                "T-038 mapping test round trip: warm-up=%d (ms %s) measured=%d min=%.1fms p50=%.1fms p95=%.1fms"
                        + " max=%.1fms mean=%.1fms bound=%dms",
                WARM_UP, warmUpMs, ROUND_TRIPS, ms(sorted.get(0)), ms(percentile(sorted, 50)),
                ms(percentile(sorted, 95)), ms(sorted.get(sorted.size() - 1)), meanMs, BOUND_MS);
        System.out.println(stats);

        for (int i = 0; i < nanos.size(); i++) {
            assertThat(nanos.get(i) / 1_000_000L)
                    .as("round trip #%d of %d is under %d ms (%s)", i + 1, ROUND_TRIPS, BOUND_MS, stats)
                    .isLessThan(BOUND_MS);
        }
    }

    /** One real round trip, timed from request send to the parsed answer; a non-OK answer fails at once. */
    private static long timedRoundTrip(String url, String bearer, int index, String phase) {
        long start = System.nanoTime();
        Response answered = ConfigAdminApi.call("POST", url, bearer, SAMPLE);
        long elapsed = System.nanoTime() - start;
        assertThat(answered.status()).as("%s call #%d answers 200, body: %s", phase, index + 1, answered.body())
                .isEqualTo(200);
        assertThat(answered.json().path("status").asString())
                .as("%s call #%d: the transform answers OK, body: %s", phase, index + 1, answered.body())
                .isEqualTo("OK");
        assertThat(answered.json().path("output").path("customer_code").asString())
                .as("%s call #%d: the output is the mapped sample", phase, index + 1).isEqualTo("acme-1");
        return elapsed;
    }

    /** Nearest-rank percentile of an ascending list. */
    private static long percentile(List<Long> sorted, int p) {
        int rank = (int) Math.ceil(p / 100d * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000d;
    }

    // ------------------------------------------------------------------ seeding
    // ponytail: copied from T109MappingTestEndpointContainerTest (its helpers are private instance methods of a test
    // class); extract one shared mapping-seed fixture when a third suite needs them.

    private LaneCLifecycle.Draft draftWithApprovedSource(String base, Actors actor, String... labels)
            throws SQLException {
        String sourceSystem = LaneCIdentities.randomCode("src");
        String destination1 = LaneCIdentities.randomCode("tgt");
        String destination2 = LaneCIdentities.randomCode("tgt");
        LaneCLifecycle.createSystem(base, actor.author(), sourceSystem, "INBOUND");
        LaneCLifecycle.createSystem(base, actor.author(), destination1, "OUTBOUND");
        LaneCLifecycle.createSystem(base, actor.author(), destination2, "OUTBOUND");
        for (String label : labels) {
            seedSourceVersion(sourceSystem, label);
        }
        return new LaneCLifecycle.Draft(
                LaneCLifecycle.createFlow(base, actor.author(), sourceSystem,
                        List.of(destination1, destination2), "$.orderId"),
                sourceSystem, destination1, destination2);
    }

    private UUID saveMapping(String base, Actors actor, UUID route, LaneCLifecycle.Draft draft,
            String fieldMappings, String compiledExpression) throws SQLException {
        UUID source = seedSourceVersion(draft.sourceSystem(), "1.0");
        UUID target = seedTargetVersion(systemOfCode(draft.destination1()), "t038-" + draft.flowId());
        Response saved = ConfigAdminApi.call("PUT",
                base + ROUTES + "/" + route + "/mapping", actor.author(), mappingBody(
                        fieldMappings, compiledExpression, source, target));
        assertThat(saved.status()).as("the mapping saves, body: %s", saved.body()).isEqualTo(200);
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select id::text from public.mapping where route_id = ?::uuid")) {
            read.setString(1, route.toString());
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next()).as("the route's mapping row exists").isTrue();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    private static final tools.jackson.databind.json.JsonMapper BODY_JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private static String mappingBody(String fieldMappings, String compiledExpression, UUID sourceVersion,
            UUID targetVersion) {
        tools.jackson.databind.node.ObjectNode json = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        json.set("fieldMappings", BODY_JSON.readTree(fieldMappings));
        json.put("compiledExpression", compiledExpression);
        json.put("sourceContractVersionId", sourceVersion.toString());
        json.put("targetContractVersionId", targetVersion.toString());
        return BODY_JSON.writeValueAsString(json);
    }

    private UUID seedSourceVersion(String sourceSystem, String label) throws SQLException {
        UUID id = UUID.nameUUIDFromBytes(("mapping-source|" + sourceSystem + "|" + label).getBytes(
                StandardCharsets.UTF_8));
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select id::text from public.contract_version where id = ?::uuid")) {
            read.setString(1, id.toString());
            try (ResultSet rs = read.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString(1));
                }
            }
        }
        UUID contractId = UUID.nameUUIDFromBytes(("mapping-source-contract|" + sourceSystem).getBytes(
                StandardCharsets.UTF_8));
        UUID tenant = E3ConfigDatabase.tenantA().id();
        UUID system = systemOfCode(sourceSystem);
        try (Connection owner = E3ConfigDatabase.ownerConnection()) {
            try (PreparedStatement create = owner.prepareStatement(
                    "insert into public.contract (id, tenant_id, system_id, code, role, name)"
                            + " values (?::uuid, ?::uuid, ?::uuid, ?, 'SOURCE_REQUEST', ?)"
                            + " on conflict (tenant_id, id) do nothing")) {
                create.setString(1, contractId.toString());
                create.setString(2, tenant.toString());
                create.setString(3, system.toString());
                create.setString(4, sourceSystem);
                create.setString(5, "T-038 source contract of " + sourceSystem);
                create.executeUpdate();
            }
            try (PreparedStatement insert = owner.prepareStatement(
                    "insert into public.contract_version (id, tenant_id, contract_id, version_label, status,"
                            + " json_schema_draft_2020_12, author_id, approver_id)"
                            + " values (?::uuid, ?::uuid, ?::uuid, ?, 'APPROVED', '{\"type\":\"object\"}'::jsonb,"
                            + " ?::uuid, ?::uuid)")) {
                insert.setString(1, id.toString());
                insert.setString(2, tenant.toString());
                insert.setString(3, contractId.toString());
                insert.setString(4, label);
                insert.setString(5, UUID.randomUUID().toString());
                insert.setString(6, UUID.randomUUID().toString());
                assertThat(insert.executeUpdate()).as("the source version %s inserts", label).isEqualTo(1);
            }
        }
        return id;
    }

    private UUID seedTargetVersion(UUID systemId, String name) throws SQLException {
        UUID id = UUID.nameUUIDFromBytes(("mapping-target|" + name).getBytes(StandardCharsets.UTF_8));
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select id::text from public.contract_version where id = ?::uuid")) {
            read.setString(1, id.toString());
            try (ResultSet rs = read.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString(1));
                }
            }
            UUID contractId = UUID.nameUUIDFromBytes(("mapping-target-contract|" + name).getBytes(
                    StandardCharsets.UTF_8));
            try (PreparedStatement contract = owner.prepareStatement(
                    "insert into public.contract (id, tenant_id, system_id, code, role, name)"
                            + " select ?::uuid, s.tenant_id, s.id, ?, 'TARGET_REQUEST', ?"
                            + " from public.system s where s.id = ?::uuid"
                            + " on conflict (tenant_id, code) do nothing")) {
                contract.setString(1, contractId.toString());
                contract.setString(2, "E7B-" + name);
                contract.setString(3, "E7B-" + name);
                contract.setString(4, systemId.toString());
                contract.executeUpdate();
            }
            try (PreparedStatement version = owner.prepareStatement(
                    "insert into public.contract_version (id, tenant_id, contract_id, version_label, status,"
                            + " json_schema_draft_2020_12, author_id, approver_id)"
                            + " select ?::uuid, s.tenant_id, ?::uuid, '1.0', 'APPROVED', ?::jsonb, ?::uuid, ?::uuid"
                            + " from public.system s where s.id = ?::uuid")) {
                version.setString(1, id.toString());
                version.setString(2, contractId.toString());
                version.setString(3, TARGET_SCHEMA);
                version.setString(4, UUID.randomUUID().toString());
                version.setString(5, UUID.randomUUID().toString());
                version.setString(6, systemId.toString());
                version.executeUpdate();
            }
            return id;
        }
    }

    private UUID systemOfCode(String code) throws SQLException {
        try (Connection owner = E3ConfigDatabase.ownerConnection();
                PreparedStatement read = owner.prepareStatement(
                        "select id::text from public.system where code = ? order by id limit 1")) {
            read.setString(1, code);
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next()).as("the system %s exists", code).isTrue();
                return UUID.fromString(rs.getString(1));
            }
        }
    }
}
