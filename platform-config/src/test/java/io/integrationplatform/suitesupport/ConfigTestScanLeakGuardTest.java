package io.integrationplatform.suitesupport;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.system.application.DbAccountWidthCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;

/**
 * B41: the E5/E3/parity suite apps component-scan {@code io.integrationplatform.config}, which on the test classpath
 * also holds test classes. A test class with a stereotype ({@code @Configuration}) leaks its beans into every scanning
 * suite (SystemPersistenceWidthCheckWiringTest's nested slice once added a second DbAccountWidthCheck, so the three
 * container suites failed to start with NoUniqueBeanDefinitionException). Scans exactly like the suite apps and parses
 * the configuration classes (no refresh, nothing instantiated): production code defines no DbAccountWidthCheck, so any
 * match is a test-tree class that landed in the scan.
 */
class ConfigTestScanLeakGuardTest {

    @Test
    @DisplayName("scanning io.integrationplatform.config picks up no DbAccountWidthCheck bean from the test tree")
    void theConfigScanLeaksNoWidthCheck() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan("io.integrationplatform.config");
            ConfigurationClassPostProcessor parser = new ConfigurationClassPostProcessor();
            parser.setEnvironment(context.getEnvironment());
            parser.setResourceLoader(context);
            parser.setBeanClassLoader(context.getClassLoader());
            parser.postProcessBeanDefinitionRegistry(context);

            assertThat(context.getBeanFactory().getBeanNamesForType(DbAccountWidthCheck.class, true, false))
                    .as("a leaked test @Configuration would make every E5 suite app see two DbAccountWidthCheck beans")
                    .isEmpty();
        }
    }
}
