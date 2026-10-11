package io.integrationplatform.config.rule.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.rule.api.RuleEvaluationPort;
import io.integrationplatform.config.rule.application.DecisionEngine;
import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules.Rule;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The seams lanes C and D depend on, each guard shown failing on a planted violation (R02): no ZEN engine class is
 * imported anywhere in {@code rule/adapter/in}; the test-panel controller depends on {@link RuleEvaluationPort} and
 * nothing else (so the panel can only ever run the runtime's engine); every rule endpoint has its role rule -
 * writes EDIT_CONFIGURATION, reads and the side-effect-free evaluate READ_CONFIGURATION.
 */
class RuleAdapterInGuardTest {

    private static final String ENGINE_PACKAGE = "io.gorules";

    @Test
    @DisplayName("No source file in rule/adapter/in imports an engine class")
    void noEngineClassInAdapterIn() throws IOException {
        Path folder = Path.of(System.getProperty("basedir", ".")).toAbsolutePath()
                .resolve("src/main/java/io/integrationplatform/config/rule/adapter/in");
        List<String> sources = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sources.add(Files.readString(file));
            }
        }
        assertThat(sources).as("the adapter/in sources are found").hasSizeGreaterThanOrEqualTo(3);
        assertThat(sources).noneMatch(RuleAdapterInGuardTest::namesTheEngine);
    }

    @Test
    @Tag("negative")
    @DisplayName("R02 planted violation: a controller source importing the engine fails the guard")
    void aPlantedEngineImportFailsTheGuard() {
        assertThat(namesTheEngine("package x;\nimport io.gorules.zen_engine.ZenEngine;\nclass Panel {}")).isTrue();
    }

    @Test
    @DisplayName("The test-panel controller depends on the runtime evaluation port and nothing else")
    void panelControllerDependsOnThePortOnly() {
        assertThat(onlyThePort(RuleEvaluationController.class)).isTrue();
    }

    @Test
    @Tag("negative")
    @DisplayName("R02 planted violation: a panel wired to anything besides the port fails the guard")
    void aPlantedPanelWithAnotherEngineFailsTheGuard() {
        assertThat(onlyThePort(PlantedPanel.class)).isFalse();
    }

    @Test
    @DisplayName("Every rule endpoint has its role rule: writes edit, reads and evaluate read")
    void ruleEndpointRoles() {
        Map<String, Operation> rules = new LinkedHashMap<>();
        for (Rule rule : new RuleEndpointRoles().rules()) {
            rules.put(rule.key(), rule.operation());
        }
        assertThat(rules).containsExactlyInAnyOrderEntriesOf(Map.of(
                "POST /api/v1/admin/flows/{flowId}/rule-sets", Operation.EDIT_CONFIGURATION,
                "GET /api/v1/admin/flows/{flowId}/rule-sets", Operation.READ_CONFIGURATION,
                "GET /api/v1/admin/rule-sets/{ruleSetId}", Operation.READ_CONFIGURATION,
                "PUT /api/v1/admin/rule-sets/{ruleSetId}", Operation.EDIT_CONFIGURATION,
                "POST /api/v1/admin/rule-sets/{ruleSetId}/evaluate", Operation.READ_CONFIGURATION));
    }

    private static boolean namesTheEngine(String source) {
        return source.contains(ENGINE_PACKAGE);
    }

    private static boolean onlyThePort(Class<?> controller) {
        boolean constructorsTakeOnlyThePort = Arrays.stream(controller.getDeclaredConstructors())
                .map(Constructor::getParameterTypes)
                .allMatch(types -> types.length == 1 && types[0] == RuleEvaluationPort.class);
        boolean fieldsHoldOnlyThePort = Arrays.stream(controller.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .allMatch(type -> type == RuleEvaluationPort.class);
        return constructorsTakeOnlyThePort && fieldsHoldOnlyThePort;
    }

    /** Planted: a test panel that also holds an engine of its own. */
    static final class PlantedPanel {

        private final RuleEvaluationPort port;
        private final DecisionEngine ownEngine;

        PlantedPanel(RuleEvaluationPort port, DecisionEngine ownEngine) {
            this.port = port;
            this.ownEngine = ownEngine;
        }
    }
}
