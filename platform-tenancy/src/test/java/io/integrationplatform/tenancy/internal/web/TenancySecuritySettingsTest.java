package io.integrationplatform.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * ADR-007 Decision 4, brief item 1: tenancy-security.yml names only environment variables for the issuer and the
 * audience (no default - fail closed) and allow-lists RS256 and ES256; the chain refuses to start without them or with
 * a weaker algorithm, and starting never calls the identity provider.
 */
class TenancySecuritySettingsTest {

    private static final String JWT = "spring.security.oauth2.resourceserver.jwt.";

    private static PropertySource<?> tenancySecurityYaml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("tenancy-security", new ClassPathResource("config/tenancy-security.yml"));
        assertThat(sources).hasSize(1);
        return sources.get(0);
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
            .withInitializer(context -> {
                try {
                    context.getEnvironment().getPropertySources().addLast(tenancySecurityYaml());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            })
            .withBean(TenantFixtures.FakeLoginBootstrap.class, () -> new TenantFixtures.FakeLoginBootstrap("unused"))
            .withUserConfiguration(TenancySecurityConfiguration.class);

    @Test
    void theFileNamesOnlyEnvironmentVariablesAndTheTwoAsymmetricAlgorithms() throws IOException {
        PropertySource<?> yaml = tenancySecurityYaml();
        assertThat(String.valueOf(yaml.getProperty(JWT + "issuer-uri"))).isEqualTo("${OIDC_ISSUER_URI}");
        assertThat(String.valueOf(yaml.getProperty(JWT + "audiences"))).isEqualTo("${OIDC_AUDIENCE}");
        assertThat(List.of(String.valueOf(yaml.getProperty(JWT + "jws-algorithms[0]")),
                String.valueOf(yaml.getProperty(JWT + "jws-algorithms[1]"))))
                .containsExactlyInAnyOrder("RS256", "ES256");
        assertThat(yaml.getProperty(JWT + "jws-algorithms[2]")).isNull();
    }

    @Test
    void withoutTheIssuerTheApplicationDoesNotStart() {
        runner.withPropertyValues("OIDC_AUDIENCE=platform-api")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("OIDC_ISSUER_URI"));
    }

    @Test
    void withoutTheAudienceTheApplicationDoesNotStart() {
        runner.withPropertyValues("OIDC_ISSUER_URI=http://127.0.0.1:9/realms/platform")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("OIDC_AUDIENCE"));
    }

    @Test
    void aSymmetricOrUnlistedAlgorithmIsRefusedAtStart() {
        runner.withPropertyValues("OIDC_ISSUER_URI=http://127.0.0.1:9/realms/platform", "OIDC_AUDIENCE=platform-api",
                        JWT + "jws-algorithms[0]=RS256", JWT + "jws-algorithms[1]=HS256")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("HS256"));
        runner.withPropertyValues("OIDC_ISSUER_URI=http://127.0.0.1:9/realms/platform", "OIDC_AUDIENCE=platform-api",
                        JWT + "jws-algorithms[0]=RS256", JWT + "jws-algorithms[1]=RS512")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("RS512"));
    }

    @Test
    void withBothNamesSetItStartsWithoutCallingTheProvider() {
        // 127.0.0.1:9 (discard) is never answered: starting must not fetch discovery or keys.
        runner.withPropertyValues("OIDC_ISSUER_URI=http://127.0.0.1:9/realms/platform", "OIDC_AUDIENCE=platform-api")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JwtDecoder.class));
    }
}
