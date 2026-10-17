package io.integrationplatform.archtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Scope guard for E1-F2-S1, example "native SQL string | a brand-new module" (R08: the deleted build's rule missed a
 * class in a package outside its scope). The ArchUnit rules see a module's classes only when arch-test depends on that
 * module, so every production module listed in the parent pom must be an arch-test dependency - a brand-new module can
 * never fall out of scope silently.
 */
class ModuleScopeGuardTest {

    private static final Path PARENT_POM = Path.of("..", "pom.xml");
    private static final Path ARCH_TEST_POM = Path.of("pom.xml");

    @Test
    void everyProductionModuleOfTheParentPomIsInArchTestScope() {
        List<String> modules = ModuleScopeGuard.modulesOf(PARENT_POM);
        Set<String> dependencies = ModuleScopeGuard.projectDependenciesOf(ARCH_TEST_POM);

        assertThat(modules).as("parent pom modules").contains("platform-tenancy", "platform-app", "arch-test");
        assertThat(ModuleScopeGuard.missingFromScope(modules, dependencies))
                .as("production modules arch-test does not depend on (add them to arch-test/pom.xml)")
                .isEmpty();
    }

    @Test
    void theTestOnlyE2eModuleIsNeverReportedMissing() {
        // E15 seam (F17/T142): platform-e2e holds test sources only (black-box harness), so arch-test has nothing to scan.
        List<String> modules = List.of("platform-routing", "platform-e2e", "arch-test");
        Set<String> dependencies = Set.of("platform-routing");

        assertThat(ModuleScopeGuard.missingFromScope(modules, dependencies)).isEmpty();
    }

    @Test
    void everyTestOnlyModuleHoldsNoProductionSources() {
        // Row 327 T151: the guard skips TEST_ONLY_MODULES, which is safe only while none of them grows a src/main
        // (moved here from platform-e2e's own TestOnlyModuleTest so one place checks every test-only module).
        assertThat(ModuleScopeGuard.TEST_ONLY_MODULES).contains("arch-test", "platform-e2e");
        for (String module : ModuleScopeGuard.TEST_ONLY_MODULES) {
            assertThat(Path.of("..", module, "pom.xml")).as(module + " is a module of this build").exists();
        }
        assertThat(ModuleScopeGuard.testOnlyModulesWithProductionSources(Path.of("..")))
                .as("test-only modules that hold a src/main").isEmpty();
    }

    @Test
    void aTestOnlyModuleThatGrowsASrcMainFailsTheGuard(@TempDir Path root) throws IOException {
        // Row 327 T155 (C5): the permanent form of T151's manual proof (a temporary platform-e2e/src/main).
        for (String module : ModuleScopeGuard.TEST_ONLY_MODULES) {
            Files.createDirectories(root.resolve(module).resolve("src/test/java"));
        }
        Files.createDirectories(root.resolve("platform-e2e/src/main/java"));

        assertThat(ModuleScopeGuard.testOnlyModulesWithProductionSources(root)).containsExactly("platform-e2e");
    }

    @Test
    void aBrandNewModuleMissingFromArchTestFailsTheGuard() {
        List<String> modules = List.of("platform-routing", "platform-tenancy", "platform-brandnew", "arch-test");
        Set<String> dependencies = Set.of("platform-routing", "platform-tenancy");

        assertThat(ModuleScopeGuard.missingFromScope(modules, dependencies)).containsExactly("platform-brandnew");
    }
}
