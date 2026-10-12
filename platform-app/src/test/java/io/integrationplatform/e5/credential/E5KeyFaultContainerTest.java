package io.integrationplatform.e5.credential;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.integrationplatform.TestImages;
import io.integrationplatform.config.credential.api.KeyEncryptionKeys;
import io.integrationplatform.config.credential.application.CredentialAudit;
import io.integrationplatform.config.credential.application.TargetCredentialService;
import io.integrationplatform.config.credential.readiness.TargetCredentialKeysHealthIndicator;
import io.integrationplatform.config.internal.persistence.CredentialTargetRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRepository;
import io.integrationplatform.delivery.internal.JdbcDeliveryRetryStore;
import io.integrationplatform.domain.delivery.DeliveryState;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.credential.CredentialedDelivery;
import io.integrationplatform.routing.credential.SendTimeCredentials;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.LoginBinding;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * E5-F1-S4 (T-185; ADR-007 Decision 7 rules 5 and 6; ADR-010 sections 5, 6 and 9) on the REAL tables of every module,
 * PostgreSQL as the restricted login: the target credential saved by the configuration module's service (sealed with
 * K1), opened at send time by platform-routing, the delivery moved by platform-delivery's store, the instance's
 * readiness decided by the configuration module's indicator. The pieces are composed by hand - the consumer that will
 * drive them is E10's - so each instance's keyring is exactly what the scenario says.
 *
 * <pre>
 * Scenario: Unknown key-encryption-key id is transient
 *   Given an instance started without the key id that a target credential references
 *   When a delivery to that target is attempted
 *   Then the outcome is TRANSIENT, the delivery waits in RETRY_WAIT, an alert fires, and the instance fails its
 *        readiness check
 *
 * &#64;negative &#64;security
 * Scenario: Tag mismatch is permanent
 *   Given a target credential ciphertext copied from another tenant's row
 *   When it is decrypted at send time
 *   Then the delivery is DEAD_LETTERED with reason "target credential unreadable", and neither ciphertext nor
 *        plaintext is logged
 * </pre>
 * "Started without the key id": the start-up check reads the key ids the credential rows reference; the production
 * reader of that list across tenants waits for a reviewed tenant-boundary crossing (see the T44 hand-back), so this
 * suite gives the indicator the list the owner reads - the check's decision itself is the production code.
 */
