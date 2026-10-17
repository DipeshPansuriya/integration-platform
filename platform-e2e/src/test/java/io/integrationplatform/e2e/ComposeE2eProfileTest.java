package io.integrationplatform.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * E15 seam (F17/T143) at the compose level, no Docker: the {@code e2e} profile adds Artemis and WireMock to
 * compose/compose.yaml without changing a plain {@code docker compose up}.
 * <ul>
 * <li>both services exist, carry {@code profiles: ["e2e"]}, use the {@link E2eImages} pins, run in UTC, publish
 * loopback-only ports and have a healthcheck;</li>
 * <li>{@code @security} Artemis credentials come from variable names only (R07) and anonymous login is off;</li>
 * <li>the default services (postgres, keycloak, kafka, kafbat) carry no profile, so {@code up} is unchanged.</li>
 * </ul>
 * ponytail: line-based service reading like KafbatComposeLayoutTest (platform-routing) - compose is flat and ours.
 */
class ComposeE2eProfileTest {

    private static final Path MODULE = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
    private static final Path REPO = MODULE.getParent();
    private static final Path COMPOSE = REPO.resolve("compose/compose.yaml");
    private static final Path ENV_EXAMPLE = REPO.resolve("compose/compose.env.example");
    private static final String E2E_PROFILE = "[\"e2e\"]";

    @Test
    void artemisIsAnE2eProfileServicePinnedLikeTheHarness() throws IOException {
        Service artemis = Service.of(COMPOSE, "artemis");

        assertThat(artemis.value("profiles")).as("artemis runs only with --profile e2e").isEqualTo(E2E_PROFILE);
        assertThat(artemis.value("image")).as("compose artemis image").isEqualTo(E2eImages.ARTEMIS);
        assertThat(artemis.nestedMap("environment")).containsEntry("TZ", "Etc/UTC");
        assertThat(artemis.nestedList("ports")).containsExactly("\"127.0.0.1:61616:61616\"", "\"127.0.0.1:8161:8161\"");
        assertThat(artemis.nestedMap("healthcheck")).as("artemis has a healthcheck").containsKey("test");
        assertThat(Duration.parse("PT" + artemis.nestedMap("healthcheck").get("start_period").toUpperCase()))
                .as("the broker gets at least 30 s to boot before failed checks count").isGreaterThanOrEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void artemisCredentialsComeFromVariableNamesOnlyAndAnonymousLoginIsOff() throws IOException {
        Map<String, String> env = Service.of(COMPOSE, "artemis").nestedMap("environment");

        assertThat(env).containsEntry("ARTEMIS_USER", "${ARTEMIS_USER:?set ARTEMIS_USER}");
        assertThat(env).containsEntry("ARTEMIS_PASSWORD", "${ARTEMIS_PASSWORD:?set ARTEMIS_PASSWORD}");
        assertThat(env).containsEntry("ANONYMOUS_LOGIN", "\"false\"");

        List<String> example = Files.readAllLines(ENV_EXAMPLE);
        assertThat(example).as("compose.env.example holds the Artemis names with empty values")
                .contains("ARTEMIS_USER=", "ARTEMIS_PASSWORD=");
    }

    @Test
    void wiremockIsAnE2eProfileServicePinnedLikeTheHarness() throws IOException {
        Service wiremock = Service.of(COMPOSE, "wiremock");

        assertThat(wiremock.value("profiles")).as("wiremock runs only with --profile e2e").isEqualTo(E2E_PROFILE);
        assertThat(wiremock.value("image")).as("compose wiremock image").isEqualTo(E2eImages.WIREMOCK);
        assertThat(wiremock.nestedMap("environment")).containsEntry("TZ", "Etc/UTC");
        assertThat(wiremock.nestedList("ports")).containsExactly("\"127.0.0.1:8089:8080\"");
        assertThat(wiremock.nestedMap("healthcheck").get("test")).as("wiremock healthcheck reads its admin health")
                .contains("http://localhost:8080/__admin/health");
    }

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "keycloak", "kafka", "kafbat"})
    void theDefaultServicesCarryNoProfileSoPlainUpIsUnchanged(String name) throws IOException {
        assertThat(Service.of(COMPOSE, name).value("profiles")).as(name + " has no profiles line").isNull();
    }

    /** One compose service's lines, read flat (the same shape KafbatComposeLayoutTest reads). */
    private static final class Service {

        private final List<String> lines = new ArrayList<>();

        static Service of(Path composeFile, String name) throws IOException {
            List<String> all = Files.readAllLines(composeFile);
            int at = all.indexOf("  " + name + ":");
            assertThat(at).as("compose declares the service " + name).isNotNegative();
            Service service = new Service();
            for (int i = at + 1; i < all.size(); i++) {
                String line = all.get(i);
                if (!line.startsWith("    ") && !line.isBlank() && !line.strip().startsWith("#")) {
                    break; // the next service or a top-level section ends this block
                }
                service.lines.add(line);
            }
            return service;
        }

        /** The value of a one-level {@code key: value} entry, or null when the service has no such key. */
        String value(String key) {
            String prefix = "    " + key + ": ";
            return lines.stream().filter(line -> line.startsWith(prefix))
                    .map(line -> line.substring(prefix.length()).strip()).findFirst().orElse(null);
        }

        List<String> nestedList(String block) {
            List<String> items = new ArrayList<>();
            for (String line : block(block)) {
                if (line.startsWith("      - ")) {
                    items.add(line.substring("      - ".length()).strip());
                }
            }
            return items;
        }

        Map<String, String> nestedMap(String block) {
            Map<String, String> entries = new LinkedHashMap<>();
            for (String line : block(block)) {
                if (line.startsWith("      ") && !line.startsWith("        ") && !line.strip().startsWith("#")) {
                    String body = line.substring(6);
                    int colon = body.indexOf(':');
                    if (colon > 0) {
                        entries.put(body.substring(0, colon).strip(), body.substring(colon + 1).strip());
                    }
                }
            }
            return entries;
        }

        private List<String> block(String block) {
            List<String> inside = new ArrayList<>();
            boolean in = false;
            for (String line : lines) {
                if (line.startsWith("    ") && !line.startsWith("     ")) {
                    in = line.equals("    " + block + ":");
                    continue;
                }
                if (in) {
                    inside.add(line);
                }
            }
            return inside;
        }
    }
}
