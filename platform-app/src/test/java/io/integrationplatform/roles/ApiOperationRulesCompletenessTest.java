package io.integrationplatform.roles;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.tenancy.RolePolicy.Operation;
import io.integrationplatform.tenancy.port.ApiOperationRules;
import io.integrationplatform.tenancy.port.ApiOperationRules.Rule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * T-099: every {@code /api/**} handler of the MAIN sources of every module has exactly one role rule (method + path ->
 * {@code RolePolicy} operation), so no endpoint can reach production without a server-side role decision -
 * {@code /api/v1/me} is "any bound human". The handlers and the rule sets are found by scanning the classpath (test
 * classes excluded), so a new controller or module is checked without touching this test. Fails on a planted
 * violation (R02). Not tagged container (R03).
 */
class ApiOperationRulesCompletenessTest {

    private static final String BASE_PACKAGE = "io.integrationplatform";

    @Test
    @DisplayName("Every main-source /api handler has a role rule, and every rule names a real handler")
    void everyApiHandlerHasARoleRule() {
        List<Class<?>> controllers = mainSourceClasses(new AnnotationTypeFilter(Controller.class));
        List<ApiOperationRules> ruleSets = mainSourceClasses(new AssignableTypeFilter(ApiOperationRules.class))
                .stream().map(ApiOperationRulesCompletenessTest::instantiate).toList();

        assertThat(controllers).as("the scan finds the config admin controllers and /me").hasSizeGreaterThanOrEqualTo(4);
        assertThat(ruleSets).as("tenancy's and config's rule sets").hasSizeGreaterThanOrEqualTo(2);
        assertThat(problems(controllers, ruleSets)).isEmpty();
    }

    @Test
    @DisplayName("The rules map today's endpoints to the operations of the derived matrix")
    void todaysEndpointsMapAsReviewed() {
        Map<String, Operation> rules = new LinkedHashMap<>();
        mainSourceClasses(new AssignableTypeFilter(ApiOperationRules.class)).stream()
                .map(ApiOperationRulesCompletenessTest::instantiate)
                .flatMap(set -> set.rules().stream())
                .forEach(rule -> rules.put(rule.key(), rule.operation()));

        assertThat(rules).containsAllEntriesOf(Map.ofEntries(
                Map.entry("GET /api/v1/me", Operation.READ_OWN_IDENTITY),
                Map.entry("POST /api/v1/admin/systems", Operation.EDIT_CONFIGURATION),
                Map.entry("GET /api/v1/admin/flows", Operation.READ_CONFIGURATION),
                Map.entry("GET /api/v1/admin/flows/{flowId}", Operation.READ_CONFIGURATION),
                Map.entry("POST /api/v1/admin/flows", Operation.EDIT_CONFIGURATION),
                Map.entry("PUT /api/v1/admin/flows/{flowId}", Operation.EDIT_CONFIGURATION),
                Map.entry("PUT /api/v1/admin/flows/{flowId}/versions/{versionId}", Operation.EDIT_CONFIGURATION),
                Map.entry("POST /api/v1/admin/flows/{flowId}/submit", Operation.SUBMIT_FOR_APPROVAL),
                Map.entry("POST /api/v1/admin/flows/{flowId}/approve", Operation.APPROVE_VERSION),
                Map.entry("POST /api/v1/admin/flows/{flowId}/publish", Operation.PUBLISH_VERSION),
                Map.entry("POST /api/v1/admin/contracts/versions", Operation.EDIT_CONFIGURATION),
                Map.entry("GET /api/v1/admin/contracts/versions/{versionId}", Operation.READ_CONFIGURATION),
                Map.entry("POST /api/v1/admin/contracts/versions/{versionId}/submit", Operation.SUBMIT_FOR_APPROVAL)));
    }

    @Test
    @Tag("negative")
    @DisplayName("R02 planted violation: an /api handler without a rule fails the guard")
    void aPlantedHandlerWithoutARuleFails() {
        assertThat(problems(List.of(PlantedEndpoint.class), List.of(() -> List.of(
                new Rule("POST", "/api/v1/planted", Operation.EDIT_CONFIGURATION)))))
                .singleElement().asString()
                .startsWith("no role rule for GET /api/v1/planted/{id}");
    }

    @Test
    @Tag("negative")
    @DisplayName("A stale rule (no such handler) and a rule declared twice fail the guard")
    void staleAndDuplicateRulesFail() {
        List<ApiOperationRules> rules = List.of(
                () -> List.of(new Rule("GET", "/api/v1/planted/{id}", Operation.READ_CONFIGURATION),
                        new Rule("POST", "/api/v1/planted", Operation.EDIT_CONFIGURATION),
                        new Rule("DELETE", "/api/v1/gone", Operation.EDIT_CONFIGURATION)),
                () -> List.of(new Rule("POST", "/api/v1/planted", Operation.READ_CONFIGURATION)));

        assertThat(problems(List.of(PlantedEndpoint.class), rules)).containsExactlyInAnyOrder(
                "stale role rule, no such handler: DELETE /api/v1/gone",
                "role rule declared twice: POST /api/v1/planted");
    }

    @Test
    @DisplayName("Mappings outside /api are not the role guard's business")
    void nonApiMappingsAreOutOfScope() {
        assertThat(problems(List.of(NonApiEndpoint.class), List.of())).isEmpty();
    }

    // ---- the guard ------------------------------------------------------------------------------------------------

    static List<String> problems(Collection<Class<?>> controllers, Collection<ApiOperationRules> ruleSets) {
        List<String> problems = new ArrayList<>();
        Map<String, Rule> rules = new LinkedHashMap<>();
        for (ApiOperationRules set : ruleSets) {
            for (Rule rule : set.rules()) {
                if (rules.putIfAbsent(rule.key(), rule) != null) {
                    problems.add("role rule declared twice: " + rule.key());
                }
            }
        }
        Set<String> handled = new TreeSet<>();
        MappingReader reader = new MappingReader();
        for (Class<?> controller : controllers) {
            reader.mappings(controller).forEach((method, info) -> {
                Set<RequestMethod> verbs = info.getMethodsCondition().getMethods();
                for (String pattern : info.getPatternValues()) {
                    if (!pattern.equals("/api") && !pattern.startsWith("/api/")) {
                        continue;
                    }
                    if (verbs.isEmpty()) {
                        problems.add("an /api handler must name its HTTP method: " + pattern + " ("
                                + controller.getSimpleName() + "#" + method.getName() + ")");
                    }
                    for (RequestMethod verb : verbs) {
                        String key = verb.name() + " " + pattern;
                        handled.add(key);
                        if (!rules.containsKey(key)) {
                            problems.add("no role rule for " + key + " (" + controller.getSimpleName() + "#"
                                    + method.getName() + ")");
                        }
                    }
                }
            });
        }
        rules.keySet().stream().filter(key -> !handled.contains(key))
                .forEach(key -> problems.add("stale role rule, no such handler: " + key));
        return problems;
    }

    /** Classes of the main sources only: anything compiled from a test source tree is left out. */
    private static List<Class<?>> mainSourceClasses(org.springframework.core.type.filter.TypeFilter filter) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(filter);
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                Class<?> type = Class.forName(candidate.getBeanClassName());
                URL location = type.getProtectionDomain().getCodeSource().getLocation();
                if (!location.toString().contains("test-classes")) {
                    classes.add(type);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return classes;
    }

    private static ApiOperationRules instantiate(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (ApiOperationRules) constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("a rule set needs a no-argument constructor: " + type.getName(), e);
        }
    }

    /** Reads a controller type's mappings the way Spring MVC registers them, without an application context. */
    private static final class MappingReader extends RequestMappingHandlerMapping {

        MappingReader() {
            GenericApplicationContext empty = new GenericApplicationContext();
            empty.refresh();
            setApplicationContext(empty);
            afterPropertiesSet();
        }

        Map<Method, RequestMappingInfo> mappings(Class<?> type) {
            return MethodIntrospector.selectMethods(type,
                    (MethodIntrospector.MetadataLookup<RequestMappingInfo>) method -> getMappingForMethod(method, type));
        }
    }

    // ---- planted handlers: none is a @Controller, so no application ever registers them ---------------------------

    static class PlantedEndpoint {
        @GetMapping("/api/v1/planted/{id}")
        Object one(@org.springframework.web.bind.annotation.PathVariable String id) {
            return null;
        }

        @PostMapping("/api/v1/planted")
        Object create() {
            return null;
        }
    }

    static class NonApiEndpoint {
        @GetMapping("/internal/ping")
        Object ping() {
            return null;
        }
    }
}
