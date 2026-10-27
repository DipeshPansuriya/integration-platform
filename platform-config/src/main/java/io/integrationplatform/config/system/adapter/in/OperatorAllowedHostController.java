package io.integrationplatform.config.system.adapter.in;

import io.integrationplatform.config.adapter.in.ConfigActors;
import io.integrationplatform.config.system.application.OperatorAllowedHostService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OperatorAllowedHostController.PATH)
public class OperatorAllowedHostController {

    static final String PATH = "/api/v1/admin/settings/allowed-hosts";

    private final OperatorAllowedHostService service;
    private final ConfigActors actors;

    public OperatorAllowedHostController(OperatorAllowedHostService service, ConfigActors actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        actors.requireActor();
        return ResponseEntity.ok(Map.of("hosts", service.list()));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, String> body) {
        actors.requireActor();
        String pattern = body.getOrDefault("pattern", "").strip();
        if (pattern.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "pattern is required"));
        }
        List<String> updated = service.add(pattern);
        return ResponseEntity.ok(Map.of("hosts", updated));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> remove(@RequestBody Map<String, String> body) {
        actors.requireActor();
        String pattern = body.getOrDefault("pattern", "").strip();
        if (pattern.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "pattern is required"));
        }
        List<String> updated = service.remove(pattern);
        return ResponseEntity.ok(Map.of("hosts", updated));
    }
}
