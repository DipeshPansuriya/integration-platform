package io.integrationplatform.destination;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.adapter.in.OperatorAllowedHostController;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Row 278 T97 (E10 lane C, T-184 "the tenant admin gets 403 on allow-list edits"): the operator's destination
 * allow-list is deployment configuration ({@code platform.config.destinations.allowed-hosts}, bound once at
 * startup in {@code SystemPersistenceConfiguration}) - until the deferred operator login exists (ADR-009), there
 * is NO API that edits it, so no tenant role can. These legs hold that shape: no endpoint of the whole app even
 * named like an allow-list editor, and neither the {@link AllowList} nor the {@link DestinationGuard} offers a way
 * to change the list at runtime.
 *
 * <p>Row 484 (security finding H-1): PR 34 added {@code /api/v1/admin/settings/allowed-hosts} and this test stayed
 * green, because the detector only knew the spelling "allow-list" and read the class-level and the method-level path
 * apart. It now composes the HTTP verb with the full path, knows both spellings, and a planted controller proves it
 * fires.
 */
class AllowListSurfaceTest {

    /** Both spellings of the list: allow-list / allowlist, and allowed-hosts / allowedHosts / allow-hosts. */
    private static final Pattern ALLOW_LIST_NAMED = Pattern.compile("(?i)allow(ed)?[-_ ]?(list|hosts?)");

    private static final Pattern MUTATOR_NAME =
            Pattern.compile("(?i)^(set|add|remove|put|clear|update|replace|reload|swap)");

    @Test
    @DisplayName("no HTTP endpoint outside the operator controller edits (or names) the allow-list")
    void noAllowListEditEndpointExistsOutsideOperatorController() {
        List<Class<?>> controllers = new ArrayList<>(restControllers());
        controllers.remove(OperatorAllowedHostController.class);
        List<String> surfaces = handlerSurfaces(controllers);

        assertThat(surfaces).as("every handler mapping of the scanned controllers").isNotEmpty();
        assertThat(surfaces).filteredOn(s -> ALLOW_LIST_NAMED.matcher(s).find()).isEmpty();
    }

    @Test
    @DisplayName("the OperatorAllowedHostController is the only allow-list editor and all its endpoints name it")
    void operatorControllerIsTheOnlyAllowListEditor() {
        List<String> surfaces = handlerSurfaces(List.of(OperatorAllowedHostController.class));
        assertThat(surfaces).isNotEmpty();
        assertThat(surfaces).allMatch(s -> ALLOW_LIST_NAMED.matcher(s).find());
    }

    @Test
    @DisplayName("R02 planted violation: a controller that adds or removes allowed-hosts is flagged by the detector")
    void aPlantedAllowedHostsEditorIsFlagged() {
        List<String> flagged = handlerSurfaces(List.of(PlantedAllowedHostsEditor.class)).stream()
                .filter(s -> ALLOW_LIST_NAMED.matcher(s).find())
                .toList();

        assertThat(flagged)
                .anyMatch(s -> s.startsWith("POST /api/v1/admin/settings/allowed-hosts "))
                .anyMatch(s -> s.startsWith("DELETE /api/v1/admin/settings/allowed-hosts "));
    }

    @Test
    @DisplayName("the AllowList is built once from the operator's list and offers no mutation - the tenant admin"
            + " cannot edit what only deployment config holds")
    void allowListIsImmutable() {
        assertThat(AllowList.class.getFields()).isEmpty();
        List<String> mutators = new ArrayList<>();
        for (Method method : AllowList.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith("set") || name.startsWith("add") || name.startsWith("remove")
                    || name.startsWith("put") || name.startsWith("clear")) {
                mutators.add(method.getName());
            }
        }
        assertThat(mutators).isEmpty();
    }

    @Test
    @DisplayName("the one DestinationGuard bean offers no way to replace the list at runtime - the list it holds is the"
            + " one it was built with")
    void guardOffersNoRuntimeReplacementOfTheList() {
        List<String> mutators = Arrays.stream(DestinationGuard.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .filter(name -> MUTATOR_NAME.matcher(name).find())
                .sorted()
                .toList();

        assertThat(mutators).isEmpty();
    }

    /** "VERB /full/path [Controller.method]" of every handler of the controllers: class-level path + method-level path. */
    private static List<String> handlerSurfaces(Collection<Class<?>> controllers) {
        List<String> surfaces = new ArrayList<>();
        for (Class<?> controller : controllers) {
            List<String> roots = pathsOf(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
            for (Method method : controller.getMethods()) {
                RequestMapping onMethod = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (onMethod == null) {
                    continue;
                }
                List<String> verbs = onMethod.method().length == 0
                        ? List.of("ANY")
                        : Arrays.stream(onMethod.method()).map(RequestMethod::name).toList();
                for (String verb : verbs) {
                    for (String root : roots) {
                        for (String path : pathsOf(onMethod)) {
                            surfaces.add(verb + " " + root + path
                                    + " [" + controller.getSimpleName() + "." + method.getName() + "]");
                        }
                    }
                }
            }
        }
        return surfaces;
    }

    private static List<String> pathsOf(RequestMapping mapping) {
        return mapping == null || mapping.path().length == 0 ? List.of("") : List.of(mapping.path());
    }

    private static List<Class<?>> restControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                        return definition.getMetadata().isIndependent();
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> controllers = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("io.integrationplatform")) {
            try {
                controllers.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException unreadable) {
                throw new IllegalStateException(unreadable);
            }
        }
        return controllers;
    }

    /** Not a {@code @RestController}: the classpath scan above must not find it, only the planted-violation leg. */
    @RequestMapping("/api/v1/admin/settings/allowed-hosts")
    static class PlantedAllowedHostsEditor {

        @PostMapping
        public void add() {
        }

        @DeleteMapping
        public void remove() {
        }
    }
}
