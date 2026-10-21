package io.integrationplatform.config.e5.credential;

import static io.integrationplatform.config.e5.credential.E5CredentialFixture.MASK;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.call;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.createSystem;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.endpointOf;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.newUser;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.scalar;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.tenantA;
import static io.integrationplatform.config.e5.credential.E5CredentialFixture.tenantB;
import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.credential.api.CredentialAad;
import io.integrationplatform.config.e5.credential.E5CredentialFixture.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * E5-F1-S3 and E5-F1-S4 (T-103, T-185) - target credentials over the real HTTP API on PostgreSQL, as the restricted
 * login, through the real identity chain and role interceptor.
 *
 * <pre>
 * Scenario: Store a target credential encrypted
 *   Given a target system that needs a password or key
 *   When the tenant admin saves it
 *   Then it is stored envelope-encrypted with the key outside the database (ADR-007 Decision 7)
 *
 * &#64;negative &#64;security
 * Scenario: Secrets are write-only and never logged
 *   Given a saved secret
 *   When the system is read through the API or screen, or the logs of the whole action are searched
 *   Then the secret value appears nowhere (only a masked marker)
 *
 * Scenario Outline: Every type's credential fields are envelope-encrypted
 *   Given a &lt;type&gt; target with its credential fields
 *   When they are saved
 *   Then each field is its own target_credential row under ADR-007 Decision 7
 *   Examples: API | MQ_CSV   (and DB: the per-type naming includes it, rule 10)
 *
 * &#64;negative &#64;security
 * Scenario: Snapshots hold credential ids only
 *   Given a published interface version whose targets use credentials
 *   When its bundle snapshot, audit events and exports are read
 *   Then they contain credential ids and no ciphertext, wrapped key or value
 * </pre>
 * Plus: only a tenant admin writes (configurator, support, read-only: 403); a foreign endpoint is 404 and nothing is
 * written. "The key outside the database" is checked by opening the stored row with the run's in-memory K1 - the row
 * holds only the key's id.
 */
