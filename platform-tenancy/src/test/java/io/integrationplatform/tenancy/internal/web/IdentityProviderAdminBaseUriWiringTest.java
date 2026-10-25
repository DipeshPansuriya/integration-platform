package io.integrationplatform.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.integrationplatform.tenancy.port.IdentityProviderAdmin;
import io.integrationplatform.tenancy.port.SourceCredentialStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Hosted-edge fix: {@code platform.tenancy.identity-admin.base-uri} (env {@code KEYCLOAK_ADMIN_BASE_URI}) sends the
 * Keycloak admin REST calls to an internal address while the token and the issuer keep the public URL. Two real local
 * servers stand for the two addresses: the "public" one answers only the token endpoint (the hosted edge hides
 * /admin and answers 404), the "internal" one only /admin.
 */
class IdentityProviderAdminBaseUriWiringTest {

    private static final String REALM_PATH = "/realms/platform";

    private final List<String> publicRequests = new CopyOnWriteArrayList<>();
    private final List<String> internalRequests = new CopyOnWriteArrayList<>();
    private HttpServer publicEdge;
    private HttpServer internal;

    @BeforeEach
    void servers() throws IOException {
        publicEdge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        publicEdge.createContext("/", exchange -> {
            publicRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().equals(REALM_PATH + "/protocol/openid-connect/token")) {
                reply(exchange, 200, "{\"access_token\":\"admin-token\",\"token_type\":\"Bearer\",\"expires_in\":60}");
            } else {
                reply(exchange, 404, "");   // /admin is not routed on the hosted edge
            }
        });
        internal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        internal.createContext("/", exchange -> {
            internalRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            reply(exchange, 201, "");
        });
        publicEdge.start();
        internal.start();
    }

    @AfterEach
    void stop() {
        publicEdge.stop(0);
        internal.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private ApplicationContextRunner runner(String... extra) {
        String issuer = "http://127.0.0.1:" + publicEdge.getAddress().getPort() + REALM_PATH;
        return new ApplicationContextRunner()
                .withUserConfiguration(IdentityProviderAdminConfiguration.class)
                .withBean(SourceCredentialStore.class, () -> Mockito.mock(SourceCredentialStore.class))
                .withBean(PlatformTransactionManager.class, () -> Mockito.mock(PlatformTransactionManager.class))
                .withPropertyValues("PLATFORM_ADMIN_CLIENT_SECRET=stub-" + UUID.randomUUID(),
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + issuer)
                .withPropertyValues(extra);
    }

    @Test
    @DisplayName("With the admin base set, the admin call reaches the internal address and the token the public one")
    void adminCallsGoToTheAdminBase() {
        String base = "http://127.0.0.1:" + internal.getAddress().getPort();

        runner("platform.tenancy.identity-admin.base-uri=" + base).run(context ->
                context.getBean(IdentityProviderAdmin.class).createConfidentialClient("src-erp-a"));

        assertThat(publicRequests).containsExactly("POST " + REALM_PATH + "/protocol/openid-connect/token");
        assertThat(internalRequests).containsExactly("POST /admin/realms/platform/clients");
    }

    @Test
    @DisplayName("With the admin base unset the admin call goes where it always went - the issuer's host")
    void unsetKeepsTheIssuerDerivedAdminUrl() {
        runner().run(context -> {
            try {
                context.getBean(IdentityProviderAdmin.class).createConfidentialClient("src-erp-a");
            } catch (IdentityProviderAdmin.IdentityProviderAdminException hidden) {
                // the public stub answers 404 for /admin, as the hosted edge does
            }
        });

        assertThat(internalRequests).isEmpty();
        assertThat(publicRequests).contains("POST /admin/realms/platform/clients");
    }

    /** The shipped tenancy-security.yml as a property source, so the env variable NAME is resolved the way the app does. */
    private ApplicationContextRunner runnerFromShippedYaml(String... environment) {
        String issuer = "http://127.0.0.1:" + publicEdge.getAddress().getPort() + REALM_PATH;
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        context.getEnvironment().getPropertySources().addLast(new YamlPropertySourceLoader()
                                .load("tenancy-security", new ClassPathResource("config/tenancy-security.yml")).get(0));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withUserConfiguration(IdentityProviderAdminConfiguration.class)
                .withBean(SourceCredentialStore.class, () -> Mockito.mock(SourceCredentialStore.class))
                .withBean(PlatformTransactionManager.class, () -> Mockito.mock(PlatformTransactionManager.class))
                .withPropertyValues("PLATFORM_ADMIN_CLIENT_SECRET=stub-" + UUID.randomUUID(),
                        "OIDC_ISSUER_URI=" + issuer, "OIDC_AUDIENCE=platform-api")
                .withPropertyValues(environment);
    }

    @Test
    @DisplayName("The env variable KEYCLOAK_ADMIN_BASE_URI, through the shipped tenancy-security.yml, reaches the adapter")
    void theEnvironmentVariableReachesTheAdapter() {
        String base = "http://127.0.0.1:" + internal.getAddress().getPort();

        runnerFromShippedYaml("KEYCLOAK_ADMIN_BASE_URI=" + base).run(context ->
                context.getBean(IdentityProviderAdmin.class).createConfidentialClient("src-erp-a"));

        assertThat(publicRequests).containsExactly("POST " + REALM_PATH + "/protocol/openid-connect/token");
        assertThat(internalRequests).containsExactly("POST /admin/realms/platform/clients");
    }

    @Test
    @DisplayName("The env variable unset (the yaml default is empty): the admin URL stays issuer-derived")
    void theEnvironmentVariableUnsetKeepsTheIssuerDerivedAdminUrl() {
        runnerFromShippedYaml().run(context -> {
            try {
                context.getBean(IdentityProviderAdmin.class).createConfidentialClient("src-erp-a");
            } catch (IdentityProviderAdmin.IdentityProviderAdminException hidden) {
                // the public stub answers 404 for /admin, as the hosted edge does
            }
        });

        assertThat(internalRequests).isEmpty();
        assertThat(publicRequests).contains("POST /admin/realms/platform/clients");
    }

    @Test
    @Tag("negative")
    @DisplayName("A bad admin base (wrong scheme, embedded credentials) stops the application at start-up")
    void aBadAdminBaseFailsTheStart() {
        runner("platform.tenancy.identity-admin.base-uri=ftp://keycloak:8080")
                .run(context -> assertThat(context).hasFailed());
        runner("platform.tenancy.identity-admin.base-uri=http://u:p@keycloak:8080")
                .run(context -> assertThat(context).hasFailed());
        assertThat(internalRequests).isEmpty();
    }
}
