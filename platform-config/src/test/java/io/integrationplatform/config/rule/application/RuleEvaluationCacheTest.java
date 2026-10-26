package io.integrationplatform.config.rule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.integrationplatform.config.rule.Jdm;
import io.integrationplatform.config.rule.adapter.out.ZenDecisionEngine;
import io.integrationplatform.config.rule.api.RuleSetNotFoundException;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository.Fingerprinted;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository.RuleSetRow;
import io.integrationplatform.tenancy.TenantContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * L7: B37 calls the rules port once per record, so the stored rule set must not be re-read and recompiled per record
 * - and a cache must never serve a changed rule set, nor cross tenants. The repository is a stand-in that behaves as
 * the tenant-scoped table does (rows per tenant, edited in place); the engine is the real one behind a compile counter.
 */
class RuleEvaluationCacheTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static ZenDecisionEngine zen;

    private final AtomicInteger compiles = new AtomicInteger();
    private final DecisionEngine engine = new DecisionEngine() {
        @Override
        public Optional<TableProblem> check(String jdm) {
            return zen.check(jdm);
        }

        @Override
        public JsonNode evaluate(String jdm, JsonNode input) {
            return zen.evaluate(jdm, input);
        }

        @Override
        public CompiledDecision compile(String jdm) {
            compiles.incrementAndGet();
            return zen.compile(jdm);
        }
    };

    private final JdbcRuleSetRepository repository = mock(JdbcRuleSetRepository.class);
    /** The bound tenant, switched by a test the way a request binds one. */
    private UUID tenant = UUID.randomUUID();
    private final TenantContext tenantContext = () -> Optional.of(tenant);
    /** tenant -> rule set id -> content: what the tenant-scoped statements can see (RLS). */
    private final Map<UUID, Map<UUID, String>> table = new HashMap<>();

    @BeforeAll
    static void engine() {
        zen = new ZenDecisionEngine();
    }

    @AfterAll
    static void close() {
        zen.close();
    }

    private RuleEvaluationService port(int maxEntries) {
        when(repository.fingerprint(any())).thenAnswer(call -> content(call.getArgument(0)).map(c -> fingerprint(c)));
        when(repository.findFingerprinted(any())).thenAnswer(call -> content(call.getArgument(0))
                .map(content -> new Fingerprinted(new RuleSetRow(call.getArgument(0).toString(),
                        UUID.randomUUID().toString(), "VALIDATION", "v", content), fingerprint(content))));
        return new RuleEvaluationService(repository, engine, tenantContext, maxEntries);
    }

    private Optional<String> content(UUID id) {
        return Optional.ofNullable(table.getOrDefault(tenant, Map.of()).get(id));
    }

    private static String fingerprint(String content) {
        return Integer.toHexString(content.hashCode()); // any function of the content will do for a stand-in
    }

    @Test
    @DisplayName("500 evaluations of one rule set read the stored content once and compile once")
    void bulkOfFiveHundredReadsAndCompilesOnce() {
        UUID id = UUID.randomUUID();
        table.put(tenant, Map.of(id, Jdm.validationTable()));
        RuleEvaluationService port = port(256);

        JsonNode input = JSON.readTree(Jdm.validationInput());
        for (int i = 0; i < 100; i++) { // warm the JVM and the native engine before timing either path
            zen.evaluate(Jdm.validationTable(), input);
        }
        long start = System.nanoTime();
        for (int i = 0; i < 500; i++) { // what the port did before L7 per record, minus the store read
            zen.evaluate(Jdm.validationTable(), input);
        }
        double before = (System.nanoTime() - start) / 500_000.0;
        start = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            assertThat(port.evaluate(id, input).violations()).hasSize(3);
        }
        double after = (System.nanoTime() - start) / 500_000.0;
        System.out.printf("L7-MEASURE per record, warm, engine only (store mocked): compile+evaluate %.1f us, "
                + "cached %.1f us%n", before, after);

        verify(repository, times(1)).findFingerprinted(id);
        verify(repository, times(500)).fingerprint(id);
        assertThat(compiles).hasValue(1);
    }

    @Test
    @DisplayName("an edited rule set is picked up on the very next evaluation")
    void editedRuleSetIsNeverServedStale() {
        UUID id = UUID.randomUUID();
        table.put(tenant, new HashMap<>(Map.of(id, Jdm.validationTable())));
        RuleEvaluationService port = port(256);
        JsonNode input = JSON.readTree(Jdm.validationInput());
        assertThat(port.evaluate(id, input).violations()).hasSize(3);

        table.get(tenant).put(id, Jdm.routingTable()); // edited in place: other content, same id
        assertThat(port.evaluate(id, input).violations()).isEmpty(); // no longer the validation table's answer
        assertThat(compiles).hasValue(2);

        table.get(tenant).put(id, Jdm.validationTable()); // edited back
        assertThat(port.evaluate(id, input).violations()).hasSize(3);
        assertThat(compiles).hasValue(3);
    }

    @Test
    @DisplayName("two tenants with the same rule set id keep their own entries and never see each other's")
    void tenantsNeverShareAnEntry() {
        UUID id = UUID.randomUUID();
        UUID tenantA = tenant;
        UUID tenantB = UUID.randomUUID();
        table.put(tenantA, Map.of(id, Jdm.validationTable()));
        table.put(tenantB, Map.of(id, Jdm.routingTable()));
        RuleEvaluationService port = port(256);
        JsonNode input = JSON.readTree(Jdm.validationInput());

        for (int i = 0; i < 3; i++) {
            tenant = tenantA;
            assertThat(port.evaluate(id, input).violations()).hasSize(3);
            tenant = tenantB;
            assertThat(port.evaluate(id, input).violations()).isEmpty();
        }
        assertThat(compiles).hasValue(2); // one entry per tenant: no thrashing, no sharing
        assertThat(port.cachedEntries()).isEqualTo(2);
    }

    @Test
    @DisplayName("a rule set cached for one tenant is not found by another tenant that cannot see it")
    void cachedEntryDoesNotLeakToAnotherTenant() {
        UUID id = UUID.randomUUID();
        table.put(tenant, Map.of(id, Jdm.validationTable()));
        RuleEvaluationService port = port(256);
        port.evaluate(id, JSON.readTree(Jdm.validationInput())); // cached under tenant A

        tenant = UUID.randomUUID(); // tenant B sees no such row
        assertThatThrownBy(() -> port.evaluate(id, JSON.readTree(Jdm.validationInput())))
                .isInstanceOf(RuleSetNotFoundException.class);
    }

    @Test
    @DisplayName("the cache is bounded: the least recently used entry is dropped")
    void cacheIsBounded() {
        Map<UUID, String> sets = new HashMap<>();
        UUID[] ids = new UUID[5];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = UUID.randomUUID();
            sets.put(ids[i], Jdm.validationTable());
        }
        table.put(tenant, sets);
        RuleEvaluationService port = port(3);

        for (UUID id : ids) {
            port.evaluate(id, JSON.readTree(Jdm.validationInput()));
        }
        assertThat(port.cachedEntries()).isEqualTo(3);

        port.evaluate(ids[0], JSON.readTree(Jdm.validationInput())); // evicted earlier: compiled again, still correct
        assertThat(compiles).hasValue(6);
        assertThat(port.cachedEntries()).isEqualTo(3);
    }
}
