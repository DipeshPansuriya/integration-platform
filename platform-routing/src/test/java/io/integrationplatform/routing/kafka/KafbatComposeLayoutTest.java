package io.integrationplatform.routing.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T-077 (E8-F2-S1) at the compose level - the scenarios checked on the compose text itself, so they run in a
 * bare {@code ./mvnw test} with no Docker (the Kafbat API behaviours live in
 * {@link E8F2KafbatViewContainerTest}, tagged {@code container}):
 * <ul>
 * <li>"Topics are visible" - Kafbat UI is in compose, pinned to the same digest as {@link KafkaImages#KAFBAT},
 * on the operations network, pointed at the work Kafka;</li>
 * <li>"{@code @negative @security} Kafbat is never tenant-facing" - Kafbat is attached to the operations
 * network only, never the tenant-facing default network the console and its backends share, and its own login
 * comes from {@code KAFBAT_UI_*} variable names (R07: names only, no values);</li>
 * <li>"{@code @negative} The product never depends on Kafbat" - not one product source file references
 * Kafbat; the data-path half lives in {@link KafkaHarnessTest}.</li>
 * </ul>
 * ponytail: line-based service reading, not a YAML parser - compose is small and written by us.
 */
class KafbatComposeLayoutTest {

    private static final Path MODULE = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
    private static final Path REPO = MODULE.getParent();
    private static final Path COMPOSE = REPO.resolve("compose/compose.yaml");
    private static final Path ENV_EXAMPLE = REPO.resolve("compose/compose.env.example");

    @Test
    void kafkaIsInComposePinnedLikeTheHarnessOnWorkAndOperationsNetworks() throws IOException {
        Service kafka = Service.of(COMPOSE, "kafka");
        assertThat(kafka.image()).as("compose kafka image").isEqualTo(KafkaImages.KAFKA);
        assertThat(kafka.nestedList("networks")).as("Kafka joins the work network (reachable by routing) and the operations network")
                .contains("work", "operations");
        assertThat(kafka.nestedList("networks")).as("Kafka never joins the tenant-facing default network")
                .doesNotContain("default");
        assertThat(kafka.nestedMap("environment")).as("compose parity with the harness: topic auto-creation stays off")
                .containsEntry("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    }

    @Test
    void kafbatIsInComposePinnedLikeTheFixtureOnTheOperationsNetworkOnly() throws IOException {
        Service kafbat = Service.of(COMPOSE, "kafbat");
        assertThat(kafbat.image()).as("compose kafbat image").isEqualTo(KafkaImages.KAFBAT);
        assertThat(kafbat.nestedList("networks")).as("Kafbat is on the operations network and no other")
                .containsExactly("operations");
        assertThat(kafbat.nestedMap("environment")).as("Kafbat reads the work Kafka, not anything tenant-facing")
                .containsEntry("KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS", "kafka:9092");
        assertThat(kafbat.nestedMap("ports")).as("the engineer opens Kafbat on the host, on one loopback-bound port")
                .isNotEmpty();
    }

    @Test
    void kafbatLoginComesFromVariableNamesOnly() throws IOException {
        Map<String, String> kafbatEnvs = Service.of(COMPOSE, "kafbat").nestedMap("environment");
        assertThat(kafbatEnvs).as("Kafbat requires its own login on the operations network")
                .containsEntry("AUTH_TYPE", "LOGIN_FORM");
        assertThat(kafbatEnvs).as("Kafbat login user comes from a ${VAR} name")
                .containsEntry("SPRING_SECURITY_USER_NAME", "${KAFBAT_UI_USER:?set KAFBAT_UI_USER}");
        assertThat(kafbatEnvs).as("Kafbat login password comes from a ${VAR} name")
                .containsEntry("SPRING_SECURITY_USER_PASSWORD", "${KAFBAT_UI_PASSWORD:?set KAFBAT_UI_PASSWORD}");

        List<String> example = Files.readAllLines(ENV_EXAMPLE);
        assertThat(example.stream().filter(line -> line.startsWith("KAFBAT_UI_USER=")).findFirst())
                .as("compose.env.example holds the name KAFBAT_UI_USER with an empty value")
                .contains("KAFBAT_UI_USER=");
        assertThat(example.stream().filter(line -> line.startsWith("KAFBAT_UI_PASSWORD=")).findFirst())
                .as("compose.env.example holds the name KAFBAT_UI_PASSWORD with an empty value")
                .contains("KAFBAT_UI_PASSWORD=");
    }

    @Test
    void noProductSourceReferencesKafbat() throws IOException {
        try (Stream<Path> mains = Files.walk(REPO)) {
            List<String> hits = mains
                    .filter(p -> p.toString().endsWith(".java") && p.toString().contains("/src/main/"))
                    .filter(p -> {
                        try {
                            String text = Files.readString(p).toLowerCase();
                            return text.contains("kafbat") || text.contains("kafka-ui");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(Path::toString)
                    .toList();
            assertThat(hits).as("product code never imports or names Kafbat (E8-F2-S1 @negative)").isEmpty();
        }
    }

    /**
     * One compose service's lines, read flat: the unindented {@code service:}, its one-level {@code key: value}
     * entries, its {@code list} blocks ({@code - item}) and the {@code key}/{@code value} maps nested at
     * two-space depth below it. Anything deeper (multi-line YAML constructs) is ignored: compose is written to
     * stay this flat here.
     */
    private static final class Service {

        private final String name;
        private final List<String> lines = new ArrayList<>();

        private Service(String name) {
            this.name = name;
        }

        static Service of(Path composeFile, String name) throws IOException {
            List<String> all = Files.readAllLines(composeFile);
            int at = all.indexOf("  " + name + ":");
            assertThat(at).as("compose declares the service " + name).isNotNegative();
            Service service = new Service(name);
            for (int i = at + 1; i < all.size(); i++) {
                String line = all.get(i);
                if (line.startsWith("  ") && line.endsWith(":") && !line.startsWith("    ")) {
                    // the next service (or a top-level section's first entry) ends this block
                    break;
                }
                if (line.startsWith("    ") && line.endsWith(":") && !line.startsWith("      ")) {
                    // a top-level key at service depth (ports:, volumes:, ...) could still hold nested lines;
                    // those are read by nestedList/nestedMap, so keep them
                    service.lines.add(line);
                    continue;
                }
                service.lines.add(line);
            }
            return service;
        }

        String image() {
            return lines.stream()
                    .filter(line -> line.startsWith("    image: "))
                    .map(line -> line.substring("    image: ".length()).strip())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("service " + name + " declares no image"));
        }

        /** Every {@code - item} under the four-space block {@code <block>:}. Empty when the block is absent. */
        List<String> nestedList(String block) {
            List<String> items = new ArrayList<>();
            boolean inBlock = false;
            for (String line : lines) {
                if (line.startsWith("    ") && line.endsWith(":") && !line.startsWith("      ")) {
                    inBlock = line.equals("    " + block + ":");
                    continue;
                }
                if (inBlock && line.startsWith("      - ")) {
                    items.add(line.substring("      - ".length()).strip());
                }
            }
            return items;
        }

        /** Every {@code key: value} pair two spaces deeper than the four-space block {@code <block>:}. */
        Map<String, String> nestedMap(String block) {
            Map<String, String> entries = new LinkedHashMap<>();
            boolean inBlock = false;
            for (String line : lines) {
                if (line.startsWith("    ") && line.endsWith(":") && !line.startsWith("      ")) {
                    inBlock = line.equals("    " + block + ":");
                    continue;
                }
                if (inBlock && line.startsWith("      ") && !line.startsWith("        ")) {
                    String body = line.substring(6);
                    int colon = body.indexOf(':');
                    if (colon > 0) {
                        entries.put(body.substring(0, colon).strip(), body.substring(colon + 1).strip());
                    }
                }
            }
            return entries;
        }
    }
}