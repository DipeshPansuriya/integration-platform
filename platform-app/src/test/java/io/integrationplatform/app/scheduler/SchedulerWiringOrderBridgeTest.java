package io.integrationplatform.app.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * T-056 wiring (sanjay-developer, row 278) - JVM-only red/green evidence for the ordering bridge: the
 * db-scheduler starter orders itself against the Boot 3 DataSourceAutoConfiguration FQN, dead in Boot 4
 * (the class moved to org.springframework.boot.jdbc.autoconfigure), so its @ConditionalOnBean(DataSource)
 * backed out and the Scheduler/SchedulerClient beans never registered (the platform-app container cascade on
 * main, 2a4b579). The bridge re-states both sides with the Boot 4 names. Container-proof of the full wiring
 * (SchedulerSpringWiringContainerTest) is the arjun lock window.
 */
class SchedulerWiringOrderBridgeTest {

    private static final String CANDIDATES_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static final String STARTER_AUTOCONFIGURATION =
            "com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration";

    /** The imports register on the classpath lists the bridge - registered, not just compiled. */
    @Test
    void theImportsRegisterListsTheBridge() throws IOException {
        List<String> candidates = readCandidates();
        assertTrue(candidates.contains(SchedulerWiringOrderBridge.class.getName()),
                "the bridge is a registered autoconfiguration candidate; got " + candidates);
    }

    /** The bridge orders after the Boot 4 DataSource autoconfiguration - a class that really exists. */
    @Test
    void theBridgeOrdersAfterTheBoot4DataSourceAutoConfiguration() throws Exception {
        AutoConfiguration bridge = SchedulerWiringOrderBridge.class.getAnnotation(AutoConfiguration.class);
        assertEquals(1, bridge.after().length);
        Class<?> after = Class.forName(bridge.after()[0].getName());
        assertEquals(org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class, after,
                "the Boot 4 DataSource autoconfiguration, not the moved-away Boot 3 FQN");
    }

    /** The bridge orders before the db-scheduler starter's autoconfiguration, so its condition sees the
     * DataSource bean definition the boot-4 DataSource autoconfiguration registered first. */
    @Test
    void theBridgeOrdersBeforeTheDbSchedulerStarterAutoconfiguration() throws Exception {
        AutoConfiguration bridge = SchedulerWiringOrderBridge.class.getAnnotation(AutoConfiguration.class);
        assertEquals(1, bridge.beforeName().length);
        assertEquals(STARTER_AUTOCONFIGURATION, bridge.beforeName()[0]);
        // both sides of the ordering exist on this classpath, so the sort can apply the constraint
        Class.forName(bridge.beforeName()[0]);
        Class.forName(STARTER_AUTOCONFIGURATION);
    }

    private static List<String> readCandidates() throws IOException {
        List<String> names = new ArrayList<>();
        Enumeration<URL> resources = Thread.currentThread().getContextClassLoader()
                .getResources(CANDIDATES_RESOURCE);
        while (resources.hasMoreElements()) {
            try (var lines = new java.io.BufferedReader(new java.io.InputStreamReader(
                    resources.nextElement().openStream(), java.nio.charset.StandardCharsets.UTF_8)).lines()) {
                lines.filter(line -> !line.isBlank() && !line.startsWith("#")).forEach(names::add);
            }
        }
        return names;
    }
}