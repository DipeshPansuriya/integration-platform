package io.integrationplatform.destination;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.destination.AllowList;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * Row 278 T97 (E10 lane C, T-184 "the tenant admin gets 403 on allow-list edits"): the operator's destination
 * allow-list is deployment configuration ({@code platform.config.destinations.allowed-hosts}, bound once at
 * startup in {@code SystemPersistenceConfiguration}) - until the deferred operator login exists (ADR-009), there
 * is NO API that edits it, so no tenant role can. These legs hold that shape: no endpoint of the whole app even
 * named like an allow-list editor, and the {@link AllowList} itself exposes no mutator.
 */
class AllowListSurfaceTest {

    private static final Pattern ALLOW_LIST_NAMED_PATH = Pattern.compile("(?i)allow.?list");

    @Test
    @DisplayName("no HTTP endpoint anywhere on the app's classpath edits (or names) the allow-list - it is"
            + " deployment configuration only")
    void noAllowListEditEndpointExists() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                        return definition.getMetadata().isIndependent();
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> paths = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("io.integrationplatform")) {
            Class<?> controller;
            try {
                controller = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException unreadable) {
                throw new IllegalStateException(unreadable);
            }
            for (Method method : controller.getMethods()) {
                for (String mapped : mappedPaths(method)) {
                    paths.add(method.getName() + " " + mapped);
                }
            }
            for (String mapped : mappedPaths(controller)) {
                paths.add(controller.getSimpleName() + " (class) " + mapped);
            }
        }

        assertThat(paths).as("every handler mapping of the scanned controllers").isNotEmpty();
        assertThat(paths).filteredOn(p -> ALLOW_LIST_NAMED_PATH.matcher(p).find()).isEmpty();
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

    private static List<String> mappedPaths(java.lang.reflect.AnnotatedElement handler) {
        List<String> paths = new ArrayList<>();
        RequestMapping root = handler.getAnnotation(RequestMapping.class);
        GetMapping get = handler.getAnnotation(GetMapping.class);
        PostMapping post = handler.getAnnotation(PostMapping.class);
        PutMapping put = handler.getAnnotation(PutMapping.class);
        PatchMapping patch = handler.getAnnotation(PatchMapping.class);
        DeleteMapping delete = handler.getAnnotation(DeleteMapping.class);
        if (root != null) {
            paths.addAll(List.of(root.value()));
        }
        if (get != null) {
            paths.addAll(List.of(get.value()));
        }
        if (post != null) {
            paths.addAll(List.of(post.value()));
        }
        if (put != null) {
            paths.addAll(List.of(put.value()));
        }
        if (patch != null) {
            paths.addAll(List.of(patch.value()));
        }
        if (delete != null) {
            paths.addAll(List.of(delete.value()));
        }
        return paths;
    }
}
