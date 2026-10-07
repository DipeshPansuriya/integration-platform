package io.integrationplatform.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * E4 (T-097), ADR-007 Decision 1 and R07: tenancy-console.yml names only environment variables for the console client
 * (no default secret), keeps the session cookie HttpOnly + Secure + SameSite=Lax and keeps the sessions in PostgreSQL
 * (Spring Session JDBC, schema owned by Flyway - never created by the application).
 */
class ConsoleSettingsFileTest {

    private static PropertySource<?> consoleYaml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("tenancy-console", new ClassPathResource("config/tenancy-console.yml"));
        assertThat(sources).hasSize(1);
        return sources.get(0);
    }

    private static String value(String key) throws IOException {
        return String.valueOf(consoleYaml().getProperty(key));
    }

    @Test
    void theConsoleClientIsReadFromEnvironmentVariableNamesOnly() throws IOException {
        assertThat(value("platform.tenancy.console.client-id")).isEqualTo("${CONSOLE_OIDC_CLIENT_ID}");
        assertThat(value("platform.tenancy.console.client-secret")).isEqualTo("${CONSOLE_OIDC_CLIENT_SECRET}");
        assertThat(value("platform.tenancy.console.redirect-uri")).isEqualTo("${CONSOLE_OIDC_REDIRECT_URI}");
        assertThat(value("platform.tenancy.console.post-logout-redirect-uri"))
                .isEqualTo("${CONSOLE_POST_LOGOUT_REDIRECT_URI}");
    }

    @Test
    void theSessionCookieIsHttpOnlySecureAndSameSiteLax() throws IOException {
        assertThat(value("server.servlet.session.cookie.http-only")).isEqualTo("true");
        assertThat(value("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(value("server.servlet.session.cookie.same-site")).isEqualToIgnoringCase("lax");
        assertThat(value("server.servlet.session.cookie.name")).isEqualTo("SESSION");
    }

    @Test
    void sessionsLiveInPostgresqlInTheFlywayOwnedTables() throws IOException {
        assertThat(value("spring.session.jdbc.initialize-schema")).isEqualTo("never");
        assertThat(value("spring.session.jdbc.table-name")).isEqualTo("console_session.spring_session");
    }
}
