package io.integrationplatform.archtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
    void aBrandNewModuleMissingFromArchTestFailsTheGuard() {
        List<String> modules = List.of("platform-routing", "platform-tenancy", "platform-brandnew", "arch-test");
        Set<String> dependencies = Set.of("platform-routing", "platform-tenancy");

        assertThat(ModuleScopeGuard.missingFromScope(modules, dependencies)).containsExactly("platform-brandnew");
    }
}
