package io.integrationplatform.config.rule.application;

import io.integrationplatform.config.rule.api.RuleEvaluation;
import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.config.rule.api.RuleSetNotFoundException;
import io.integrationplatform.config.rule.api.RuleViolation;
import io.integrationplatform.config.rule.application.DecisionEngine.CompiledDecision;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository.Fingerprinted;
import io.integrationplatform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * {@link RuleEvaluationPort}: evaluates the stored rule set for the tenant of the current transaction with the
 * {@link DecisionEngine}. A VALIDATION rule set's matching rows become {@link RuleViolation}s in table order
 * (hitPolicy "collect" returns an array, one element per matching row; a single-hit policy returns one object).
 *
 * <p>The compiled decision is cached (L7: the data plane calls this once per record). A rule set is edited in place
 * (rule_set has UPDATE on name/content, no version rows), so a hit is only served after one narrow statement proves
 * the stored content is still the cached one: the md5 of {@code content_text}. A changed rule set therefore can never
 * be served stale, whoever changed it; the content itself is read and compiled only on a miss. The key carries the
 * tenant as well as the rule set id (a hit never crosses tenants; the statement itself is tenant-scoped and RLS-bound
 * too), and the cache holds at most {@value #MAX_ENTRIES} entries, least recently used out. Nothing here logs a rule
 * set's content or an input value.
 */
public class RuleEvaluationService implements RuleEvaluationPort {

    static final int MAX_ENTRIES = 256;

    private record Key(UUID tenantId, UUID ruleSetId) {
    }

    private record Compiled(String fingerprint, String kind, CompiledDecision decision) {
    }

    private final JdbcRuleSetRepository ruleSets;
    private final DecisionEngine engine;
    private final TenantContext tenantContext;
    private final Map<Key, Compiled> cache;

    public RuleEvaluationService(JdbcRuleSetRepository ruleSets, DecisionEngine engine, TenantContext tenantContext) {
        this(ruleSets, engine, tenantContext, MAX_ENTRIES);
    }

    RuleEvaluationService(JdbcRuleSetRepository ruleSets, DecisionEngine engine, TenantContext tenantContext,
            int maxEntries) {
        this.ruleSets = Objects.requireNonNull(ruleSets, "ruleSets");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Compiled> eldest) {
                return size() > maxEntries;
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public RuleEvaluation evaluate(UUID ruleSetId, JsonNode input) {
        Compiled compiled = compiled(ruleSetId);
        JsonNode output = compiled.decision().evaluate(input);
        List<RuleViolation> violations = "VALIDATION".equals(compiled.kind()) ? violations(output) : List.of();
        return new RuleEvaluation(ruleSetId, compiled.kind(), output, violations);
    }

    int cachedEntries() {
        return cache.size();
    }

    private Compiled compiled(UUID ruleSetId) {
        // the tenant-scoped statement first: another tenant's id (or an unknown one) is "not found", cache or not
        String fingerprint = ruleSets.fingerprint(ruleSetId).orElseThrow(RuleSetNotFoundException::new);
        Key key = new Key(tenantContext.requireTenantId(), ruleSetId);
        Compiled cached = cache.get(key);
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached;
        }
        Fingerprinted read = ruleSets.findFingerprinted(ruleSetId).orElseThrow(RuleSetNotFoundException::new);
        // the fingerprint of the content actually compiled, not the one read a moment earlier
        Compiled fresh = new Compiled(read.fingerprint(), read.row().kind(), engine.compile(read.row().content()));
        cache.put(key, fresh);
        return fresh;
    }

    private static List<RuleViolation> violations(JsonNode output) {
        List<RuleViolation> violations = new ArrayList<>();
        for (JsonNode row : output.isArray() ? output : List.of(output)) {
            JsonNode violation = row.path("violation");
            if (violation.isObject()) {
                violations.add(new RuleViolation(text(violation, "field"), text(violation, "code"),
                        text(violation, "message")));
            }
        }
        return violations;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
