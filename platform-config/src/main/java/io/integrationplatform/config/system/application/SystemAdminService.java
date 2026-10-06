package io.integrationplatform.config.system.application;

import io.integrationplatform.config.application.ConfigApiException;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository;
import io.integrationplatform.config.internal.persistence.JdbcSystemRepository.SystemRow;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * System registration (T-015's onboarding premise): a row in {@code system}, and for an OUTBOUND system the one
 * default target endpoint its flows route through (E5 replaces its placeholder settings; the flow API only needs
 * the endpoint row to exist). The API's INBOUND/OUTBOUND vocabulary maps to the schema's SOURCE/TARGET closed set
 * at this boundary and the answer echoes the API vocabulary back.
 */
public class SystemAdminService {

    private final JdbcSystemRepository systems;

    public SystemAdminService(JdbcSystemRepository systems) {
        this.systems = Objects.requireNonNull(systems, "systems");
    }

    @Transactional
    public SystemRow create(JsonNode body) {
        String code = required(body, "code");
        String name = required(body, "name");
        String direction = required(body, "direction");
        if (!direction.equals("INBOUND") && !direction.equals("OUTBOUND")) {
            throw ConfigApiException.validation("direction must be INBOUND or OUTBOUND");
        }
        if (systems.findSystemByCode(code).isPresent()) {
            throw ConfigApiException.conflict("a system with code " + code + " is already registered");
        }
        String id = systems.insertSystem(code, name, JdbcSystemRepository.directionToDb(direction));
        if (direction.equals("OUTBOUND")) {
            systems.insertDefaultEndpoint(id, code, name);
        }
        return new SystemRow(id, code, name, direction);
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
}
