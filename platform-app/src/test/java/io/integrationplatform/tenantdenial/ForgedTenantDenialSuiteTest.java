package io.integrationplatform.tenantdenial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import io.integrationplatform.config.flow.application.FlowBundle;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.MappingRow;
import io.integrationplatform.config.internal.persistence.JdbcFlowRepository.RouteRow;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import io.integrationplatform.tenantdenial.EndpointMatrix.Config;
import io.integrationplatform.tenantdenial.EndpointMatrix.Entry;
import io.integrationplatform.tenantdenial.EndpointMatrix.Seed;
import io.integrationplatform.tenantdenial.EndpointMatrix.SeededTenant;
import io.integrationplatform.tenantdenial.EndpointMatrix.WriteProbe;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * E1-F1-S1 "A forged tenant changes nothing" - the forged-tenant denial suite, architecture.md section 5 control T1,
 * the E1 release gate (re-run by E4 and E15). Traces E1-C1, E1-C2, E1-C5.
 *
 * <p>The whole application runs (every module's endpoints) against PostgreSQL with the restricted app login. Every
 * {@code /api/**} endpoint has a matrix entry ({@link EndpointMatrix}); each gets the forgeries that fit it. A user of
 * tenant A signs in with a real signed bearer token; tenant B is seeded beside it and is what every forgery names.
 *
 * <pre>
 * Scenario: A tenant-A user sees only tenant-A rows
 *   Given rows for tenant A and tenant B
 *   And a signed-in user bound to tenant A
 *   When the user calls any read endpoint
 *   Then only tenant-A rows are returned
 *
 * &#64;negative &#64;tenant
 * Scenario Outline: A forged tenant is ignored
 *   Given a signed-in user bound to tenant A
 *   When the request carries &lt;forgery&gt; naming tenant B
 *   Then every response contains only tenant-A rows, or 404 for a tenant-B id
 *   And the forgery is recorded as a denied attempt
 *   Examples:
 *     | forgery                         |
 *     | a "Tenant" header               |
 *     | a tenantId field in the body    |
 *     | a tenantId query parameter      |
 *     | a tenant-B record id in the path |
 *
 * &#64;negative &#64;security
 * Scenario: No identity, no tenant
 *   Given an unauthenticated request that carries a tenant field
 *   When it is handled
 *   Then it is rejected with 401 and no tenant data is read
 * </pre>
 *
 * <p>Container group (R03), one Docker suite at a time (R06). It moves to platform-e2e when that module exists.
 *
 * <p>The lifecycle endpoints of the config admin API (T-015/T-019/T-021) write rows, so the matrix carries a
 * row-state probe per write entry ({@link WriteProbe}): the suite re-seeds both tenants' config chains before
 * every test (fixed ids, guard-trigger-safe inserts) and after every call checks that tenant A's rows stand where
 * the scenario says (a legitimate write lands; an ignored forgery still lands; a tenant-B id and a 401 write
 * nothing) and that tenant B's whole config chain is byte-identical to its seeded fingerprint.
 */
@Tag("container")
@Tag("tenant")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestProbeController.class, ProbeRowReader.class})
@DisplayName("E1-F1-S1 A forged tenant changes nothing (release gate)")
class ForgedTenantDenialSuiteTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final TestTokens TOKENS = new TestTokens();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static RestrictedLogin login;
    private static Seed seed;
    private static String fingerprintBeforeTest;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    /** The suite's tokens are verified against the in-memory key; the configured issuer is never contacted. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SuiteTokenDecoder {

        @Bean
        @Primary
        JwtDecoder tenantDenialSuiteJwtDecoder() {
            return TOKENS.decoder();
        }
    }

    @MockitoSpyBean
    private TenantDenialRecorder recorder;

    @Autowired
    private ProbeRowReader probe;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @LocalServerPort
    private int port;

    @BeforeEach
    void seedOnceThenReset() throws Exception {
        if (seed == null) {
            seed = seedTenantsAandB();
        }
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            writeConfigChain(owner, seed.a());
            writeConfigChain(owner, seed.b());
            fingerprintBeforeTest = configFingerprint(owner, seed.b().id());
        }
        clearInvocations(recorder);
        probe.resetReads();
    }

    static Stream<Entry> endpoints() {
        return EndpointMatrix.REGISTRY.stream();
    }

    static Stream<Object[]> forgeries() {
        return EndpointMatrix.forgeries();
    }

    static Stream<Object[]> tenantFields() {
        return EndpointMatrix.tenantFields();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("Scenario: A tenant-A user sees only tenant-A rows")
    void aTenantAUserSeesOnlyTenantARows(Entry entry) throws Exception {
        HttpResponse<String> response = send(entry, null, signedInAsTenantA());

        assertThat(response.statusCode()).as(entry + " status, body: " + response.body())
                .isEqualTo(entry.expectedStatus());
        assertOnlyTenantAData(entry, response);
        assertRowState(entry, "the legitimate call wrote tenant A's own rows", false);
        assertTenantBUnchanged();
        verify(recorder, never()).record(any());
    }

    @ParameterizedTest(name = "{1} on {0}")
    @MethodSource("forgeries")
    @Tag("negative")
    @DisplayName("Scenario Outline: A forged tenant is ignored")
    void aForgedTenantIsIgnored(Entry entry, Forgery forgery) throws Exception {
        HttpResponse<String> response = send(entry, forgery, signedInAsTenantA());

        if (forgery == Forgery.PATH_ID) {
            assertThat(response.statusCode()).as(entry + " with a tenant-B id, body: " + response.body()).isEqualTo(404);
            assertNoMarkerOf(seed.b(), response);
            assertRowState(entry, "a tenant-B id reached no tenant-A row", true);
        } else {
            assertThat(response.statusCode()).as(entry + " with " + forgery + ", body: " + response.body())
                    .isEqualTo(entry.expectedStatus());
            assertOnlyTenantAData(entry, response);
            assertRowState(entry, "the ignored forgery still wrote tenant A's own rows only", false);
        }
        assertTenantBUnchanged();
        DeniedTenantAttempt attempt = theOneRecordedAttempt();
        assertThat(attempt.channel()).isEqualTo(forgery.channel());
        assertThat(attempt.boundTenantId()).isEqualTo(seed.a().id());
        assertThat(attempt.method()).isEqualTo(entry.method());
        assertThat(attempt.path()).isEqualTo(response.request().uri().getRawPath());
    }

    @ParameterizedTest(name = "{1} on {0}")
    @MethodSource("tenantFields")
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario: No identity, no tenant")
    void noIdentityNoTenant(Entry entry, Forgery tenantField) throws Exception {
        HttpResponse<String> response = send(entry, tenantField, null);

        assertThat(response.statusCode()).as(entry + " without identity, body: " + response.body()).isEqualTo(401);
        assertNoMarkerOf(seed.a(), response);
        assertNoMarkerOf(seed.b(), response);
        assertThat(probe.reads()).as("tenant data reads").isZero();
        assertRowState(entry, "no identity, no config write", true);
        DeniedTenantAttempt attempt = theOneRecordedAttempt();
        assertThat(attempt.channel()).isEqualTo(DeniedTenantAttempt.Channel.NO_IDENTITY);
        assertThat(attempt.boundTenantId()).isNull();
        assertThat(attempt.method()).isEqualTo(entry.method());
        assertThat(attempt.path()).isEqualTo(response.request().uri().getRawPath());
    }

    @Test
    @DisplayName("Coverage guard: every /api/** endpoint of the running application has a matrix entry")
    void everyApiEndpointHasAMatrixEntry() {
        assertThat(EndpointMatrix.coverageProblems(handlerMapping.getHandlerMethods(), EndpointMatrix.REGISTRY))
                .isEmpty();
    }

    // --- requests -----------------------------------------------------------------------------------------------

    private String signedInAsTenantA() {
        return TOKENS.accessToken(seed.a().subject());
    }

    /** Builds the entry's tenant-A call, then applies the forgery naming tenant B (null = no forgery). */
    private HttpResponse<String> send(Entry entry, Forgery forgery, String bearer) throws Exception {
        String foreignTenant = seed.b().id().toString();
        String path = forgery == Forgery.PATH_ID ? entry.foreignIdPath().apply(seed) : entry.pathForA().apply(seed);
        if (forgery == Forgery.QUERY_PARAMETER) {
            path += (path.contains("?") ? "&" : "?") + Forgery.TENANT_FIELD + "="
                    + URLEncoder.encode(foreignTenant, StandardCharsets.UTF_8);
        }
        String body = entry.acceptsBody() ? entry.bodyForA().apply(seed) : null;
        if (forgery == Forgery.BODY_FIELD) {
            ObjectNode forged = (ObjectNode) JSON.readTree(body);
            forged.put(Forgery.TENANT_FIELD, foreignTenant);
            body = JSON.writeValueAsString(forged);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (forgery == Forgery.HEADER) {
            request.header(Forgery.TENANT_HEADER, foreignTenant);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(entry.method(), body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    // --- assertions ---------------------------------------------------------------------------------------------

    private void assertOnlyTenantAData(Entry entry, HttpResponse<String> response) {
        assertNoMarkerOf(seed.b(), response);
        List<String> expectedData = entry.tenantAData().apply(seed);
        if (!expectedData.isEmpty()) {
            assertThat(response.body()).as(entry + " answers with the tenant-A data").contains(expectedData);
        }
        JsonNode json = JSON.readTree(response.body());
        for (JsonNode tenantId : json.findValues(Forgery.TENANT_FIELD)) {
            assertThat(tenantId.asString()).as(entry + " tenantId in the answer").isEqualTo(seed.a().id().toString());
        }
    }

    private static void assertNoMarkerOf(SeededTenant tenant, HttpResponse<String> response) {
        for (String marker : tenant.markers()) {
            assertThat(response.body()).as("answer leaks a value of tenant " + tenant.code()).doesNotContain(marker);
        }
    }

    private DeniedTenantAttempt theOneRecordedAttempt() {
        ArgumentCaptor<DeniedTenantAttempt> attempt = ArgumentCaptor.forClass(DeniedTenantAttempt.class);
        verify(recorder, times(1)).record(attempt.capture());
        return attempt.getValue();
    }

    /** The write entry's row state, read as the owner: {@code expectSeeded} picks seeded vs after-the-write. */
    private static void assertRowState(Entry entry, String label, boolean expectSeeded) throws Exception {
        WriteProbe chain = entry.write();
        if (chain == null) {
            return;
        }
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES);
             PreparedStatement state = owner.prepareStatement(chain.stateSql().apply(seed));
             ResultSet rs = state.executeQuery()) {
            assertThat(rs.next()).as(entry.key() + " answers a state row").isTrue();
            assertThat(rs.getString(1)).as(entry.key() + " - " + label)
                    .isEqualTo(expectSeeded ? chain.seeded() : chain.afterWrite());
        }
    }

    /** Tenant B's config chain must be byte-identical (ids included) to what the per-test reset wrote. */
    private static void assertTenantBUnchanged() throws Exception {
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            assertThat(configFingerprint(owner, seed.b().id()))
                    .as("tenant B's config chain unchanged by tenant A's traffic")
                    .isEqualTo(fingerprintBeforeTest);
        }
    }

    /** One md5 over every config table's row ids and count for one tenant (the owner bypasses RLS). */
    private static String configFingerprint(Connection owner, UUID tenantId) throws Exception {
        try (PreparedStatement fingerprint = owner.prepareStatement(CONFIG_FINGERPRINT)) {
            for (int position = 1; position <= 9; position++) {
                fingerprint.setString(position, tenantId.toString());
            }
            try (ResultSet rs = fingerprint.executeQuery()) {
                assertThat(rs.next()).as("the fingerprint always answers").isTrue();
                return rs.getString(1);
            }
        }
    }

    private static final String CONFIG_FINGERPRINT = "SELECT md5(string_agg(fact, ';' ORDER BY fact)) FROM ("
            + " SELECT 'config_version_refusal:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '') AS fact"
            + "   FROM public.config_version_refusal WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'contract:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM public.contract WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'contract_version:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '')"
            + "   FROM public.contract_version WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'interface:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM public.interface WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'interface_version:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '')"
            + "   FROM public.interface_version WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'mapping:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM public.mapping WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'route:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM public.route WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'system:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM public.system WHERE tenant_id = ?::uuid"
            + " UNION ALL SELECT 'target_endpoint:' || count(*) || '@'"
            + " || coalesce(string_agg(id::text, ',' ORDER BY id), '')"
            + "   FROM public.target_endpoint WHERE tenant_id = ?::uuid"
            + ") facts";

    // --- seed (as the Flyway owner; the app login never writes the directory) ------------------------------------

    private static Seed seedTenantsAandB() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/fixture/lane-d")
                .table("flyway_fixture_lane_d_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);

        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            SeededTenant a = seedTenant(owner, "alpha");
            SeededTenant b = seedTenant(owner, "bravo");
            try (PreparedStatement count = owner.prepareStatement("SELECT count(*) FROM probe_row WHERE tenant_id = ?::uuid")) {
                count.setString(1, b.id().toString());
                try (ResultSet rs = count.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("tenant-B rows exist, so 'only tenant A' is not vacuous").isEqualTo(2);
                }
            }
            return new Seed(a, b);
        }
    }

    private static SeededTenant seedTenant(Connection owner, String word) throws Exception {
        UUID tenantId = UUID.randomUUID();
        String code = "tnt-" + word + "-" + tenantId.toString().substring(0, 8);
        UUID userId = UUID.randomUUID();
        String subject = "sub-" + word + "-" + UUID.randomUUID();
        try (PreparedStatement tenant = owner.prepareStatement(
                "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')")) {
            tenant.setString(1, tenantId.toString());
            tenant.setString(2, code);
            tenant.executeUpdate();
        }
        try (PreparedStatement user = owner.prepareStatement(
                "INSERT INTO app_user (id, tenant_id, issuer, subject, status) VALUES (?::uuid, ?::uuid, ?, ?, 'ACTIVE')")) {
            user.setString(1, userId.toString());
            user.setString(2, tenantId.toString());
            user.setString(3, PlatformTestWiring.OIDC_ISSUER.toString());
            user.setString(4, subject);
            user.executeUpdate();
        }
        // E4 T-099 seeding: roles are role_grant rows; the matrix user drives every admin operation (tenant admin)
        try (PreparedStatement grant = owner.prepareStatement(
                "INSERT INTO role_grant (tenant_id, user_id, role) VALUES (?::uuid, ?::uuid, 'TENANT_ADMIN')")) {
            grant.setString(1, tenantId.toString());
            grant.setString(2, userId.toString());
            grant.executeUpdate();
        }
        List<UUID> rowIds = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            UUID rowId = UUID.randomUUID();
            String label = word + "-" + i;
            try (PreparedStatement row = owner.prepareStatement(
                    "INSERT INTO probe_row (id, tenant_id, label) VALUES (?::uuid, ?::uuid, ?)")) {
                row.setString(1, rowId.toString());
                row.setString(2, tenantId.toString());
                row.setString(3, label);
                row.executeUpdate();
            }
            rowIds.add(rowId);
            labels.add(label);
        }
        Config config = new Config(word,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        return new SeededTenant(tenantId, code, userId, subject, List.copyOf(rowIds), List.copyOf(labels), config);
    }

    /**
     * Rewrites one tenant's config chain to exactly the seeded rows: every lifecycle endpoint can run per test
     * without order effects. The delete/insert pair is guard-trigger-safe (the V2_2_4 trigger fires on UPDATE
     * only) and satisfies every composite FK inside the tenant, so the chain stands where the matrix expects it:
     * two systems and an endpoint, the source contract with its 1.0 draft, and six flows - plain, draft (PUT),
     * submittable, approvable (authored by a different user), publishable (APPROVED snapshot of one route and
     * one mapping) and immutable (the version-edit refusal target).
     */
    private static void writeConfigChain(Connection owner, SeededTenant tenant) throws Exception {
        for (String table : List.of("config_version_refusal", "interface_version", "mapping", "route",
                "contract_version", "contract", "target_endpoint", "interface", "system")) {
            execute(owner, "DELETE FROM public." + table + " WHERE tenant_id = ?::uuid", tenant.id());
        }
        Config c = tenant.config();
        execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction)"
                        + " VALUES (?::uuid, ?::uuid, ?, ?, 'SOURCE')",
                c.srcSystemId(), tenant.id(), c.srcCode(), c.srcCode() + " source");
        execute(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction)"
                        + " VALUES (?::uuid, ?::uuid, ?, ?, 'TARGET')",
                c.dstSystemId(), tenant.id(), c.dstCode(), c.dstCode() + " target");
        execute(owner, "INSERT INTO public.target_endpoint (id, tenant_id, system_id, code, name, base_url,"
                        + " http_method, auth_method, timeout_ms)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'https://endpoint.invalid/', 'POST',"
                        + " 'NO_AUTH', 5000)",
                c.dstEndpointId(), tenant.id(), c.dstSystemId(), c.dstCode() + "-default", c.dstCode() + " endpoint");
        execute(owner, "INSERT INTO public.contract (id, tenant_id, system_id, code, role, name)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'SOURCE_REQUEST', ?)",
                c.contractId(), tenant.id(), c.srcSystemId(), c.srcCode(), c.srcCode() + " contract");
        execute(owner, "INSERT INTO public.contract_version (id, tenant_id, contract_id, version_label, status,"
                        + " json_schema_draft_2020_12, author_id)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, '1.0', 'DRAFT', '{\"type\":\"object\"}'::jsonb,"
                        + " ?::uuid)",
                c.sourceContractVersionId(), tenant.id(), c.contractId(), tenant.userId());
        insertFlow(owner, tenant, c, "main", c.flowMainId(), "DRAFT", null, tenant.userId(), null);
        insertFlow(owner, tenant, c, "draft", c.flowDraftId(), "DRAFT", null, tenant.userId(), null);
        insertFlow(owner, tenant, c, "submit", c.flowSubmitId(), "DRAFT", null, tenant.userId(), null);
        insertFlow(owner, tenant, c, "approve", c.flowApproveId(), "PENDING_APPROVAL", null, c.otherAuthorId(), null);
        insertFlow(owner, tenant, c, "publish", c.flowPublishId(), "APPROVED", publishSnapshot(c),
                c.otherAuthorId(), tenant.userId());
        insertFlow(owner, tenant, c, "immutable", c.flowImmutableId(), "DRAFT", null, tenant.userId(), null);
    }

    /** The only version id the matrix names outside a status query is the immutable flow's (the refusal target). */
    private static void insertFlow(Connection owner, SeededTenant tenant, Config c, String kind, UUID flowId,
            String status, String snapshot, UUID authorId, UUID approverId) throws Exception {
        execute(owner, "INSERT INTO public.interface (id, tenant_id, code, name, ordering, current_version)"
                        + " VALUES (?::uuid, ?::uuid, ?, ?, 'PER_KEY', 1)",
                flowId, tenant.id(), c.flowCode(kind), c.flowCode(kind) + " " + kind);
        // The lifecycle timestamps are SQL literals, not parameters: a varchar-bound "now()" is not assignable
        // to a timestamptz column, and NULL keeps the DRAFT row's shape honest (nothing was submitted yet).
        String submitted = status.equals("PENDING_APPROVAL") || status.equals("APPROVED") ? "now()" : "NULL";
        String decided = status.equals("APPROVED") ? "now()" : "NULL";
        execute(owner, "INSERT INTO public.interface_version (id, tenant_id, interface_id, version, status,"
                        + " business_key_rule, snapshot, source_contract_version_id, author_id, approver_id,"
                        + " submitted_at, decided_at)"
                        + " VALUES (?::uuid, ?::uuid, ?::uuid, 1, ?, '$.orderId', ?::jsonb, ?::uuid, ?::uuid,"
                        + " ?::uuid, " + submitted + ", " + decided + ")",
                kind.equals("immutable") ? c.immutableVersionId() : UUID.randomUUID(), tenant.id(), flowId, status,
                snapshot == null ? "{\"interface\":{},\"routes\":[],\"mappings\":[]}" : snapshot,
                c.sourceContractVersionId(), authorId, approverId);
    }

    /** The APPROVED version's bundle, in the one serialization the publish materializes (T-021). */
    private static String publishSnapshot(Config c) {
        return FlowBundle.serialize(c.flowCode("publish"), c.flowCode("publish") + " publish", "PER_KEY",
                "$.orderId",
                List.of(new RouteRow(c.pubRouteId().toString(), 1, null, false, "ACTIVE",
                        c.dstEndpointId().toString())),
                List.of(new MappingRow(c.pubMappingId().toString(), c.pubRouteId().toString(), "[]", "$")));
    }

    private static void execute(Connection owner, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int position = 0; position < values.length; position++) {
                Object value = values[position];
                if (value == null) {
                    statement.setNull(position + 1, Types.OTHER);
                } else {
                    statement.setObject(position + 1, value instanceof UUID id ? id.toString() : value);
                }
            }
            statement.executeUpdate();
        }
    }
}
