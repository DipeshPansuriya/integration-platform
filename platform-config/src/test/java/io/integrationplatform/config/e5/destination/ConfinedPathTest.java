package io.integrationplatform.config.e5.destination;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.destination.ConfinedPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Row 327 T97 (T-184, ADR-010 section 8 "File targets"): a File target's path is normalised and must stay under the
 * target's configured root after resolving {@code ..} and symbolic links (real-path prefix check); anything else is
 * refused. The E16 File adapter applies it; this is the one place the rule lives. Not tagged container (R03).
 */
class ConfinedPathTest {

    @TempDir
    Path workspace;

    @Test
    @DisplayName("A relative path under the root resolves to the real path inside the root")
    void aPathUnderTheRootResolves() throws IOException {
        Path root = Files.createDirectories(workspace.resolve("root"));
        Files.createDirectories(root.resolve("out"));

        assertThat(ConfinedPath.under(root, "out/./orders.csv"))
                .contains(root.toRealPath().resolve("out").resolve("orders.csv"));
        assertThat(ConfinedPath.under(root, "out/../in/new/orders.csv"))
                .as("a path that does not exist yet is checked through its nearest existing parent")
                .contains(root.toRealPath().resolve("in").resolve("new").resolve("orders.csv"));
    }

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {"../escape.csv", "out/../../escape.csv", "..", "a/b/../../../escape.csv"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("A ../ path that leaves the root is refused")
    void aDotDotEscapeIsRefused(String path) throws IOException {
        Path root = Files.createDirectories(workspace.resolve("root"));
        assertThat(ConfinedPath.under(root, path)).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("An absolute path outside the root is refused; blank and NUL paths are refused")
    void anAbsoluteOrMalformedPathIsRefused() throws IOException {
        Path root = Files.createDirectories(workspace.resolve("root"));
        Path outside = Files.createDirectories(workspace.resolve("outside"));

        assertThat(ConfinedPath.under(root, outside.resolve("x.csv").toString())).isEmpty();
        assertThat(ConfinedPath.under(root, " ")).isEmpty();
        assertThat(ConfinedPath.under(root, "a\u0000b")).isEmpty();
        assertThat(ConfinedPath.under(root, null)).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A symbolic link inside the root that points outside it is refused (real-path check)")
    void aSymlinkEscapeIsRefused() throws IOException {
        Path root = Files.createDirectories(workspace.resolve("root"));
        Path outside = Files.createDirectories(workspace.resolve("outside"));
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (IOException | UnsupportedOperationException noPrivilege) {
            Assumptions.abort("this machine cannot create a symbolic link: " + noPrivilege);
        }

        assertThat(ConfinedPath.under(root, "link/x.csv")).isEmpty();
    }
}
