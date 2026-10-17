package io.integrationplatform.config.rule.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository;
import io.integrationplatform.config.rule.internal.JdbcRuleSetRepository.RuleSetRow;
import io.integrationplatform.tenancy.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Authoring rule sets on a flow (T-023, E6-F1-S1): a VALIDATION, ROUTING or LOOKUP decision table is saved as one
 * configuration row in the open ZEN JSON format, only after the engine itself accepts every table and every cell
 * ({@link DecisionEngine#check}), so the editor and the runtime agree on what is valid. A LOOKUP rule set is also
 * registered by name as the tenant's lookup table. Every id the bound tenant cannot see answers 404 - never 403,
 * never a hint.
 */
public class RuleSetAdminService {

    public static final List<String> KINDS = List.of("VALIDATION", "ROUTING", "LOOKUP");

    private final JdbcRuleSetRepository ruleSets;
    private final DecisionEngine engine;
    private final ApplicationEventPublisher events;
    private final TenantContext tenants;

    /**
     * @param events  where a completed create or update publishes its audit event (E12-F2-S1)
     * @param tenants the bound tenant the audit event names
     */
    public RuleSetAdminService(JdbcRuleSetRepository ruleSets, DecisionEngine engine,
            ApplicationEventPublisher events, TenantContext tenants) {
        this.ruleSets = Objects.requireNonNull(ruleSets, "ruleSets");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.events = Objects.requireNonNull(events, "events");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    @Transactional
    public RuleSetRow create(String flowId, JsonNode body, UUID authorId) {
        UUID flow = id(flowId);
        if (!ruleSets.flowExists(flow)) {
            throw ConfigApiException.notFound();
        }
        String kind = text(body, "kind");
        if (!KINDS.contains(kind)) {
            throw ConfigApiException.validation("kind must be one of " + KINDS);
        }
        String name = name(body);
        String content = checkedContent(body);
        String id = ruleSets.insert(flow, kind, name, content, authorId);
        if ("LOOKUP".equals(kind)) {
            ruleSets.insertLookupTable(id, name);
        }
        return new RuleSetRow(id, flow.toString(), kind, name, content);
    }

    @Transactional(readOnly = true)
    public List<RuleSetRow> list(String flowId) {
        UUID flow = id(flowId);
        if (!ruleSets.flowExists(flow)) {
            throw ConfigApiException.notFound();
        }
        return ruleSets.listByFlow(flow);
    }

    @Transactional(readOnly = true)
    public RuleSetRow read(String ruleSetId) {
        return ruleSets.find(id(ruleSetId)).orElseThrow(ConfigApiException::notFound);
    }

    /** The kind and the flow are fixed at creation; the name and the table are edited in place. */
    @Transactional
    public RuleSetRow update(String ruleSetId, JsonNode body, UUID authorId) {
        UUID id = id(ruleSetId);
        RuleSetRow stored = ruleSets.find(id).orElseThrow(ConfigApiException::notFound);
        String name = name(body);
        String content = checkedContent(body);
        if (ruleSets.update(id, name, content, authorId) != 1) {
            throw ConfigApiException.notFound();
        }
        if ("LOOKUP".equals(stored.kind())) {
            ruleSets.renameLookupTable(id, name);
        }
        return new RuleSetRow(stored.id(), stored.flowId(), stored.kind(), name, content);
    }

    /** A path id that is not a uuid names no row: the same 404 as a foreign or unknown id, never a 500. */
    private static UUID id(String pathId) {
        try {
            return UUID.fromString(pathId);
        } catch (IllegalArgumentException notAUuid) {
            throw ConfigApiException.notFound();
        }
    }

    private static String name(JsonNode body) {
        String name = text(body, "name");
        if (name == null || name.isBlank()) {
            throw ConfigApiException.validation("name is required");
        }
        return name;
    }

    /** The content as the JSON text that is stored and re-opened; refused unless the engine accepts it. */
    private String checkedContent(JsonNode body) {
        JsonNode content = body == null ? null : body.get("content");
        if (content == null || !content.isObject()) {
            throw new RuleTableInvalidException(new DecisionEngine.TableProblem(null, null, null,
                    "content must be a JSON Decision Model object"));
        }
        String text = content.toString();
        Optional<DecisionEngine.TableProblem> problem = engine.check(text);
        if (problem.isPresent()) {
            throw new RuleTableInvalidException(problem.get());
        }
        return text;
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }
}