@Tag("container")
@SpringBootTest(classes = E5CredentialSuiteApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class TargetCredentialContainerTest {

    private static final String ENDPOINTS = "/api/v1/admin/target-endpoints/";

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        E5CredentialFixture.wire(registry);
        // T-124: a target's host is checked at save, so the suite's partner host must be on the allow-list
        registry.add("platform.config.destinations.allowed-hosts", () -> "partner.example,*.partner.example");
    }

    private UUID apiTarget(String admin) {
        return endpointOf(createSystem(port, admin, "OUTBOUND", "API", "{\"baseUrl\":\"https://partner.example/\"}"));
    }

    private static String secret(String label) {
        return label + "-" + UUID.randomUUID();
    }

    private static String credentialsPath(UUID endpoint) {
        return ENDPOINTS + endpoint + "/credentials";
    }

    /** One stored row as the owner sees it: field|target_type|kek_id|id. */
    private static List<String> rows(UUID endpoint) {
        String all = scalar("SELECT string_agg(field || '|' || target_type || '|' || kek_id || '|' || id::text, ','"
                + " ORDER BY field) FROM public.target_credential WHERE target_endpoint_id = ?::uuid",
                endpoint.toString());
        return all == null ? List.of() : List.of(all.split(","));
    }

    @Test
    @DisplayName("Scenario: Store a target credential encrypted - one row per field, sealed, the key outside the database")
    void storeATargetCredentialEncrypted(CapturedOutput output) throws Exception {
        String admin = tenantA().adminSubject();
        UUID endpoint = apiTarget(admin);
        String password = secret("basic");
        String apiKey = secret("apikey");

        Response saved = call(port, "PUT", credentialsPath(endpoint), admin,
                "{\"fields\":{\"basicPassword\":\"" + password + "\",\"apiKey\":\"" + apiKey + "\"}}");

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(saved.body()).contains(endpoint.toString()).contains(MASK).contains("basicPassword")
                .contains("apiKey").doesNotContain(password).doesNotContain(apiKey);
        assertThat(rows(endpoint)).hasSize(2).allSatisfy(row -> assertThat(row).contains("|API|K1|"));

        try (Connection owner = E5CredentialFixture.ownerConnection(); PreparedStatement read = owner.prepareStatement(
                "SELECT id, tenant_id, target_endpoint_id, field, kek_id, wrapped_key, ciphertext"
                        + " FROM public.target_credential WHERE target_endpoint_id = ?::uuid AND field = 'BASIC_PASSWORD'")) {
            read.setString(1, endpoint.toString());
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next()).isTrue();
                UUID id = rs.getObject(1, UUID.class);
                UUID tenant = rs.getObject(2, UUID.class);
                byte[] wrapped = rs.getBytes(6);
                byte[] ciphertext = rs.getBytes(7);
                assertThat(tenant).isEqualTo(tenantA().id());
                assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain(password);
                byte[] dataKey = open(E5CredentialFixture.k1().getEncoded(), wrapped,
                        CredentialAad.wrappedKey(tenant, id, "K1"));
                byte[] value = open(dataKey, ciphertext, CredentialAad.value(tenant, endpoint, "BASIC_PASSWORD"));
                assertThat(new String(value, StandardCharsets.UTF_8))
                        .as("opens only with the key held outside the database").isEqualTo(password);
            }
        }
        assertThat(output).doesNotContain(password).doesNotContain(apiKey);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario: Secrets are write-only and never logged - read answers a masked marker; the logs never hold it")
    void secretsAreWriteOnlyAndNeverLogged(CapturedOutput output) {
        String admin = tenantA().adminSubject();
        UUID endpoint = apiTarget(admin);
        String token = secret("bearer");
        assertThat(call(port, "PUT", credentialsPath(endpoint), admin,
                "{\"fields\":{\"bearerToken\":\"" + token + "\"}}").status()).isEqualTo(200);

        Response read = call(port, "GET", credentialsPath(endpoint), admin, null);
        Response readByConfigurator = call(port, "GET", credentialsPath(endpoint),
                newUser(tenantA().id(), "CONFIGURATOR"), null);

        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json().path("fields")).singleElement().satisfies(field -> {
            assertThat(field.path("field").asString()).isEqualTo("bearerToken");
            assertThat(field.path("value").asString()).isEqualTo(MASK);
            assertThat(field.path("credentialId").asString()).isNotBlank();
        });
        assertThat(read.body()).doesNotContain(token);
        assertThat(readByConfigurator.status()).as("reading the masked view is configuration reading").isEqualTo(200);
        assertThat(readByConfigurator.body()).doesNotContain(token);
        assertThat(output).as("the logs of the whole action").doesNotContain(token)
                .contains("event=credential.target_saved");
    }

    @Test
    @DisplayName("Scenario Outline: every type's fields - API, MQ_CSV and DB - each field its own target_credential row")
    void everyTypesFieldsAreOwnRows() {
        String admin = tenantA().adminSubject();
        UUID mq = endpointOf(createSystem(port, admin, "OUTBOUND", "MQ",
                "{\"host\":\"mq.partner.example\",\"port\":1414,\"queue\":\"ORDERS\"}"));
        UUID db = endpointOf(createSystem(port, admin, "OUTBOUND", "DB",
                "{\"host\":\"db.partner.example\",\"port\":5432,\"database\":\"orders\",\"user\":\"ipass_orders\"}"));

        Response mqSaved = call(port, "PUT", credentialsPath(mq), admin, "{\"fields\":{"
                + "\"queueManagerPassword\":\"" + secret("qm") + "\",\"tlsKeystorePassword\":\"" + secret("ks")
                + "\",\"tlsTruststorePassword\":\"" + secret("ts") + "\"}}");
        Response dbSaved = call(port, "PUT", credentialsPath(db), admin,
                "{\"fields\":{\"accountPassword\":\"" + secret("db") + "\"}}");

        assertThat(mqSaved.status()).as(mqSaved.body()).isEqualTo(200);
        assertThat(dbSaved.status()).as(dbSaved.body()).isEqualTo(200);
        assertThat(rows(mq)).extracting(row -> row.substring(0, row.indexOf("|K1|")))
                .containsExactly("QUEUE_MANAGER_PASSWORD|MQ_CSV", "TLS_KEYSTORE_PASSWORD|MQ_CSV",
                        "TLS_TRUSTSTORE_PASSWORD|MQ_CSV");
        assertThat(rows(db)).singleElement().asString().startsWith("DB_ACCOUNT_PASSWORD|DB|K1|");
    }

    @Test
    @DisplayName("Rule 2: saving a field again keeps its row id and seals it anew (new data key, new nonce)")
    void reSavingReseals() {
        String admin = tenantA().adminSubject();
        UUID endpoint = apiTarget(admin);
        call(port, "PUT", credentialsPath(endpoint), admin, "{\"fields\":{\"apiKey\":\"" + secret("one") + "\"}}");
        String before = scalar("SELECT id::text || '|' || encode(wrapped_key, 'hex') || '|' || encode(ciphertext,"
                + " 'hex') FROM public.target_credential WHERE target_endpoint_id = ?::uuid", endpoint.toString());

        call(port, "PUT", credentialsPath(endpoint), admin, "{\"fields\":{\"apiKey\":\"" + secret("two") + "\"}}");
        String after = scalar("SELECT id::text || '|' || encode(wrapped_key, 'hex') || '|' || encode(ciphertext,"
                + " 'hex') FROM public.target_credential WHERE target_endpoint_id = ?::uuid", endpoint.toString());

        assertThat(after.split("\\|")[0]).isEqualTo(before.split("\\|")[0]);
        assertThat(after.split("\\|")[1]).isNotEqualTo(before.split("\\|")[1]);
        assertThat(after.split("\\|")[2]).isNotEqualTo(before.split("\\|")[2]);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario Outline: only a tenant admin writes - configurator, support, read-only get 403, nothing written")
    void onlyATenantAdminWrites() {
        UUID endpoint = apiTarget(tenantA().adminSubject());
        for (String role : List.of("CONFIGURATOR", "SUPPORT", "READ_ONLY")) {
            Response refused = call(port, "PUT", credentialsPath(endpoint), newUser(tenantA().id(), role),
                    "{\"fields\":{\"apiKey\":\"" + secret(role) + "\"}}");
            assertThat(refused.status()).as(role + ": " + refused.body()).isEqualTo(403);
        }
        assertThat(rows(endpoint)).isEmpty();
        assertThat(call(port, "GET", credentialsPath(endpoint), newUser(tenantA().id(), "SUPPORT"), null).status())
                .as("support reads no configuration").isEqualTo(403);
    }

    @Test
    @Tag("negative")
    @Tag("tenant")
    @DisplayName("@tenant: another tenant's endpoint is 404 for read and write, and nothing is written there")
    void anotherTenantsEndpointIs404() {
        UUID foreign = apiTarget(tenantB().adminSubject());
        String admin = tenantA().adminSubject();

        assertThat(call(port, "PUT", credentialsPath(foreign), admin,
                "{\"fields\":{\"apiKey\":\"" + secret("x") + "\"}}").status()).isEqualTo(404);
        assertThat(call(port, "GET", credentialsPath(foreign), admin, null).status()).isEqualTo(404);
        assertThat(call(port, "GET", ENDPOINTS + "not-a-uuid/credentials", admin, null).status()).isEqualTo(404);
        assertThat(rows(foreign)).isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("An untyped (E3) system's endpoint has no credential fields: 409")
    void anUntypedSystemIs409() {
        String admin = tenantA().adminSubject();
        UUID endpoint = endpointOf(createSystem(port, admin, "OUTBOUND", null, null));
        assertThat(call(port, "PUT", credentialsPath(endpoint), admin,
                "{\"fields\":{\"apiKey\":\"" + secret("x") + "\"}}").status()).isEqualTo(409);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario: Snapshots hold credential ids only - the published bundle and the audit events hold no secret")
    void snapshotsHoldIdsOnly(CapturedOutput output) throws Exception {
        String author = tenantA().adminSubject();
        String approver = newUser(tenantA().id(), "TENANT_ADMIN");
        UUID source = createSystem(port, author, "INBOUND", null, null);
        UUID target = createSystem(port, author, "OUTBOUND", "API", "{\"baseUrl\":\"https://partner.example/\"}");
        UUID endpoint = endpointOf(target);
        String password = secret("published");
        assertThat(call(port, "PUT", credentialsPath(endpoint), author,
                "{\"fields\":{\"basicPassword\":\"" + password + "\"}}").status()).isEqualTo(200);
        String credentialId = scalar("SELECT id::text FROM public.target_credential WHERE target_endpoint_id ="
                + " ?::uuid", endpoint.toString());
        String sourceCode = scalar("SELECT code FROM public.system WHERE id = ?::uuid", source.toString());
        String targetCode = scalar("SELECT code FROM public.system WHERE id = ?::uuid", target.toString());

        Response flow = call(port, "POST", "/api/v1/admin/flows", author, "{\"sourceSystem\":\"" + sourceCode
                + "\",\"destinations\":[\"" + targetCode + "\"],\"businessKeyRule\":\"$.orderId\"}");
        assertThat(flow.status()).as(flow.body()).isEqualTo(201);
        String flowId = flow.json().path("id").asString();
        // T-190: the mapping binds to contract versions and the bundle is re-captured - the publish-time pin
        // check refuses an unbound route (ADR-011 rule 6), so the fixture binds before the lifecycle walks.
        String routeId = scalar("SELECT id::text FROM public.route WHERE interface_id = ?::uuid", flowId);
        String sourceVersion = scalar("SELECT source_contract_version_id::text FROM public.interface_version"
                + " WHERE interface_id = ?::uuid ORDER BY version DESC LIMIT 1", flowId);
        String endpointSystem = scalar("SELECT te.system_id::text FROM public.route r"
                + " JOIN public.target_endpoint te ON te.id = r.target_endpoint_id WHERE r.id = ?::uuid", routeId);
        UUID targetContractId = UUID.nameUUIDFromBytes(("mapping-target-contract|" + endpointSystem)
                .getBytes(StandardCharsets.UTF_8));
        UUID targetVersionId = UUID.nameUUIDFromBytes(("mapping-target-version|" + endpointSystem)
                .getBytes(StandardCharsets.UTF_8));
        E5CredentialFixture.execute("INSERT INTO public.contract (id, tenant_id, system_id, code, role, name)"
                + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'TARGET_REQUEST', ?)"
                + " ON CONFLICT (tenant_id, id) DO NOTHING",
                targetContractId.toString(), tenantA().id().toString(), endpointSystem,
                "map-target-" + targetContractId.toString().substring(0, 8), "mapping pin target contract");
        E5CredentialFixture.execute("INSERT INTO public.contract_version (id, tenant_id, contract_id,"
                + " version_label, status, json_schema_draft_2020_12, author_id)"
                + " VALUES (?::uuid, ?::uuid, ?::uuid, '1.0', 'APPROVED', ?::jsonb, ?::uuid)"
                + " ON CONFLICT (tenant_id, id) DO NOTHING",
                targetVersionId.toString(), tenantA().id().toString(), targetContractId.toString(),
                "{\"type\":\"object\",\"properties\":{\"customer_code\":{\"type\":\"string\"}}}",
                tenantA().id().toString());
        assertThat(call(port, "PUT", "/api/v1/admin/routes/" + routeId + "/mapping", author,
                "{\"fieldMappings\":[],\"compiledExpression\":\"$\",\"sourceContractVersionId\":\""
                        + sourceVersion + "\",\"targetContractVersionId\":\"" + targetVersionId + "\"}")
                .status()).isEqualTo(200);
        // T-037: publish is gated on a passing sample run of the pinned mapping content - the author runs it.
        String mappingId = scalar("SELECT id::text FROM public.mapping WHERE route_id = ?::uuid", routeId);
        Response sampleRun = call(port, "POST", "/api/v1/admin/mappings/" + mappingId + "/test", author,
                "{\"sample\":{\"customerId\":\"c-1\"}}");
        assertThat(sampleRun.status()).as(sampleRun.body()).isEqualTo(200);
        assertThat(sampleRun.json().path("status").asString()).as(sampleRun.body()).isEqualTo("OK");
        assertThat(call(port, "PUT", "/api/v1/admin/flows/" + flowId, author, "{\"sourceSystem\":\"" + sourceCode
                + "\",\"destinations\":[\"" + targetCode + "\"],\"businessKeyRule\":\"$.orderId\"}")
                .status()).isEqualTo(200);
        assertThat(call(port, "POST", "/api/v1/admin/flows/" + flowId + "/submit", author,
                "{\"changeNote\":\"targets use credentials\"}").status()).isEqualTo(200);
        assertThat(call(port, "POST", "/api/v1/admin/flows/" + flowId + "/approve", approver, "{}").status())
                .isEqualTo(200);
        assertThat(call(port, "POST", "/api/v1/admin/flows/" + flowId + "/publish", approver, "{}").status())
                .isEqualTo(200);

        String snapshot = scalar("SELECT snapshot::text FROM public.interface_version WHERE interface_id = ?::uuid"
                + " AND status = 'PUBLISHED'", flowId);
        assertThat(snapshot).as("the published bundle names the target by id").contains(endpoint.toString());
        List<String> forbidden = new ArrayList<>(List.of(password));
        try (Connection owner = E5CredentialFixture.ownerConnection(); PreparedStatement read = owner.prepareStatement(
                "SELECT wrapped_key, ciphertext FROM public.target_credential WHERE id = ?::uuid")) {
            read.setString(1, credentialId);
            try (ResultSet rs = read.executeQuery()) {
                rs.next();
                for (int column = 1; column <= 2; column++) {
                    byte[] bytes = rs.getBytes(column);
                    forbidden.add(Base64.getEncoder().encodeToString(bytes));
                    forbidden.add(HexFormat.of().formatHex(bytes));
                }
            }
        }
        for (String value : forbidden) {
            assertThat(snapshot).as("no ciphertext, wrapped key or value in the snapshot").doesNotContain(value);
            assertThat(output.getAll()).as("no ciphertext, wrapped key or value in any log line")
                    .doesNotContain(value);
        }
        assertThat(snapshot).doesNotContain("kek").doesNotContain("wrapped").doesNotContain("ciphertext");
        assertThat(output).as("the audit event names the credential by id").contains(credentialId);
    }

    private static byte[] open(byte[] key, byte[] sealed, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, sealed, 0, 12));
        cipher.updateAAD(aad);
        return cipher.doFinal(sealed, 12, sealed.length - 12);
    }
}
