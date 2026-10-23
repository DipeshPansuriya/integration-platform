package io.integrationplatform.ingest.validation;

import com.networknt.schema.Error;
import com.networknt.schema.ExecutionConfig;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;
import io.integrationplatform.ingest.validation.Violation.Source;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * Step 1 of the intake validation (architecture.md stage 3, ADR-003 rule 4): the payload against the registered JSON
 * Schema (draft 2020-12) of the message's contract version, with networknt json-schema-validator 2.0.1 - the one
 * version R12 allows - configured to collect EVERY error, never to stop at the first.
 *
 * <p>Remote {@code $ref}s are never fetched: a stored contract stands alone (ADR-011 Decision 2), so validation makes
 * no outbound call.
 *
 * <p>B37: the intake compiles the contract once per contract version, not per record. A contract version is immutable
 * once approved (T-018), so the compiled schema is cached under a caller-given key (tenant + contract version id) and
 * the cache is bounded; the key's schema source is only asked on a miss. The uncached {@link #check(JsonNode, JsonNode)}
 * stays for callers that hold a one-off schema.
 */
public class ContractSchemaCheck {

    /** What the schema step found, and whether the payload is shaped like the contract so the rules may run. */
    public record SchemaCheck(List<Violation> violations, boolean contractShaped) {

        public SchemaCheck {
            violations = List.copyOf(violations);
        }
    }

    private static final ExecutionConfig COLLECT_ALL = ExecutionConfig.builder().failFast(false).build();

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false)));
    private final ExecutionConfig executionConfig;
    private final Map<Object, Schema> compiled = new ConcurrentHashMap<>();

    /** Upper bound of cached contract versions; past it the cache is dropped and refills (a rare, safe miss). */
    static final int CACHE_LIMIT = 256;

    public ContractSchemaCheck() {
        this(COLLECT_ALL);
    }

    /** Package-private: only the R02 planted-violation test passes another (fail-fast) configuration. */
    ContractSchemaCheck(ExecutionConfig executionConfig) {
        this.executionConfig = Objects.requireNonNull(executionConfig, "executionConfig");
    }

    /**
     * Validates {@code payload} against {@code contractSchema} and lists every violation. The payload is "contract
     * shaped" when it is a JSON object and the schema raised no type error at its root - field-level errors do not stop
     * the rules (all violations from both steps come back together).
     */
    public SchemaCheck check(JsonNode contractSchema, JsonNode payload) {
        return check(compile(contractSchema), payload);
    }

    /**
     * As {@link #check(JsonNode, JsonNode)}, with the compiled schema cached under {@code contractKey} (the caller's
     * unique, immutable id of the contract version, including the tenant); {@code contractSchema} is read only when
     * that key is not cached yet.
     */
    public SchemaCheck check(Object contractKey, Supplier<JsonNode> contractSchema, JsonNode payload) {
        Schema schema = compiled.get(contractKey);
        if (schema == null) {
            schema = compile(contractSchema.get());
            if (compiled.size() >= CACHE_LIMIT) {
                compiled.clear();
            }
            compiled.put(contractKey, schema);
        }
        return check(schema, payload);
    }

    private Schema compile(JsonNode contractSchema) {
        return registry.getSchema(contractSchema.toString(), InputFormat.JSON);
    }

    private SchemaCheck check(Schema schema, JsonNode payload) {
        // networknt reads Jackson 2 trees; the platform holds Jackson 3 trees, so the payload crosses as JSON text.
        List<Error> errors = schema.validate(payload.toString(), InputFormat.JSON,
                context -> context.setExecutionConfig(executionConfig));
        boolean rootTypeError = errors.stream()
                .anyMatch(error -> "type".equals(error.getKeyword()) && error.getInstanceLocation().getNameCount() == 0);
        List<Violation> violations = errors.stream().map(ContractSchemaCheck::violation).toList();
        return new SchemaCheck(violations, payload.isObject() && !rootTypeError);
    }

    private static Violation violation(Error error) {
        return new Violation(fieldPath(error), "SCHEMA_" + constantCase(error.getKeyword()), error.getMessage(),
                Source.SCHEMA);
    }

    /**
     * The instance location as {@code a.b[0].c}; for keywords reported on the parent object ({@code required} and
     * the like) the property is appended, so a missing field is named by its own path. The root is {@code $}.
     */
    static String fieldPath(Error error) {
        StringBuilder path = new StringBuilder();
        NodePath location = error.getInstanceLocation();
        String last = null;
        for (int i = 0; i < location.getNameCount(); i++) {
            Object element = location.getElement(i);
            if (element instanceof Integer index) {
                path.append('[').append(index).append(']');
                last = null;
            } else {
                last = String.valueOf(element);
                path.append(path.isEmpty() ? "" : ".").append(last);
            }
        }
        String property = error.getProperty();
        if (property != null && !property.equals(last)) {
            path.append(path.isEmpty() ? "" : ".").append(property);
        }
        return path.isEmpty() ? "$" : path.toString();
    }

    /** {@code minLength} -> {@code MIN_LENGTH}. */
    private static String constantCase(String keyword) {
        return keyword == null ? "INVALID" : keyword.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }
}
