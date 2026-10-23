package io.integrationplatform.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * T208 (row 372, design 2026-10-23-t198-t208): every exception advice answers for its own module only and sorts
 * before any fallback. A copied or global advice that matched another module's controllers would silently replace
 * that module's error contract.
 *
 * <p>For each production class annotated {@code @RestControllerAdvice} / {@code @ControllerAdvice}:
 * (a) it is {@code @RestControllerAdvice} (JSON answers); (b) it names a non-empty selector, and every
 * {@code basePackages}/{@code value} entry equals its OWN module root ({@code io.integrationplatform.<module>}),
 * with no other selector kind; (c) it carries {@code @Order(0)}. The one exemption is a platform-wide fallback: an
 * unscoped advice is allowed only in {@code io.integrationplatform.app} and only with
 * {@code @Order(Ordered.LOWEST_PRECEDENCE)}.
 */
final class ExceptionAdviceScopedToOwnModuleRule {

    static final String ROOT = "io.integrationplatform";
    static final String FALLBACK_HOME = ROOT + ".app";
    static final int MODULE_ORDER = 0;

    static final ArchRule EXCEPTION_ADVICE_SCOPED_TO_OWN_MODULE = classes()
            .that(areExceptionAdvice())
            .should(beScopedToOwnModuleAndOrdered())
            .because("T208: a module's exception advice answers for its own controllers only, at @Order(0); a "
                    + "platform-wide fallback may live only in " + FALLBACK_HOME + " at LOWEST_PRECEDENCE");

    private ExceptionAdviceScopedToOwnModuleRule() {
    }

    private static DescribedPredicate<JavaClass> areExceptionAdvice() {
        return DescribedPredicate.describe("exception advice",
                c -> c.isAnnotatedWith(RestControllerAdvice.class) || c.isAnnotatedWith(ControllerAdvice.class));
    }

    /** {@code io.integrationplatform.<module>} of a package, or "" outside the root. */
    static String moduleRoot(String packageName) {
        String module = ModuleInternalsRule.moduleOf(packageName);
        return module.isEmpty() ? "" : ROOT + "." + module;
    }

    private static ArchCondition<JavaClass> beScopedToOwnModuleAndOrdered() {
        return new ArchCondition<>("be a @RestControllerAdvice scoped to its own module root with the right @Order") {
            @Override
            public void check(JavaClass advice, ConditionEvents events) {
                List<String> problems = new ArrayList<>();
                String own = moduleRoot(advice.getPackageName());
                Optional<RestControllerAdvice> rest = advice.tryGetAnnotationOfType(RestControllerAdvice.class);
                if (rest.isEmpty()) {
                    problems.add("is a plain @ControllerAdvice (only @RestControllerAdvice keeps the answers JSON)");
                }
                List<String> packages = new ArrayList<>();
                boolean otherSelector = false;
                boolean anySelector = false;
                if (rest.isPresent()) {
                    packages.addAll(Arrays.asList(rest.get().basePackages()));
                    packages.addAll(Arrays.asList(rest.get().value()));
                    otherSelector = rest.get().basePackageClasses().length > 0
                            || rest.get().assignableTypes().length > 0 || rest.get().annotations().length > 0;
                    anySelector = !packages.isEmpty() || otherSelector;
                }
                boolean fallback = own.equals(FALLBACK_HOME) && rest.isPresent() && !anySelector;
                if (rest.isPresent() && !anySelector && !fallback) {
                    problems.add("names no selector, so it would answer for every module's controllers "
                            + "(an unscoped fallback is allowed only in " + FALLBACK_HOME + ")");
                }
                if (otherSelector) {
                    problems.add("scopes by basePackageClasses/assignableTypes/annotations; only basePackages "
                            + "= its own module root is fail-safe by location");
                }
                for (String p : packages) {
                    if (!p.equals(own)) {
                        problems.add("is scoped to '" + p + "' but its own module root is '" + own + "'");
                    }
                }
                Optional<Order> order = advice.tryGetAnnotationOfType(Order.class);
                int expected = fallback ? Ordered.LOWEST_PRECEDENCE : MODULE_ORDER;
                if (order.isEmpty()) {
                    problems.add("carries no @Order (needs @Order(" + expected + "))");
                } else if (order.get().value() != expected) {
                    problems.add("has @Order(" + order.get().value() + ") but needs @Order(" + expected + ")");
                }
                for (String problem : problems) {
                    events.add(SimpleConditionEvent.violated(advice, advice.getName() + " " + problem));
                }
            }
        };
    }
}
