package io.integrationplatform.routing.e8.duplicate;

import io.integrationplatform.ingest.port.PublishPort;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.Assertions;

/**
 * The E8 seam checks that make this wave's suites the red-first deliverable (T-114, T-115): the suites drive the
 * real chain on the real containers and, at the end, assert the product's routing-side Kafka pieces exist. They do
 * not yet - the red is the deliverable until lane A (the record consumer stage, T-111/T-181) and lane B (the
 * Kafka publish adapter behind the publish port, T-110) are integrated; arjun-fullstack re-runs these suites
 * after the wave 1 integration and they turn green then. The assertions fail on missing adapter/consumer - the
 * suites' own setup (broker, database, relay, guard) is proven before them, never the cause of a red.
 *
 * <p>Read-only: this scans the module's compiled package directories and loads what it finds - it never edits a
 * seam (RULES.md - the seams are consumed, not edited).
 *
 * <p>{@code public} and shared with the clientpin suite (same module, test scope): one seam check the two suites
 * and the judge can both read.
 */
public final class E8RoutingSeams {

    private E8RoutingSeams() {
    }

    /**
     * The routing publish package must hold the Kafka implementation of the ingest {@link PublishPort} (its
     * package-info names the package exactly that, T-110).
     */
    public static void assertRoutingsPublishAdapterIsIntegrated() throws Exception {
        assertPackageHolds("io.integrationplatform.routing.publish",
                "the Kafka publish adapter behind the publish port (lane B, T-110)",
                isPublishPort);
    }

    /**
     * The routing stage package must hold the stage-1 record consumer base - the consumer that loads the row
     * through the protected path and asks the duplicate guard (its package-info names the package exactly that,
     * T-111/T-181).
     */
    public static void assertRoutingsStageConsumerIsIntegrated() throws Exception {
        assertPackageHolds("io.integrationplatform.routing.stage",
                "the routing Kafka record consumer (lane A, T-111/T-181)", isAnyClass);
    }

    private static final java.util.function.Predicate<Class<?>> isAnyClass = type -> true;

    private static final java.util.function.Predicate<Class<?>> isPublishPort = type ->
            PublishPort.class.isAssignableFrom(type) && !type.isInterface();

    private static void assertPackageHolds(String packageName, String pieceName,
            java.util.function.Predicate<Class<?>> matches) throws Exception {
        URL location = E8RoutingSeams.class.getClassLoader()
                .getResource(packageName.replace('.', '/') + "/package-info.class");
        Assertions.assertThat(location)
                .as("the %s package itself should be on the routing classpath", packageName).isNotNull();
        File packageDirectory = new File(location.toURI()).getParentFile();
        List<Class<?>> found = new ArrayList<>();
        File[] compiled = packageDirectory.listFiles();
        if (compiled != null) {
            for (File candidate : compiled) {
                String name = candidate.getName();
                if (!name.endsWith(".class") || name.equals("package-info.class")
                        || candidate.getName().contains("$")) {
                    continue;
                }
                String className = packageName + "." + name.substring(0, name.length() - ".class".length());
                Class<?> type = Class.forName(className);
                if (matches.test(type)) {
                    found.add(type);
                }
            }
        }
        Assertions.assertThat(found)
                .as("missing %s - the E8 suite stays red until its wave 1 lane integrates it", pieceName)
                .isNotEmpty();
    }
}