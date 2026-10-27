package io.integrationplatform.config.system.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.destination.ApiKeyHeaderName;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import io.integrationplatform.config.destination.FileRoots;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemDetail;
import io.integrationplatform.domain.audit.ConfigAuditActions;
import io.integrationplatform.tenancy.port.LoginBinding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
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
    private final DestinationGuard guard;
    private final FileRoots fileRoots;
    private final Supplier<Optional<UUID>> tenant;
    private final boolean allowKafkaPlaintext;
    private final ApplicationEventPublisher events;

    /**
     * @param guard     T-124: the ONE destination guard, run on every type's host field at save (no DNS)
     * @param fileRoots F4: the operator's FILE base folders - a FILE rootPath must lie in the tenant's folder under one
     * @param tenant    the tenant bound to the request (empty = none, a FILE root is then refused)
     */
    public SystemAdminService(JdbcSystemRepository systems, TestConnectionService testConnection,
            DestinationGuard guard, FileRoots fileRoots, Supplier<Optional<UUID>> tenant,
            ApplicationEventPublisher events) {
        this(systems, testConnection, guard, fileRoots, tenant, false, events);
    }

    /** @param allowKafkaPlaintext the operator's {@code platform.config.destinations.kafka-allow-plaintext} (E16 R1/R2) */
    public SystemAdminService(JdbcSystemRepository systems, TestConnectionService testConnection,
            DestinationGuard guard, FileRoots fileRoots, Supplier<Optional<UUID>> tenant,
            boolean allowKafkaPlaintext, ApplicationEventPublisher events) {
        this.events = Objects.requireNonNull(events, "events");
        this.allowKafkaPlaintext = allowKafkaPlaintext;
        this.fileRoots = Objects.requireNonNull(fileRoots, "fileRoots");
        this.tenant = Objects.requireNonNull(tenant, "tenant");
        this.systems = Objects.requireNonNull(systems, "systems");
        this.testConnection = Objects.requireNonNull(testConnection, "testConnection");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /** Registers a system; type and settings are optional together (an E3 untyped registration stays valid). */
    @Transactional
    public Map<String, Object> create(JsonNode body, UUID actorId) {
        List<String> errors = new ArrayList<>();
        String code = text(body, "code", errors);
        String name = text(body, "name", errors);
        String direction = direction(body, errors);
        JsonNode type = field(body, "type");
        JsonNode settings = field(body, "settings");
        errors.addAll(settingsErrors(type, settings));
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
            String baseUrl = apiBaseUrl(typeName, settings);
            systems.insertDefaultEndpoint(id, code, name, baseUrl);
            if (baseUrl != null) {
                auditDestination(actorId, id, null, baseUrl);
            }
        }
        return view(new SystemDetail(id, code, name, directionDb, typeName,
                typeName == null ? null : settings.toString()));
    }

    /**
     * The delivery address of an API system: its {@code settings.baseUrl}, already schema-valid and past the destination
     * guard (the save is refused before this runs); {@code null} for any other type, so the endpoint keeps its
     * placeholder. The guard, the allow-list and the send-time checks are not touched.
     */
    private static String apiBaseUrl(String type, JsonNode settings) {
        JsonNode value = type == null || !type.equalsIgnoreCase("API") || settings == null ? null : settings.get("baseUrl");
        return value != null && value.isString() && !value.stringValue().isBlank() ? value.stringValue().trim() : null;
    }

    /** F-1 (CWE-778): one event per destination change - old and new HOST only, never path, query or userinfo (R14). */
    private void auditDestination(UUID actorId, String systemId, String before, String after) {
        UUID tenantId = tenant.get().orElseThrow(() -> new IllegalStateException("no tenant bound"));
        events.publishEvent(ConfigAuditActions.event(ConfigAuditActions.SYSTEM_DESTINATION_CHANGED, tenantId, actorId,
                List.of(ConfigAuditActions.target("system", systemId)),
                before == null ? null : ConfigAuditActions.state("host", hostOnly(before)),
                ConfigAuditActions.state("host", hostOnly(after))));
    }

    private static String hostOnly(String address) {
        try {
            String host = java.net.URI.create(address).getHost();
            return host == null ? "unknown" : host;
        } catch (IllegalArgumentException malformed) {
            return "unknown";
        }
    }

    private static JsonNode settingsJsonNode(String settings) {
        return settings == null ? null : JSON.readTree(settings);
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
    public Map<String, Object> update(String systemId, JsonNode body, UUID actorId) {
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
            errors.addAll(settingsErrors(typeNode, settingsNode));
            type = SystemSettings.typeOf(typeNode);
            settings = type == null ? null : settingsNode.toString();
        }
        if (!errors.isEmpty()) {
            throw new SystemValidationException(errors);
        }
        if (systems.updateSystem(current.id(), name, type, settings) != 1) {
            throw ConfigApiException.notFound();
        }
        // F-2: only a save that carried type/settings went through the guard above; a name-only save never writes
        // the endpoint, and an address the endpoint already holds is not written again
        String baseUrl = hasTypeOrSettings && current.direction().equals("TARGET")
                ? apiBaseUrl(type, settingsJsonNode(settings)) : null;
        if (baseUrl != null) {
            Optional<String> held = systems.findEndpointBaseUrl(current.id());
            if (held.isPresent() && !held.get().equals(baseUrl)) {
                systems.updateEndpointBaseUrl(current.id(), baseUrl);
                auditDestination(actorId, current.id(), held.get(), baseUrl);
            }
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

    /**
     * T178c-1: how many of these destination codes are stored FILE systems whose pattern lacks {@code {deliveryId}}.
     * A count only - no code, name or pattern leaves this method.
     */
    @Transactional(readOnly = true)
    public long countFileDestinationsWithoutDeliveryId(java.util.Collection<String> codes) {
        return codes.stream()
                .map(systems::findSystemByCode)
                .flatMap(Optional::stream)
                .map(row -> systems.findSystem(row.id()))
                .flatMap(Optional::stream)
                .filter(detail -> SystemSettings.storedFilePatternLacksDeliveryId(detail.type(), detail.settings()))
                .count();
    }

    private static final java.util.Set<String> AUTH_METHODS = java.util.Set.of(
            "NO_AUTH", "API_KEY", "BASIC", "BEARER_TOKEN", "OAUTH2_CLIENT_CREDENTIALS");

    @Transactional(readOnly = true)
    public Map<String, Object> readEndpointAuth(String systemId) {
        SystemDetail system = require(systemId);
        if (!system.direction().equals("TARGET")) {
            throw ConfigApiException.conflict("only an outbound system has a target endpoint");
        }
        JdbcSystemRepository.EndpointAuth auth = systems.findEndpointAuth(system.id())
                .orElseThrow(() -> ConfigApiException.conflict("this system has no target endpoint"));
        return endpointAuthView(auth);
    }

    @Transactional
    public Map<String, Object> updateEndpointAuth(String systemId, JsonNode body, UUID actorId) {
        SystemDetail system = require(systemId);
        if (!system.direction().equals("TARGET")) {
            throw ConfigApiException.conflict("only an outbound system has a target endpoint");
        }
        JdbcSystemRepository.EndpointAuth current = systems.findEndpointAuth(system.id())
                .orElseThrow(() -> ConfigApiException.conflict("this system has no target endpoint"));
        List<String> errors = new ArrayList<>();
        String authMethod = textOrNull(body, "authMethod");
        if (authMethod == null || !AUTH_METHODS.contains(authMethod)) {
            errors.add("authMethod must be one of " + AUTH_METHODS);
        }
        String authIdentity = textOrNull(body, "authIdentity");
        String authTokenUrl = textOrNull(body, "authTokenUrl");
        if (!"OAUTH2_CLIENT_CREDENTIALS".equals(authMethod)) {
            authTokenUrl = null; // a stale token URL never lingers behind another method
        }
        if (!"BASIC".equals(authMethod) && !"API_KEY".equals(authMethod)
                && !"OAUTH2_CLIENT_CREDENTIALS".equals(authMethod)) {
            authIdentity = null;
        }
        if ("BASIC".equals(authMethod) && (authIdentity == null || authIdentity.isBlank())) {
            errors.add("authIdentity (username) is required for BASIC auth");
        }
        if ("API_KEY".equals(authMethod) && (authIdentity == null || authIdentity.isBlank())) {
            errors.add("authIdentity (header name) is required for API_KEY auth");
        } else if ("API_KEY".equals(authMethod)) {
            ApiKeyHeaderName.problem(authIdentity).ifPresent(problem -> errors.add("authIdentity: " + problem));
        }
        if ("OAUTH2_CLIENT_CREDENTIALS".equals(authMethod)) {
            if (authIdentity == null || authIdentity.isBlank()) {
                errors.add("authIdentity (client ID) is required for OAUTH2_CLIENT_CREDENTIALS auth");
            }
            if (authTokenUrl == null || authTokenUrl.isBlank()) {
                errors.add("authTokenUrl is required for OAUTH2_CLIENT_CREDENTIALS auth");
            } else if (!tokenUrlAllowed(authTokenUrl)) {
                // row 510: the token URL receives the sealed client secret - the same destination rules as the base
                // URL, TLS required; the text repeats neither the host nor the reason
                errors.add("authTokenUrl is refused by the destination policy (an https address on an allow-listed"
                        + " host is required)");
            }
        }
        if (!errors.isEmpty()) {
            throw new SystemValidationException(errors);
        }
        if (systems.updateEndpointAuth(system.id(), authMethod, authIdentity, authTokenUrl) != 1) {
            throw ConfigApiException.notFound();
        }
        auditEndpointAuth(actorId, system.id(), current, authMethod, authIdentity, authTokenUrl);
        return endpointAuthView(new JdbcSystemRepository.EndpointAuth(
                current.endpointId(), authMethod, authIdentity, authTokenUrl));
    }

    private boolean tokenUrlAllowed(String tokenUrl) {
        try {
            guard.checkSecretReceiverAtSave(new java.net.URI(tokenUrl.strip()));
            return true;
        } catch (java.net.URISyntaxException | DestinationRefusedException refused) {
            return false;
        }
    }

    /** Row 510 (CWE-778): where and how a sealed secret is sent changed - method, identity and token HOST only. */
    private void auditEndpointAuth(UUID actorId, String systemId, JdbcSystemRepository.EndpointAuth before,
            String method, String identity, String tokenUrl) {
        UUID tenantId = tenant.get().orElseThrow(() -> new IllegalStateException("no tenant bound"));
        events.publishEvent(ConfigAuditActions.event(ConfigAuditActions.SYSTEM_ENDPOINT_AUTH_CHANGED, tenantId, actorId,
                List.of(ConfigAuditActions.target("system", systemId)),
                ConfigAuditActions.state("authMethod", before.authMethod(), "authIdentity", before.authIdentity(),
                        "tokenHost", before.authTokenUrl() == null ? null : hostOnly(before.authTokenUrl())),
                ConfigAuditActions.state("authMethod", method, "authIdentity", identity,
                        "tokenHost", tokenUrl == null ? null : hostOnly(tokenUrl))));
    }

    private static Map<String, Object> endpointAuthView(JdbcSystemRepository.EndpointAuth auth) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("endpointId", auth.endpointId());
        result.put("authMethod", auth.authMethod());
        result.put("authIdentity", auth.authIdentity());
        result.put("authTokenUrl", auth.authTokenUrl());
        return result;
    }

    private static String textOrNull(JsonNode body, String field) {
        if (body == null) return null;
        JsonNode value = body.get(field);
        if (value == null || value.isNull() || !value.isString()) return null;
        String text = value.stringValue().trim();
        return text.isEmpty() ? null : text;
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

    /** The type's schema errors; a schema-valid host field then goes through the destination guard (T-124). */
    private List<String> settingsErrors(JsonNode type, JsonNode settings) {
        List<String> schema = SystemSettings.validate(type, settings, allowKafkaPlaintext);
        String typeName = SystemSettings.typeOf(type);
        return !schema.isEmpty() || typeName == null ? schema
                : DestinationSettingsCheck.errors(typeName, settings, guard, fileRoots,
                        tenant.get().orElse(null));
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
