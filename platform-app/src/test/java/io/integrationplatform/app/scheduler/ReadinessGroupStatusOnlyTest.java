package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/**
 * L20 cross-lane check (L17 readiness list x L19 exact actuator permit): L19 opens {@code /actuator/health/readiness}
 * anonymously on the premise that the body is the status only. The shipped configuration (application.yml and
 * config-secrets.yml, the file that lists {@code schedulerJobs} in the readiness group) must therefore never set
 * {@code show-details} or {@code show-components}, on the endpoint or on any group, or the job type names and the
 * "reason" text of L17's indicator would be public. (Live body proof: the container classes named in the L20 report.)
 */
class ReadinessGroupStatusOnlyTest {

    @Test
    void readinessGroupListsSchedulerJobsAndNoShowSettingExistsAnywhere() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.import=classpath:config/config-secrets.yml")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ConfigurableEnvironment env = context.getEnvironment();
                    assertThat(env.getProperty("management.endpoint.health.group.readiness.include"))
                            .contains("schedulerJobs");
                    List<String> shown = env.getPropertySources().stream()
                            .filter(EnumerablePropertySource.class::isInstance)
                            .flatMap(source -> java.util.Arrays.stream(((EnumerablePropertySource<?>) source).getPropertyNames()))
                            .filter(name -> name.startsWith("management.endpoint.health"))
                            .filter(name -> name.contains("show-details") || name.contains("show-components"))
                            .toList();
                    assertThat(shown).as("anonymous health stays status-only").isEmpty();
                });
    }
}
