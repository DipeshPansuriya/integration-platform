package io.integrationplatform.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * E15 seam (F17/T142): platform-e2e is a test-only module, which is why arch-test's ModuleScopeGuard lists it in
 * TEST_ONLY_MODULES and does not scan it. The claim stays true only while the module has no production sources.
 */
class TestOnlyModuleTest {

    private static final Path MODULE = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();

    @Test
    void theModuleHasNoProductionSources() {
        assertThat(MODULE.resolve("pom.xml")).as("running inside the platform-e2e module").exists();
        assertThat(MODULE.resolve("src/main")).as("platform-e2e holds test sources only (black-box harness)")
                .doesNotExist();
    }
}
