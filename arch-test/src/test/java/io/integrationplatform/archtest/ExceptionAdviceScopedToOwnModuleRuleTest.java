package io.integrationplatform.archtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** T208: the rule passes on today's advices and bites on each planted violation. */
class ExceptionAdviceScopedToOwnModuleRuleTest {

    private static final String FIXTURES = "io.integrationplatform.t208fixture";

    @Test
    void everyProductionModuleThatHasControllersHasItsOwnScopedAdvice() {
        JavaClasses production = ModuleInternalsRule.importProductionClasses();

        Set<String> advices = production.stream()
                .filter(c -> c.getSimpleName().endsWith("ExceptionHandler"))
                .map(c -> ExceptionAdviceScopedToOwnModuleRule.moduleRoot(c.getPackageName()))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(advices).as("modules with an advice").containsExactly(
                "io.integrationplatform.app", "io.integrationplatform.config",
                "io.integrationplatform.delivery", "io.integrationplatform.tenancy");

        ExceptionAdviceScopedToOwnModuleRule.EXCEPTION_ADVICE_SCOPED_TO_OWN_MODULE.check(production);
    }

    @Test
    void noModuleRootIsAPrefixOfAnotherBecauseBasePackagesMatchesByPrefix() {
        List<String> roots = ModuleInternalsRule.importProductionClasses().stream()
                .map(JavaClass::getPackageName)
                .map(ExceptionAdviceScopedToOwnModuleRule::moduleRoot)
                .filter(r -> !r.isEmpty()).distinct().sorted().toList();

        assertThat(roots).hasSizeGreaterThanOrEqualTo(8);
        for (String a : roots) {
            for (String b : roots) {
                if (!a.equals(b)) {
                    assertThat(b.startsWith(a)).as(a + " must not be a string prefix of " + b).isFalse();
                }
            }
        }
    }

    @Test
    void anAdviceScopedToItsOwnRootAtOrderZeroPasses() {
        JavaClasses compliant = new ClassFileImporter().importPackages(FIXTURES + ".compliant");

        assertThat(compliant.size()).isEqualTo(1);
        ExceptionAdviceScopedToOwnModuleRule.EXCEPTION_ADVICE_SCOPED_TO_OWN_MODULE.check(compliant);
    }

    @Test
    void anUnscopedAdviceFails() {
        assertPlanted("unscoped", "UnscopedAdvice", "names no selector");
    }

    @Test
    void anAdviceScopedToAnotherModulesRootFails() {
        assertPlanted("wrongscope", "WrongScopeAdvice",
                "is scoped to 'io.integrationplatform.config' but its own module root is 'io.integrationplatform.t208fixture'");
    }

    @Test
    void anAdviceWithoutOrderFails() {
        assertPlanted("noorder", "NoOrderAdvice", "carries no @Order");
    }

    private static void assertPlanted(String pkg, String name, String reason) {
        JavaClasses planted = new ClassFileImporter().importPackages(FIXTURES + "." + pkg);

        assertThat(planted.size()).as("the planted violation is in scope").isEqualTo(1);
        assertThatThrownBy(() -> ExceptionAdviceScopedToOwnModuleRule.EXCEPTION_ADVICE_SCOPED_TO_OWN_MODULE.check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(name)
                .hasMessageContaining(reason);
    }
}
