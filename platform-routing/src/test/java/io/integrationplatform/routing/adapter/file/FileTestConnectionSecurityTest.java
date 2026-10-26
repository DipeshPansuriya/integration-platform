package io.integrationplatform.routing.adapter.file;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.destination.FileRoots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * E16 F4: test-connection must not be a file-system oracle - the probe runs only inside the tenant's allowed folder,
 * and "outside the allowed roots" answers exactly as "not reachable" does.
 */
class FileTestConnectionSecurityTest {

    @TempDir
    Path workspace;

    private final UUID tenant = UUID.randomUUID();

    private Map<String, Object> test(FileRoots roots, String rootPath, List<Path> probed) {
        return new FileTestConnection(id -> Optional.of(new FileSettings(rootPath)),
                root -> roots.resolve(root, tenant), directory -> {
                    probed.add(directory);
                    FileTestConnection.defaultProbe(directory);
                }).test(UUID.randomUUID());
    }

    @Test
    @Tag("security")
    @DisplayName("F4: a folder outside the allowed roots is never probed and answers exactly like a missing folder")
    void outsideAndMissingAnswerTheSame() throws IOException {
        Path base = Files.createDirectories(workspace.resolve("exchange"));
        Path tenantRoot = Files.createDirectories(base.resolve(tenant.toString()));
        Path outside = Files.createDirectories(workspace.resolve("outside"));
        FileRoots roots = FileRoots.of(List.of(base.toString()));
        List<Path> probed = new ArrayList<>();

        Map<String, Object> outsideAnswer = test(roots, outside.toString(), probed);
        Map<String, Object> missingAnswer = test(roots, tenantRoot.resolve("absent").toString(), probed);

        assertThat(probed).as("nothing is created or deleted outside the allowed folder").isEmpty();
        assertThat(outsideAnswer.get("result")).isEqualTo("FAILED");
        assertThat(outsideAnswer.get("reason")).isEqualTo("NOT_A_DIRECTORY");
        assertThat(withoutIds(outsideAnswer)).as("no oracle: identical answers").isEqualTo(withoutIds(missingAnswer));
        assertThat(String.valueOf(outsideAnswer.get("message"))).doesNotContain(outside.toString())
                .doesNotContain("outside");
        try (var files = Files.list(outside)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    @Tag("security")
    @DisplayName("With no base configured the probe never runs; inside the tenant's folder it does")
    void probeRunsOnlyInsideAnAllowedFolder() throws IOException {
        Path base = Files.createDirectories(workspace.resolve("exchange"));
        Path tenantRoot = Files.createDirectories(base.resolve(tenant.toString()));
        List<Path> probed = new ArrayList<>();

        assertThat(test(FileRoots.none(), tenantRoot.toString(), probed).get("result")).isEqualTo("FAILED");
        assertThat(probed).isEmpty();

        Map<String, Object> ok = test(FileRoots.of(List.of(base.toString())), tenantRoot.toString(), probed);
        assertThat(ok.get("result")).isEqualTo("SUCCESS");
        assertThat(probed).containsExactly(tenantRoot.toRealPath());
    }

    private static Map<String, Object> withoutIds(Map<String, Object> answer) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(answer);
        copy.remove("systemId");
        copy.remove("responseTimeMs");
        return copy;
    }
}
