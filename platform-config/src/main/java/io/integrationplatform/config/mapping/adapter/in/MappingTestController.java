package io.integrationplatform.config.mapping.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.mapping.application.MappingTestService;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The mapping test endpoint (E7 lane B2, T-109): {@code POST /api/v1/admin/mappings/{id}/test} - the mapping's
 * stored compiled expression runs on the posted sample and the sample run is recorded (MappingTestService). The
 * tenant never travels in the path (ADR-011 Decision 4); the endpoint is EDIT_CONFIGURATION
 * (MappingTestEndpointRoles) and another tenant's mapping answers 404, never 403.
 */
@RestController
@RequestMapping("/api/v1/admin/mappings")
public class MappingTestController {

    private final MappingTestService mappings;
    private final ConfigActors actors;

    public MappingTestController(MappingTestService mappings, ConfigActors actors) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    /** {@code body} is the whole post; the sample is its {@code sample} member (MappingTestService). */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String id,
            @RequestBody(required = false) JsonNode body) {
        actors.requireActor();
        return ResponseEntity.ok(mappings.test(id, body));
    }
}