package io.integrationplatform.routing.adapter.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * T-150: test-connection for a FILE system, answered from the registry - the folder must exist and be
 * writable; a failed answer is a FAILED answer with a reason, never an exception.
 */
public class FileTestConnection {

    /** Probe = take and remove one file in the folder; any failure means the folder is not writable. */
    @FunctionalInterface
    public interface Probe {
        void probe(Path directory) throws IOException;
    }

    private final Function<UUID, Optional<FileSettings>> registry;
    private final Probe probe;

    public FileTestConnection(Function<UUID, Optional<FileSettings>> registry) {
        this(registry, FileTestConnection::defaultProbe);
    }

    public FileTestConnection(Function<UUID, Optional<FileSettings>> registry, Probe probe) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    static void defaultProbe(Path directory) throws IOException {
        Path probe = directory.resolve("test-connection-" + UUID.randomUUID() + ".probe");
        Files.write(probe, new byte[0]);
        Files.deleteIfExists(probe);
    }

    public Map<String, Object> test(UUID systemId) {
        Optional<FileSettings> settings;
        try {
            settings = registry.apply(systemId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return answer(systemId, "FAILED", "BAD_SETTINGS", 0, e.getMessage());
        }
        if (settings.isEmpty()) {
            return answer(systemId, "FAILED", "NOT_FOUND", 0, "no FILE system with this id");
        }
        Path directory = Path.of(settings.get().directory());
        if (!Files.isDirectory(directory)) {
            return answer(systemId, "FAILED", "NOT_A_DIRECTORY", 0,
                    "'" + settings.get().directory() + "' is not a reachable folder");
        }
        long start = System.nanoTime();
        try {
            probe.probe(directory);
        } catch (IOException unwritable) {
            return answer(systemId, "FAILED", "DIRECTORY_UNWRITABLE", elapsedMs(start),
                    "the folder could not take and remove a probe file");
        }
        return answer(systemId, "SUCCESS", null, elapsedMs(start), "the folder took and removed a probe file");
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static Map<String, Object> answer(UUID systemId, String result, String reason, long ms, String message) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("systemId", systemId.toString());
        answer.put("type", "FILE");
        answer.put("result", result);
        answer.put("httpStatus", null);
        answer.put("responseTimeMs", ms);
        answer.put("reason", reason);
        answer.put("detail", null);
        answer.put("message", message);
        return answer;
    }
}