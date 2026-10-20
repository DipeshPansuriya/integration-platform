package io.integrationplatform.config.e5.system;

import static io.integrationplatform.config.e5.system.E5SystemFixture.call;
import static io.integrationplatform.config.e5.system.E5SystemFixture.newUser;
import static io.integrationplatform.config.e5.system.E5SystemFixture.randomCode;
import static io.integrationplatform.config.e5.system.E5SystemFixture.scalar;
import static io.integrationplatform.config.e5.system.E5SystemFixture.systemBody;
import static io.integrationplatform.config.e5.system.E5SystemFixture.tenantA;
import static io.integrationplatform.config.e5.system.E5SystemFixture.SYSTEMS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import io.integrationplatform.config.scheduler.JobScheduleSync;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.mockito.Mockito.mock;

/**
 * T-182 (E10-F1-S5, ADR-010 section 5; ADR-011 Decision 1 addendum) on a running system with the real
 * PostgreSQL row: the error dictionary is a tunable default per target - a documented 500 as TRANSIENT is
 * accepted and stored in the row's settings JSONB, while the rules the runtime refuses to bend (401, 403 as
 * retryable) are refused at SAVE with the reason, and the row never appears.
 */
@Tag("container")
@SpringBootTest(classes = E5SystemSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "platform.config.destinations.allowed-hosts=*.e5.test,*.partner.example,mq.example")
class ErrorDictionarySaveContainerTest {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        E5SystemFixture.wire(registry);
    }

    /**
     * The E5 suite app predates the scheduler module's controllers (that lane supplies the beans in the composed
     * app): this suite only saves systems, so the scheduler's client rides a Mockito stub — unused by the tests.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SchedulerGaps {

        @Bean
        @Primary
        SchedulerClient schedulerClient() {
            return mock(SchedulerClient.class);
        }

        @Bean
        @Primary
        java.time.Clock suiteClock() {
            return java.time.Clock.systemUTC();
        }

        @Bean
        @Primary
        JobScheduleSync jobScheduleSync(SchedulerClient client) {
            return new JobScheduleSync(List.of(), client, () -> java.time.Instant.now());
        }
    }

    private static String configurator() {
        return newUser(tenantA().id(), "CONFIGURATOR");
    }

    private static List<String> errorsOf(E5SystemFixture.Response response) {
        List<String> errors = new ArrayList<>();
        response.json().path("errors").forEach(node -> errors.add(node.asString()));
        return errors;
    }

    @Test
    @DisplayName("A documented 500 meaning \"not processed\": the TRANSIENT dictionary saves and is stored")
    void aDocumentedTransientOverridesSaves() {
        String user = configurator();
        String settings =
                "{\"baseUrl\":\"https://api.partner.example/v1\",\"timeoutMs\":3000,"
                        + "\"errorDictionary\":{\"500\":\"TRANSIENT\"}}";

        var created = call(port, "POST", SYSTEMS, user,
                systemBody(randomCode("sys-ed"), "OUTBOUND", "API", settings));

        assertThat(created.status()).as("body: %s", created.body()).isEqualTo(201);
        String id = created.json().path("id").asString();
        assertThat(scalar("SELECT settings->'errorDictionary'->>'500' FROM public.system WHERE id = ?::uuid", id))
                .as("the tunable default row stores the override").isEqualTo("TRANSIENT");
    }

    @Test
    @DisplayName("The never-retryable refusals cannot be stored: 401 as TRANSIENT is refused at save")
    void aRetryableAuthOverrideIsRefusedAtSave() {
        String user = configurator();
        String code = randomCode("sys-ed401");

        var refused = call(port, "POST", SYSTEMS, user, systemBody(code, "OUTBOUND", "API",
                "{\"baseUrl\":\"https://api.partner.example/v1\",\"errorDictionary\":{\"401\":\"TRANSIENT\"}}"));

        assertThat(refused.status()).as("body: %s", refused.body()).isEqualTo(400);
        assertThat(errorsOf(refused)).singleElement().asString().contains("401").contains("never retryable");
        assertThat(scalar("SELECT count(*) FROM public.system WHERE code = ?", code)).isEqualTo("0");
    }
}