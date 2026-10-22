package io.integrationplatform.config.e5.destination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.FileRoots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * E16 F4 (CWE-73/22): a FILE target's root folder is tenant-typed, so it is confined to the operator's base
 * directories, one sub-folder per tenant. Unit level on real temp folders; fail closed with no base configured.
 */
class FileRootsTest {

    @TempDir
    Path workspace;

    private final UUID tenant = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private Path base;
    private Path tenantRoot;
    private FileRoots roots;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectories(workspace.resolve("exchange"));
        tenantRoot = Files.createDirectories(base.resolve(tenant.toString()));
        roots = FileRoots.of(List.of(base.toString()));
    }

    @Test
    @Tag("security")
    @DisplayName("No base directory configured: every save and every send is refused (fail closed)")
    void noBaseDirRefusesEverything() {
        FileRoots none = FileRoots.none();

        assertThat(none.configured()).isFalse();
        assertThat(none.saveProblem(tenantRoot.toString(), tenant)).isPresent();
        assertThat(none.resolve(tenantRoot.toString(), tenant)).isEmpty();
        assertThat(FileRoots.of(List.of(" ", "")).configured()).isFalse();
    }

    @Test
    @DisplayName("A relative base directory is an operator misconfiguration and stops start-up")
    void relativeBaseDirIsRefused() {
        assertThatThrownBy(() -> FileRoots.of(List.of("relative/dir"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("The tenant's own folder and its sub-folders save and resolve to the real folder")
    void theTenantsFolderIsAccepted() throws IOException {
        Path out = Files.createDirectories(tenantRoot.resolve("out"));

        assertThat(roots.saveProblem(tenantRoot.toString(), tenant)).isEmpty();
        assertThat(roots.saveProblem(out.toString(), tenant)).isEmpty();
        assertThat(roots.resolve(out.toString(), tenant)).contains(out.toRealPath());
        assertThat(roots.resolve(tenantRoot.toString(), tenant)).contains(tenantRoot.toRealPath());
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A folder outside every base, the base itself, a sibling prefix and another tenant's folder are refused")
    void foldersOutsideTheTenantsRootAreRefused() throws IOException {
        Path elsewhere = Files.createDirectories(workspace.resolve("elsewhere"));
        Path prefixSibling = Files.createDirectories(workspace.resolve("exchange-evil").resolve(tenant.toString()));
        Path othersFolder = Files.createDirectories(base.resolve(other.toString()));

        for (Path bad : List.of(elsewhere, base, prefixSibling, othersFolder, workspace.getRoot())) {
            assertThat(roots.saveProblem(bad.toString(), tenant)).as("save " + bad).isPresent();
            assertThat(roots.resolve(bad.toString(), tenant)).as("send " + bad).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "out", "./out", "..", "a\u0000b"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("Blank, relative, NUL-carrying paths are refused at save and at send")
    void malformedPathsAreRefused(String path) {
        assertThat(roots.saveProblem(path, tenant)).as("save").isPresent();
        assertThat(roots.resolve(path, tenant)).as("send").isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A path with a .. segment is refused even when it normalises back inside the tenant's folder")
    void dotDotSegmentsAreRefused() throws IOException {
        Files.createDirectories(tenantRoot.resolve("a"));
        String escaping = tenantRoot + "/../" + other;
        String inside = tenantRoot + "/a/../a";

        for (String bad : List.of(escaping, inside, tenantRoot + "\\..\\x")) {
            assertThat(roots.saveProblem(bad, tenant)).as("save " + bad).isPresent();
            assertThat(roots.resolve(bad, tenant)).as("send " + bad).isEmpty();
        }
    }

    @Test
    @Tag("security")
    @DisplayName("A missing folder and a folder outside the roots give the same empty answer (no oracle)")
    void missingAndOutsideAreIndistinguishable() throws IOException {
        Path outside = Files.createDirectories(workspace.resolve("outside"));

        assertThat(roots.resolve(tenantRoot.resolve("absent").toString(), tenant)).isEmpty();
        assertThat(roots.resolve(outside.toString(), tenant)).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A link inside the tenant's folder pointing outside the base is refused at send")
    void aLinkOutOfTheBaseIsRefusedAtSend() throws IOException {
        Path outside = Files.createDirectories(workspace.resolve("outside"));
        Path link = tenantRoot.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException noLinks) {
            Assumptions.abort("this machine cannot create symbolic links: " + noLinks);
        }

        assertThat(roots.saveProblem(link.toString(), tenant)).as("lexically inside, so it saves").isEmpty();
        assertThat(roots.resolve(link.toString(), tenant)).as("the real path leaves the base").isEmpty();
    }

    @Test
    @Tag("security")
    @DisplayName("A second base directory is honoured; the tenant folder must exist under it")
    void severalBases() throws IOException {
        Path second = Files.createDirectories(workspace.resolve("second"));
        Path secondTenant = Files.createDirectories(second.resolve(tenant.toString()));
        FileRoots two = FileRoots.of(List.of(base.toString(), second.toString()));

        assertThat(two.saveProblem(secondTenant.toString(), tenant)).isEmpty();
        assertThat(two.resolve(secondTenant.toString(), tenant)).contains(secondTenant.toRealPath());
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("R11: base/<tenant id> that is a link to another tenant's folder no longer passes; the folder must BE the tenant's own")
    void aTenantFolderLinkToAnotherTenantsFolderIsRefused() throws IOException {
        UUID otherTenant = UUID.randomUUID();
        Files.createDirectories(base.resolve(otherTenant.toString()));
        Files.createDirectories(tenantRoot);
        Files.delete(tenantRoot);
        try {
            Files.createSymbolicLink(base.resolve(tenant.toString()), base.resolve(otherTenant.toString()));
        } catch (IOException | UnsupportedOperationException | SecurityException noLinks) {
            Assumptions.abort("this machine cannot create symbolic links: " + noLinks);
        }
        Path insideTheLink = base.resolve(tenant.toString()).resolve("out");

        assertThat(roots.resolve(insideTheLink.toString(), tenant))
                .as("the real folder belongs to another tenant: refused").isEmpty();
    }

    @Test
    @Tag("security")
    @DisplayName("No tenant bound: refused (fail closed)")
    void noTenantIsRefused() {
        assertThat(roots.saveProblem(tenantRoot.toString(), null)).isPresent();
        assertThat(roots.resolve(tenantRoot.toString(), null)).isEmpty();
    }
}
