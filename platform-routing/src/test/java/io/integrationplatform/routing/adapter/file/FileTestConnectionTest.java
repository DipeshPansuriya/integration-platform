package io.integrationplatform.routing.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-150: test-connection = the folder is reachable AND writable, answered as a reason-carrying map. */
class FileTestConnectionTest {

    private static final FolderResolver FOLDERS =
            root -> Files.isDirectory(Path.of(root)) ? Optional.of(Path.of(root)) : Optional.empty();

    @TempDir
    Path folder;

    private Map<String, Object> test(Function<UUID, Optional<FileSettings>> registry) throws IOException {
        return test(registry, FileTestConnection::defaultProbe);
    }

    private Map<String, Object> test(Function<UUID, Optional<FileSettings>> registry,
            FileTestConnection.Probe probe) throws IOException {
        Map<String, Object> answer = new FileTestConnection(registry, FOLDERS, probe).test(UUID.randomUUID());
        try (var files = Files.list(folder)) {
            List<String> leftovers = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.contains("test-connection-")).collect(Collectors.toList());
            assertThat(leftovers).as("a probe file is always cleaned up").isEmpty();
        }
        return answer;
    }

    @Test
    void aReachableWritableFolderAnswersSuccessAndCleansItsProbe() throws IOException {
        Map<String, Object> answer = test(id -> Optional.of(new FileSettings(folder.toString())));

        assertThat(answer.get("result")).isEqualTo("SUCCESS");
        assertThat(answer.get("type")).isEqualTo("FILE");
        assertThat(String.valueOf(answer.get("message"))).contains("probe file");
    }

    @Test
    void aMissingFolderFailsWithoutException() throws IOException {
        Map<String, Object> answer = test(id -> Optional.of(new FileSettings(folder.resolve("absent").toString())));

        assertThat(answer.get("result")).isEqualTo("FAILED");
        assertThat(answer.get("reason")).isEqualTo("NOT_A_DIRECTORY");
    }

    @Test
    void anUnwritableFolderFailsAsDirectoryUnwritable() throws IOException {
        Map<String, Object> answer = test(id -> Optional.of(new FileSettings(folder.toString())),
                directory -> {
                    throw new AccessDeniedException(directory.toString());
                });

        assertThat(answer.get("result")).isEqualTo("FAILED");
        assertThat(answer.get("reason")).isEqualTo("DIRECTORY_UNWRITABLE");
    }

    @Test
    void badSettingsAreABadSettingsAnswerNotAnException() throws IOException {
        Map<String, Object> answer = test(id -> {
            throw new IllegalStateException("File settings need directory");
        });

        assertThat(answer.get("result")).isEqualTo("FAILED");
        assertThat(answer.get("reason")).isEqualTo("BAD_SETTINGS");
    }
}
