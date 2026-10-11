package io.integrationplatform.ingestvalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.PlatformTestWiring.RestrictedLogin;
import io.integrationplatform.TestImages;
import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.ingest.validation.ContractSchemaCheck;
import io.integrationplatform.ingest.validation.PayloadValidationService;
import io.integrationplatform.ingest.validation.RejectionEnvelope;
import io.integrationplatform.ingest.validation.Violation;
import io.integrationplatform.ingest.validation.Violation.Source;
import io.integrationplatform.tenancy.internal.principal.TenantBoundAuthentication;
import io.integrationplatform.tenancy.port.LoginBinding;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E6-F2-S1 (T-027) inside the real running application: the ingest validation service runs the contract schema
 * first and then a VALIDATION rule set STORED in PostgreSQL (rule_set, under RLS) through the context's real
 * {@link RuleEvaluationPort} - the ZEN engine (ADR-003) - in one tenant-stamped transaction (R11). Container group
 * (R03): PostgreSQL.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(IngestValidationContainerTest.SuiteDecoder.class)
class IngestValidationContainerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static RestrictedLogin login;

    /** The registered contract of the interface (draft 2020-12). */
    private static final String CONTRACT = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
             "required":["orderNo","amount","currency","reference"],
             "properties":{"orderNo":{"type":"string"},"amount":{"type":"number"},
                           "currency":{"type":"string"},"reference":{"type":"string"},
                           "quantity":{"type":"integer"}}}
            """;

    /**
     * The interface's VALIDATION rule set, lane B's convention (hitPolicy collect; each matching row outputs
     * violation.field/code/message): rows 1-3 match {@code amount 150, currency XXX, reference ""}, row 4 does not.
     */
    private static final String VALIDATION_TABLE = """
            {"nodes":[
              {"id":"request","type":"inputNode","name":"Request","position":{"x":0,"y":0}},
              {"id":"checks","type":"decisionTableNode","name":"Checks","position":{"x":240,"y":0},
               "content":{"hitPolicy":"collect",
                 "inputs":[{"id":"c1","name":"Amount","field":"amount"},
                           {"id":"c2","name":"Currency","field":"currency"},
                           {"id":"c3","name":"Reference","field":"reference"}],
                 "outputs":[{"id":"o1","name":"Field","field":"violation.field"},
                            {"id":"o2","name":"Code","field":"violation.code"},
                            {"id":"o3","name":"Message","field":"violation.message"}],
                 "rules":[
                   {"_id":"r1","c1":"> 100","c2":"","c3":"","o1":"\\"amount\\"","o2":"\\"Z_AMOUNT_TOO_HIGH\\"","o3":"\\"amount must not exceed 100\\""},
                   {"_id":"r2","c1":"","c2":"\\"XXX\\"","c3":"","o1":"\\"currency\\"","o2":"\\"A_CURRENCY_UNKNOWN\\"","o3":"\\"currency XXX is not accepted\\""},
                   {"_id":"r3","c1":"","c2":"","c3":"\\"\\"","o1":"\\"reference\\"","o2":"\\"M_REFERENCE_REQUIRED\\"","o3":"\\"reference is required\\""},
                   {"_id":"r4","c1":"< 0","c2":"","c3":"","o1":"\\"amount\\"","o2":"\\"NEGATIVE\\"","o3":"\\"amount must not be negative\\""}]}},
              {"id":"response","type":"outputNode","name":"Response","position":{"x":480,"y":0}}],
             "edges":[{"id":"e1","sourceId":"request","targetId":"checks","type":"edge"},
                      {"id":"e2","sourceId":"checks","targetId":"response","type":"edge"}]}
            """;

    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID INTERFACE = UUID.randomUUID();
    private static final UUID RULE_SET = UUID.randomUUID();

    @Autowired
    private RuleEvaluationPort ruleEvaluationPort;

    @Autowired
    private PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SuiteDecoder {

        @Bean
        @Primary
        JwtDecoder ingestValidationSuiteJwtDecoder() {
            return token -> {
                Instant now = Instant.now();
                return Jwt.withTokenValue(token).header("alg", "none")
                        .issuer(PlatformTestWiring.OIDC_ISSUER.toString()).subject(token)
                        .audience(List.of(PlatformTestWiring.OIDC_AUDIENCE))
                        .issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(5))).build();
            };
        }
    }

    /** Once, after the context has migrated the schema (Flyway runs at context start). */
    @BeforeEach
    void seedTheStoredRuleSet() throws SQLException {
        if (!SEEDED.compareAndSet(false, true)) {
            return;
        }
        try (Connection owner = PlatformTestWiring.ownerConnection(POSTGRES)) {
            execute(owner, "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')",
                    TENANT.toString(), "tnt-e6-ingest-" + TENANT.toString().substring(0, 8));
            execute(owner, "INSERT INTO interface (id, tenant_id, code, name) VALUES (?::uuid, ?::uuid, ?, ?)",
                    INTERFACE.toString(), TENANT.toString(), "orders-in", "Orders in");
            String table = JSON.readTree(VALIDATION_TABLE).toString();
            execute(owner, "INSERT INTO rule_set (id, tenant_id, interface_id, kind, name, content, content_text,"
                            + " author_id) VALUES (?::uuid, ?::uuid, ?::uuid, 'VALIDATION', ?, ?::jsonb, ?, ?::uuid)",
                    RULE_SET.toString(), TENANT.toString(), INTERFACE.toString(), "order checks", table, table,
                    USER.toString());
        }
        PlatformTestWiring.grantApplicationRole(POSTGRES, login);
    }

    @Test
    @DisplayName("Scenario: Schema first, then rules, all listed - 2 schema + 3 violations from the stored ZEN table")
    void schemaFirstThenTheStoredRulesAllListed() {
        PayloadValidationService service = new PayloadValidationService(new ContractSchemaCheck(), ruleEvaluationPort);
        JsonNode payload = JSON.readTree(
                "{\"amount\":150,\"currency\":\"XXX\",\"reference\":\"\",\"quantity\":\"two\"}");

        RejectionEnvelope rejection = asTenant(() -> service.validate(JSON.readTree(CONTRACT), List.of(RULE_SET), payload));

        assertThat(rejection.rejected()).isTrue();
        assertThat(rejection.rulesEvaluated()).isTrue();
        assertThat(rejection.violations()).hasSize(5);
        assertThat(rejection.violations().subList(0, 2)).extracting(Violation::source, Violation::field, Violation::code)
                .containsExactlyInAnyOrder(tuple(Source.SCHEMA, "orderNo", "SCHEMA_REQUIRED"),
                        tuple(Source.SCHEMA, "quantity", "SCHEMA_TYPE"));
        assertThat(rejection.violations().subList(2, 5)).extracting(Violation::source, Violation::field,
                Violation::code, Violation::message).containsExactly(
                tuple(Source.RULE, "amount", "Z_AMOUNT_TOO_HIGH", "amount must not exceed 100"),
                tuple(Source.RULE, "currency", "A_CURRENCY_UNKNOWN", "currency XXX is not accepted"),
                tuple(Source.RULE, "reference", "M_REFERENCE_REQUIRED", "reference is required"));
    }

    @Test
    @Tag("negative")
    @DisplayName("@negative Scenario: Rules do not run on a payload that is not the contract's shape")
    void theStoredRulesDoNotRunOnAnArray() {
        PayloadValidationService service = new PayloadValidationService(new ContractSchemaCheck(), ruleEvaluationPort);

        RejectionEnvelope rejection = asTenant(() -> service.validate(JSON.readTree(CONTRACT), List.of(RULE_SET),
                JSON.readTree("[{\"amount\":150}]")));

        assertThat(rejection.rulesEvaluated()).isFalse();
        assertThat(rejection.violations()).isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.source()).isEqualTo(Source.SCHEMA));
    }

    /** Bound the way a request binds a tenant, in one transaction of the context's tenant-stamping manager (R11). */
    private <T> T asTenant(Supplier<T> work) {
        SecurityContext security = SecurityContextHolder.createEmptyContext();
        Jwt jwt = Jwt.withTokenValue("e6-ingest-validation").header("alg", "none").subject("sub-e6-ingest").build();
        security.setAuthentication(new TenantBoundAuthentication(new JwtAuthenticationToken(jwt),
                new LoginBinding(USER, "ACTIVE", TENANT, "tnt-e6-ingest", "ACTIVE", ZoneId.of("Etc/UTC"))));
        SecurityContextHolder.setContext(security);
        try {
            return new TransactionTemplate(transactions).execute(status -> work.get());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void execute(Connection owner, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }
}
