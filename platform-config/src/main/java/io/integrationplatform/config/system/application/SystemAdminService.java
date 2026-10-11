package io.integrationplatform.config.system.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemDetail;
import io.integrationplatform.tenancy.port.LoginBinding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The systems registry (T-015's onboarding premise, extended by E5 T-102): a row in {@code system} - since E5 with a
 * type (API, DB, MQ, FILE) and that type's settings ({@link SystemSettings}) in the same table, no second systems table
 * (ADR-011 Decision 2) - and for an OUTBOUND system the one default target endpoint its flows route through. The API's
 * INBOUND/OUTBOUND vocabulary maps to the schema's SOURCE/TARGET closed set at this boundary and the answer echoes the
 * API vocabulary back. A save is refused with EVERY validation error listed ({@link SystemValidationException}).
 */
public class SystemAdminService {

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcSystemRepository systems;
    private final TestConnectionService testConnection;

    public SystemAdminService(JdbcSystemRepository systems, TestConnectionService testConnection) {
        this.systems = Objects.requireNonNull(systems, "systems");
        this.testConnection = Objects.requireNonNull(testConnection, "testConnection");
    }

    /** Registers a system; type and settings are optional together (an E3 untyped registration stays valid). */
    @Transactional
    public Map<String, Object> create(JsonNode body) {
        List<String> errors = new ArrayList<>();
        String code = text(body, "code", errors);
        String name = text(body, "name", errors);
        String direction = direction(body, errors);
        JsonNode type = field(body, "type");
        JsonNode settings = field(body, "settings");
        errors.addAll(SystemSettings.validate(type, settings));
        if (!errors.isEmpty()) {
            throw new SystemValidationException(errors);
        }
        if (systems.findSystemByCode(code).isPresent()) {
            throw ConfigApiException.conflict("a system with code " + code + " is already registered");
        }
        String typeName = SystemSettings.typeOf(type);
        String directionDb = JdbcSystemRepository.directionToDb(direction);
        String id = typeName == null
                ? systems.insertSystem(code, name, directionDb)
                : systems.insertTypedSystem(code, name, directionDb, typeName, settings.toString());
        if (direction.equals("OUTBOUND")) {
            systems.insertDefaultEndpoint(id, code, name);
        }
        return view(new SystemDetail(id, code, name, directionDb, typeName,
                typeName == null ? null : settings.toString()));
    }

    /** Every system of the bound tenant, ordered by code. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return systems.listSystems().stream()
                .sorted(Comparator.comparing(SystemDetail::code))
                .map(SystemAdminService::view)
                .toList();
    }

    /** One system; 404 when the id is malformed or the bound tenant cannot see it. */
    @Transactional(readOnly = true)
    public Map<String, Object> read(String systemId) {
        return view(require(systemId));
    }

    /**
     * Edits a system: {@code name} when given; {@code type} and {@code settings} together when either is given
     * (validated against the type's schema; both JSON null returns the system to untyped). The code and the direction
     * are not edited here. 404 for a row the bound tenant cannot see, before any validation.
     */
    @Transactional
    public Map<String, Object> update(String systemId, JsonNode body) {
        SystemDetail current = require(systemId);
        List<String> errors = new ArrayList<>();
        boolean hasName = body != null && body.has("name");
        boolean hasTypeOrSettings = body != null && (body.has("type") || body.has("settings"));
        if (!hasName && !hasTypeOrSettings) {
            throw new SystemValidationException(List.of("nothing to change: name, type or settings"));
        }
        String name = hasName ? text(body, "name", errors) : current.name();
        String type = current.type();
        String settings = current.settings();
        if (hasTypeOrSettings) {
            JsonNode typeNode = field(body, "type");
            JsonNode settingsNode = field(body, "settings");
            errors.addAll(SystemSettings.validate(typeNode, settingsNode));
            type = SystemSettings.typeOf(typeNode);
            settings = type == null ? null : settingsNode.toString();
        }
        if (!errors.isEmpty()) {
            throw new SystemValidationException(errors);
        }
        if (systems.updateSystem(current.id(), name, type, settings) != 1) {
            throw ConfigApiException.notFound();
        }
        return view(new SystemDetail(current.id(), current.code(), name, current.direction(), type, settings));
    }

    /**
     * T-104: tests a system's connection for the acting user (rate-limited per tenant + user, audited). Deliberately
     * NOT transactional - the destination is called outside any transaction.
     */
    public Map<String, Object> testConnection(String systemId, LoginBinding actor) {
        return testConnection.test(systemId, actor.tenantId(), actor.userId());
    }

    /** The endpoint a destination code routes through; empty when the system (or its endpoint) is unregistered. */
    @Transactional(readOnly = true)
    public Optional<String> endpointOfDestination(String code) {
        return systems.findSystemByCode(code).flatMap(system -> systems.findEndpointOfSystem(system.id()));
    }

    /**
     * T-015: the one-screen onboarding shape - a flow that names an unregistered destination brings it on-line in
     * the same transaction: an OUTBOUND system row and its default endpoint, exactly what the systems API creates
     * (E5 replaces the endpoint's placeholder settings through the same table).
     */
    @Transactional
    public String endpointFor(String code) {
        return endpointOfDestination(code).orElseGet(() -> {
            String name = "system " + code;
            String systemId = systems.insertSystem(code, name, JdbcSystemRepository.directionToDb("OUTBOUND"));
            return systems.insertDefaultEndpoint(systemId, code, name);
        });
    }

    @Transactional(readOnly = true)
    public boolean systemExists(String code) {
        return systems.findSystemByCode(code).isPresent();
    }

    /** Required non-blank text field of a request body (shared validation seam of the module). */
    public static String required(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.path(field);
        String text = value == null || value.isMissingNode() || value.isNull() ? null : value.asString();
        if (text == null || text.isBlank()) {
            throw ConfigApiException.validation(field + " is required");
        }
        return text;
    }

    /** A canonical 8-4-4-4-12 uuid text (anything else is a 404, never a database cast error). */
    static boolean isUuid(String text) {
        return text != null && UUID_TEXT.matcher(text).matches();
    }

    private SystemDetail require(String systemId) {
        if (!isUuid(systemId)) {
            throw ConfigApiException.notFound();
        }
        return systems.findSystem(systemId).orElseThrow(ConfigApiException::notFound);
    }

    /** The one JSON view of a system: id, code, name, direction (API words), type and settings (JSON null if none). */
    static Map<String, Object> view(SystemDetail system) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", system.id());
        body.put("code", system.code());
        body.put("name", system.name());
        body.put("direction", JdbcSystemRepository.directionFromDb(system.direction()));
        body.put("type", system.type());
        body.put("settings", system.settings() == null ? null : JSON.readTree(system.settings()));
        return body;
    }

    private static JsonNode field(JsonNode body, String name) {
        return body == null ? null : body.get(name);
    }

    private static String text(JsonNode body, String field, List<String> errors) {
        JsonNode value = field(body, field);
        if (value == null || value.isNull() || !value.isString() || value.stringValue().isBlank()) {
            errors.add(field + " is required (a non-blank text)");
            return null;
        }
        if (value.stringValue().length() > SystemSettings.MAX_TEXT) {
            errors.add(field + " must be at most " + SystemSettings.MAX_TEXT + " characters");
            return null;
        }
        return value.stringValue();
    }

    private static String direction(JsonNode body, List<String> errors) {
        JsonNode value = field(body, "direction");
        String text = value != null && value.isString() ? value.stringValue() : null;
        if (!"INBOUND".equals(text) && !"OUTBOUND".equals(text)) {
            errors.add("direction must be INBOUND or OUTBOUND");
            return null;
        }
        return text;
    }
}
