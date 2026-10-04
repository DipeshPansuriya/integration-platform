package io.integrationplatform.archtest;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Compares the parent pom's modules with arch-test's project dependencies. RED SKELETON - not implemented yet. */
final class ModuleScopeGuard {

    private ModuleScopeGuard() {
    }

    static List<String> modulesOf(Path parentPom) {
        return List.of();
    }

    static Set<String> projectDependenciesOf(Path archTestPom) {
        return Set.of();
    }

    static List<String> missingFromScope(List<String> modules, Set<String> dependencies) {
        return List.of();
    }
}
