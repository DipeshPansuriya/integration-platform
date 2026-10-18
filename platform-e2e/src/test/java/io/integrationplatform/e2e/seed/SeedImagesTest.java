package io.integrationplatform.e2e.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Row 327 T157: the seed's container test runs the same postgres and keycloak tags as compose (no drift). */
class SeedImagesTest {

    private static final Path COMPOSE = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize()
            .getParent().resolve("compose/compose.yaml");

    @Test
    void theSeedContainersUseComposesPostgresAndKeycloakTags() throws IOException {
        List<String> lines = Files.readAllLines(COMPOSE);

        assertThat(imageOf(lines, "postgres")).isEqualTo(TenantBSeedContainerTest.POSTGRES_IMAGE);
        assertThat(imageOf(lines, "keycloak")).isEqualTo(TenantBSeedContainerTest.KEYCLOAK_IMAGE);
    }

    private static String imageOf(List<String> lines, String service) {
        int at = lines.indexOf("  " + service + ":");
        assertThat(at).as("compose service %s", service).isNotNegative();
        for (String line : lines.subList(at + 1, lines.size())) {
            if (line.startsWith("    image: ")) {
                return line.substring("    image: ".length()).strip();
            }
        }
        throw new AssertionError("compose service " + service + " has no image line");
    }
}
