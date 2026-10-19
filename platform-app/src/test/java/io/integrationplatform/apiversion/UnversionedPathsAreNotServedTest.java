package io.integrationplatform.apiversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * T-169 (E11): API v1 is supported for 12 months, so every controller path of the MAIN sources of every module is
 * under {@code /api/v1} - an unversioned path is never served. The controllers are found by scanning the classpath
 * (test classes excluded), so a new controller or module is checked without touching this test. Fails on a planted
 * violation (R02). Not tagged container (R03).
 *
 * <p>Not controller mappings, so outside this scan: {@code /actuator/health} (Spring Boot actuator), the console login
 * entry and {@code /v3/api-docs} (security filter chain / library handlers), the console's static files.
 */
class UnversionedPathsAreNotServedTest {

    private static final String BASE_PACKAGE = "io.integrationplatform";
    private static final String V1 = "/api/v1";

    /** Controller paths allowed outside {@code /api/v1}, each with its reason. Empty today: none is needed. */
    static final Map<String, String> ALLOWED = Map.of();

    @Test
    @DisplayName("Every main-source controller path is under /api/v1")
    void everyControllerPathIsVersioned() {
        List<Class<?>> controllers = mainSourceControllers();

        assertThat(controllers).as("the scan finds the intake, admin and console controllers").hasSizeGreaterThanOrEqualTo(15);
        assertThat(unversioned(controllers, ALLOWED)).isEmpty();
    }

    @Test
    @Tag("negative")
    @DisplayName("R02 planted violation: an unversioned and a v2 path fail the guard; the v1 path passes")
    void aPlantedUnversionedPathFails() {
        assertThat(unversioned(List.of(PlantedEndpoint.class), Map.of())).containsExactlyInAnyOrder(
                "unversioned path GET /interfaces/{code}/messages (PlantedEndpoint#legacy)",
                "unversioned path POST /api/v2/messages (PlantedEndpoint#next)",
                "unversioned path GET /api/v1x (PlantedEndpoint#lookalike)");
    }

    @Test
    @DisplayName("An allow-listed path passes; a stale allow-list entry fails")
    void allowListIsExactAndNotStale() {
        assertThat(unversioned(List.of(PlantedEndpoint.class), Map.of(
                "/interfaces/{code}/messages", "planted reason",
                "/api/v2/messages", "planted reason",
                "/api/v1x", "planted reason",
                "/gone", "planted reason")))
                .containsExactly("stale allow-list entry, no such path: /gone");
    }

    // ---- the guard ------------------------------------------------------------------------------------------------

    static List<String> unversioned(Collection<Class<?>> controllers, Map<String, String> allowed) {
        return List.of(); // RED stub
    }

    private static List<Class<?>> mainSourceControllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
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
        @GetMapping("/interfaces/{code}/messages")
        Object legacy() {
            return null;
        }

        @PostMapping("/api/v2/messages")
        Object next() {
            return null;
        }

        @GetMapping("/api/v1x")
        Object lookalike() {
            return null;
        }

        @GetMapping("/api/v1/messages/{id}")
        Object versioned() {
            return null;
        }
    }
}
