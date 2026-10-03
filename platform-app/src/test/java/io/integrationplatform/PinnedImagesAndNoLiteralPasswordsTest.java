package io.integrationplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Scenario "Compose holds no literal password and no floating tag" (@negative @security, R05, R07).
 * Not tagged container: runs in a bare ./mvnw test without Docker.
 */
class PinnedImagesAndNoLiteralPasswordsTest {

    private static final Path MODULE = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
    private static final Path REPO = MODULE.getParent();
    private static final Path COMPOSE = REPO.resolve("compose/compose.yaml");
    private static final Path ENV_EXAMPLE = REPO.resolve("compose/compose.env.example");
    private static final Path TEST_JAVA = MODULE.resolve("src/test/java");

    @Test
    void composeImagesArePinnedAndPasswordsComeFromVariables() throws IOException {
        assertThat(COMPOSE).as("compose file").exists();
        String compose = Files.readString(COMPOSE);

        assertThat(compose).as("compose names at least one image").contains("image:");
        assertThat(ImagePinAndSecretRules.checkCompose(COMPOSE.toString(), compose)).isEmpty();
    }

    @Test
    void composeUsesTheSamePostgresTagAsTheTests() throws IOException {
        assertThat(Files.readString(COMPOSE)).contains("image: " + TestImages.POSTGRES);
    }

    @Test
    void envExampleHoldsVariableNamesOnly() throws IOException {
        assertThat(ENV_EXAMPLE).as("compose env example").exists();
        String example = Files.readString(ENV_EXAMPLE);

        assertThat(example).contains("POSTGRES_PASSWORD=");
        assertThat(ImagePinAndSecretRules.checkEnvExample(ENV_EXAMPLE.toString(), example)).isEmpty();
    }

    @Test
    void everyTestcontainersImageReferenceIsPinned() throws IOException {
        List<String> findings = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(TEST_JAVA)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                findings.addAll(ImagePinAndSecretRules.checkJavaImageReferences(file.toString(), Files.readString(file)));
                scanned++;
            }
        }
        assertThat(scanned).isPositive();
        assertThat(findings).isEmpty();
    }

    @Test
    void theTestImageConstantIsPinnedAndRecognised() {
        String pinned = "public static final String POSTGRES = \"" + TestImages.POSTGRES + "\";";
        String floating = "public static final String POSTGRES = \"" + TestImages.POSTGRES.replaceAll(":.*", ":17") + "\";";

        assertThat(ImagePinAndSecretRules.checkJavaImageReferences("TestImages.java", pinned)).isEmpty();
        assertThat(ImagePinAndSecretRules.checkJavaImageReferences("TestImages.java", floating)).isNotEmpty();
    }

    // R02 planted violations: in-memory only, no bad file is committed.

    @ParameterizedTest
    @ValueSource(strings = {
        "services:\n  db:\n    image: postgres:latest\n",
        "services:\n  db:\n    image: postgres:17\n",
        "services:\n  db:\n    image: postgres:17-alpine\n",
        "services:\n  db:\n    image: postgres\n",
        "services:\n  db:\n    image: \"postgres:latest\"\n",
    })
    void plantedFloatingComposeTagIsFlagged(String compose) {
        assertThat(ImagePinAndSecretRules.checkCompose("planted", compose)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "services:\n  db:\n    image: postgres:17.11-alpine\n    environment:\n      POSTGRES_PASSWORD: secret\n",
        "services:\n  db:\n    image: postgres:17.11-alpine\n    environment:\n      - POSTGRES_PASSWORD=secret\n",
        "services:\n  db:\n    image: postgres:17.11-alpine\n    environment:\n      DB_PASSWORD: \"changeit\"\n",
        "services:\n  app:\n    image: postgres:17.11-alpine\n    environment:\n      JWT_SECRET: abc123\n",
        "services:\n  db:\n    image: postgres:17.11-alpine\n    environment:\n      POSTGRES_PASSWORD: pre${POSTGRES_PASSWORD}\n",
    })
    void plantedLiteralPasswordIsFlagged(String compose) {
        assertThat(ImagePinAndSecretRules.checkCompose("planted", compose)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "static final String PG = \"postgres:latest\";",
        "static final String PG = \"postgres:17\";",
        "var c = new PostgreSQLContainer(\"postgres:17-alpine\");",
        "var c = new PostgreSQLContainer(DockerImageName.parse(\"postgres\"));",
        "var c = new GenericContainer<>(\"redis:7\");",
    })
    void plantedFloatingTestImageIsFlagged(String java) {
        assertThat(ImagePinAndSecretRules.checkJavaImageReferences("planted", java)).isNotEmpty();
    }

    @Test
    void plantedValueInEnvExampleIsFlagged() {
        assertThat(ImagePinAndSecretRules.checkEnvExample("planted", "POSTGRES_PASSWORD=secret\n")).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "services:\n  db:\n    image: postgres:17.11-alpine\n    environment:\n      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD}\n",
        "services:\n  db:\n    image: postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24\n",
        "services:\n  db:\n    image: postgres:17.11\n    environment:\n      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}\n",
    })
    void pinnedImageAndVariablePasswordPass(String compose) {
        assertThat(ImagePinAndSecretRules.checkCompose("good", compose)).isEmpty();
    }
}