@Tag("container")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("E5-F1-S4 key faults on the real tables")
class E5KeyFaultContainerTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer(TestImages.POSTGRES);
    private static final SecretKey K1 = aesKey();
    private static final SecretKey K2 = aesKey();

    private static HikariDataSource pool;
    private static Tenant tenantA;
    private static Tenant tenantB;

    record Tenant(UUID id, UUID endpoint, LoginBinding actor) {
    }

    @BeforeAll
    static void database() throws Exception {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        String login = "e5_key_login_" + Long.toUnsignedString(RANDOM.nextLong(), 36);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(aesKey().getEncoded());
        try (Connection owner = owner(); PreparedStatement format = owner.prepareStatement(
                "SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD %L', ?, ?),"
                        + " format('GRANT ipaas_app TO %I', ?)")) {
            format.setString(1, login);
            format.setString(2, password);
            format.setString(3, login);
            try (ResultSet rs = format.executeQuery()) {
                rs.next();
                owner.createStatement().execute(rs.getString(1));
                owner.createStatement().execute(rs.getString(2));
            }
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(login);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        pool = new HikariDataSource(config);
        tenantA = seedTenant("alpha");
        tenantB = seedTenant("bravo");
    }

    @AfterAll
    static void close() {
        if (pool != null) {
            pool.close();
        }
    }

    private static Connection owner() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static SecretKey aesKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new SecretKeySpec(bytes, "AES");
    }

    private static Tenant seedTenant(String word) throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID system = UUID.randomUUID();
        UUID endpoint = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        try (Connection owner = owner()) {
            run(owner, "INSERT INTO tenant (id, code, status, time_zone) VALUES (?::uuid, ?, 'ACTIVE', 'Etc/UTC')",
                    tenant.toString(), "tnt-e5key-" + word + "-" + tenant.toString().substring(0, 6));
            run(owner, "INSERT INTO public.system (id, tenant_id, code, name, direction, type, settings) VALUES"
                    + " (?::uuid, ?::uuid, ?, 'Partner', 'TARGET', 'API', '{\"baseUrl\":\"https://partner.example/\"}')",
                    system.toString(), tenant.toString(), "partner-" + word);
            run(owner, "INSERT INTO public.target_endpoint (id, tenant_id, system_id, code, name, base_url,"
                    + " http_method, auth_method, timeout_ms) VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'Partner',"
                    + " 'https://partner.example/', 'POST', 'BASIC', 5000)", endpoint.toString(), tenant.toString(),
                    system.toString(), "ep-" + word);
        }
        return new Tenant(tenant, endpoint, new LoginBinding(user, "ACTIVE", tenant, "tnt-" + word, "ACTIVE",
                ZoneId.of("Etc/UTC")));
    }

    private static void run(Connection connection, String sql, String... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }

    /** Transactions of the restricted login with app.tenant_id stamped first - what the stamping manager does. */
    private static TransactionOperations stamped(UUID tenant) {
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(pool));
        JdbcClient jdbc = JdbcClient.create(pool);
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return template.execute((TransactionStatus status) -> {
                    jdbc.sql("select set_config('app.tenant_id', :tenant, true)").param("tenant", tenant.toString())
                            .query().singleValue();
                    return action.doInTransaction(status);
                });
            }
        };
    }

    private static KeyEncryptionKeys keyring(Map<String, SecretKey> keys, String active) {
        return new KeyEncryptionKeys() {
            @Override
            public Optional<SecretKey> find(String kekId) {
                return Optional.ofNullable(keys.get(kekId));
            }

            @Override
            public Optional<String> activeId() {
                return Optional.ofNullable(active);
            }
        };
    }

    private static CredentialTargetRepository repository(UUID tenant) {
        return new CredentialTargetRepository(JdbcClient.create(pool), () -> Optional.of(tenant));
    }

    /** The configuration module's real save: the tenant admin stores the endpoint's basic-auth password (K1). */
    private static void save(Tenant tenant, String password) {
        new TargetCredentialService(repository(tenant.id()), stamped(tenant.id()), keyring(Map.of("K1", K1), "K1"),
                new CredentialAudit()).save(tenant.endpoint().toString(),
                JSON.readTree("{\"fields\":{\"basicPassword\":\"" + password + "\"}}"), tenant.actor());
    }

    /** A delivery of the tenant to its endpoint, claimed (SENDING) under {@code token}. */
    private static UUID claimedDelivery(Tenant tenant, UUID token) {
        UUID id = UUID.randomUUID();
        TenantContext bound = () -> Optional.of(tenant.id());
        JdbcDeliveryRepository deliveries = new JdbcDeliveryRepository(JdbcClient.create(pool), bound);
        stamped(tenant.id()).executeWithoutResult(status -> {
            Instant now = Instant.now();
            deliveries.create(id, UUID.randomUUID(), "bk-" + id, null, tenant.endpoint(), now);
            assertThat(deliveries.claimSend(id, token, now)).isTrue();
        });
        return id;
    }

    private static String owner(String sql, String value) throws Exception {
        try (Connection owner = owner(); PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** What the start-up check reads: every key id a credential row references (read here as the owner). */
    private static Set<String> referencedKeyIds() {
        try (Connection owner = owner(); ResultSet rs = owner.createStatement().executeQuery(
                "SELECT DISTINCT kek_id FROM public.target_credential")) {
            Set<String> ids = new HashSet<>();
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Scenario: Unknown key-encryption-key id is transient - RETRY_WAIT, no attempt, alert, not ready")
    void unknownKeyIdIsTransient(CapturedOutput output) throws Exception {
        String password = "needs-k1-" + UUID.randomUUID();
        save(tenantA, password);
        // an instance started with K2 only: its start-up check already finds K1 missing
        KeyEncryptionKeys withoutK1 = keyring(Map.of("K2", K2), "K2");
        TargetCredentialKeysHealthIndicator readiness = new TargetCredentialKeysHealthIndicator(withoutK1,
                E5KeyFaultContainerTest::referencedKeyIds);
        assertThat(readiness.health().getStatus()).as("not ready from start-up").isEqualTo(Status.OUT_OF_SERVICE);
        UUID token = UUID.randomUUID();
        UUID delivery = claimedDelivery(tenantA, token);
        List<String> calls = new ArrayList<>();
        CredentialedDelivery send = new CredentialedDelivery(
                new SendTimeCredentials(repository(tenantA.id()), withoutK1), readiness,
                new JdbcDeliveryRetryStore(JdbcClient.create(pool), () -> Optional.of(tenantA.id())),
                stamped(tenantA.id()), () -> 1.0, Clock.systemUTC());

        CredentialedDelivery.Result<String> result = send.send(delivery, token, tenantA.endpoint(), opened -> {
            calls.add("called");
            return "sent";
        });

        assertThat(result.kind()).isEqualTo(CredentialedDelivery.Kind.KEY_UNAVAILABLE);
        assertThat(result.outcomeClass()).isEqualTo(OutcomeClass.TRANSIENT);
        assertThat(calls).as("no call is made").isEmpty();
        assertThat(owner("SELECT state FROM public.delivery WHERE id = ?::uuid", delivery.toString()))
                .isEqualTo(DeliveryState.RETRY_WAIT.name());
        assertThat(owner("SELECT count(*)::text FROM public.delivery_attempt WHERE delivery_id = ?::uuid",
                delivery.toString())).as("no attempt row, no attempt used up").isEqualTo("0");
        assertThat(owner("SELECT string_agg(type, ',' ORDER BY id) FROM public.delivery_event WHERE delivery_id ="
                + " ?::uuid", delivery.toString())).isEqualTo("CLAIM,KEY_UNAVAILABLE");
        assertThat(output).contains("event=credential.key_unavailable").contains("alert=true").contains("kekId=K1");
        assertThat(readiness.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(readiness.health().getDetails()).containsEntry("missingKeyIds", List.of("K1"));
        assertThat(output.getAll()).doesNotContain(password);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario: Tag mismatch is permanent - another tenant's ciphertext dead-letters; nothing of it is logged")
    void tagMismatchIsPermanent(CapturedOutput output) throws Exception {
        String passwordA = "tenant-a-" + UUID.randomUUID();
        String passwordB = "tenant-b-" + UUID.randomUUID();
        save(tenantA, passwordA);
        save(tenantB, passwordB);
        List<String> forbidden = new ArrayList<>(List.of(passwordA, passwordB));
        AtomicReference<String> rowOfA = new AtomicReference<>();
        try (Connection owner = owner()) {
            try (PreparedStatement copy = owner.prepareStatement("UPDATE public.target_credential a SET"
                    + " wrapped_key = b.wrapped_key, ciphertext = b.ciphertext FROM public.target_credential b"
                    + " WHERE a.target_endpoint_id = ?::uuid AND b.target_endpoint_id = ?::uuid"
                    + " AND a.field = 'BASIC_PASSWORD' AND b.field = 'BASIC_PASSWORD' RETURNING a.id::text,"
                    + " a.wrapped_key, a.ciphertext")) {
                copy.setString(1, tenantA.endpoint().toString());
                copy.setString(2, tenantB.endpoint().toString());
                try (ResultSet rs = copy.executeQuery()) {
                    assertThat(rs.next()).as("tenant B's ciphertext copied onto tenant A's row").isTrue();
                    rowOfA.set(rs.getString(1));
                    for (int column = 2; column <= 3; column++) {
                        forbidden.add(Base64.getEncoder().encodeToString(rs.getBytes(column)));
                        forbidden.add(HexFormat.of().formatHex(rs.getBytes(column)));
                    }
                }
            }
        }
        KeyEncryptionKeys withK1 = keyring(Map.of("K1", K1), "K1");
        TargetCredentialKeysHealthIndicator readiness = new TargetCredentialKeysHealthIndicator(withK1,
                E5KeyFaultContainerTest::referencedKeyIds);
        UUID token = UUID.randomUUID();
        UUID delivery = claimedDelivery(tenantA, token);
        CredentialedDelivery send = new CredentialedDelivery(
                new SendTimeCredentials(repository(tenantA.id()), withK1), readiness,
                new JdbcDeliveryRetryStore(JdbcClient.create(pool), () -> Optional.of(tenantA.id())),
                stamped(tenantA.id()), () -> 1.0, Clock.systemUTC());

        CredentialedDelivery.Result<String> result = send.send(delivery, token, tenantA.endpoint(), opened -> "sent");

        assertThat(result.kind()).isEqualTo(CredentialedDelivery.Kind.UNREADABLE);
        assertThat(result.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT);
        assertThat(owner("SELECT state || '|' || dead_letter_class FROM public.delivery WHERE id = ?::uuid",
                delivery.toString())).isEqualTo("DEAD_LETTERED|PERMANENT");
        assertThat(owner("SELECT detail FROM public.delivery_event WHERE delivery_id = ?::uuid AND type ="
                + " 'DEAD_LETTER'", delivery.toString())).contains("target credential unreadable");
        assertThat(readiness.health().getStatus()).as("a tag mismatch is not a missing key").isEqualTo(Status.UP);
        assertThat(output).contains("event=credential.unreadable").contains(rowOfA.get());
        for (String value : forbidden) {
            assertThat(output.getAll()).as("neither ciphertext nor plaintext is logged").doesNotContain(value);
        }
    }
}
