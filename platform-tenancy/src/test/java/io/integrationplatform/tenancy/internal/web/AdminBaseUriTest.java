package io.integrationplatform.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * F1 (row 417, Medium): the admin base URI is accepted only when it is https, or plain http to an internal host (a
 * single-label service name, loopback, RFC1918 / ULA); an https host other than the issuer host needs the explicit
 * opt-in. Everything else stops the application at start-up, with a message that names the setting and never the value.
 */
class AdminBaseUriTest {

    private static final String ISSUER = "https://auth.example.test/realms/platform";

    private static String resolve(String raw) {
        return AdminBaseUri.resolve(raw, ISSUER, false);
    }

    @Test
    @DisplayName("Unset or blank keeps the issuer-derived behaviour (null)")
    void unsetIsNull() {
        assertThat(resolve(null)).isNull();
        assertThat(resolve("")).isNull();
        assertThat(resolve("   ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://keycloak:8080", "http://idp-internal", "http://idp-internal:8080/auth",
            "http://localhost:8080", "http://127.0.0.1:8080", "http://127.5.5.5", "http://[::1]:8080",
            "http://10.1.2.3", "http://10.255.255.255:8080", "http://172.16.5.5", "http://172.31.255.255:8080",
            "http://192.168.0.5", "http://192.168.255.1:8443", "http://[fd12:3456::1]:8080", "http://[fc00::5]",
            "http://KeyCloak:8080", "http://k8s-name-2:8080", "http://a"})
    @DisplayName("Plain http to an internal host is accepted")
    void internalPlainHttpIsAccepted(String base) {
        assertThat(resolve(base)).isEqualTo(base);
    }

    @Test
    @DisplayName("Trailing slashes are dropped; the whitespace around the value is stripped")
    void slashesAndWhitespace() {
        assertThat(resolve("  http://keycloak:8080///  ")).isEqualTo("http://keycloak:8080");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://idp.example.com", "http://idp.example.com:8080/secretpath", "http://8.8.8.8",
            "http://172.32.0.1:8080", "http://172.15.0.1", "http://192.169.0.1", "http://11.0.0.1",
            "http://169.254.169.254", "http://[2001:db8::1]", "http://[fe80::1]", "http://keycloak.:8080",
            "http://keycloak.internal:8080", "http://[::ffff:8.8.8.8]", "http://010.1.2.3", "http://999.1.1.1",
            "http://0.0.0.0", "http://1.2.3", "http://134744072", "http://2852039166", "http://0",
            "http://0x7f000001", "http://010100000", "http://9keycloak"})
    @Tag("negative")
    @DisplayName("Plain http to any other host (public name, public IP, link-local, odd shapes) is refused")
    void publicPlainHttpIsRefused(String base) {
        assertThatThrownBy(() -> resolve(base)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform.tenancy.identity-admin.base-uri")
                .hasMessageContaining("KEYCLOAK_ADMIN_BASE_URI")
                .hasMessageContaining("plain http")
                .hasMessageNotContaining(base).hasMessageNotContaining("secretpath")
                .hasMessageNotContaining("example.com")
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://keycloak", "file:///etc/passwd", "keycloak:8080", "http://", "gopher://keycloak",
            "http://key cloak:8080", "http://keycloak:8080?x=1", "http://keycloak:8080#frag",
            "https://auth.example.test?x=1", "http://keycloak:", "//keycloak:8080", "http://-keycloak"})
    @Tag("negative")
    @DisplayName("A non-http(s) scheme, a missing host, a query or a fragment is refused")
    void malformedIsRefused(String base) {
        assertThatThrownBy(() -> resolve(base)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KEYCLOAK_ADMIN_BASE_URI").hasMessageNotContaining(base).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://user:pw@keycloak:8080", "http://:s3cret@keycloak:8080",
            "https://admin:s3cret@auth.example.test", "http://admin@keycloak", "http://user_a:s3cret@host_b"})
    @Tag("negative")
    @DisplayName("User info in the URL is refused, and neither the user nor the password is echoed")
    void userInfoIsRefused(String base) {
        assertThatThrownBy(() -> resolve(base)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user info").hasMessageNotContaining("s3cret").hasMessageNotContaining("pw")
                .hasMessageNotContaining("admin:").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://key_cloak:8080", "http://idp_internal", "https://auth_x.example.test"})
    @Tag("negative")
    @DisplayName("An underscore in the host name gets its own message")
    void underscoreHostHasItsOwnMessage(String base) {
        assertThatThrownBy(() -> resolve(base)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("underscore").hasMessageContaining("KEYCLOAK_ADMIN_BASE_URI")
                .hasMessageNotContaining("key_cloak").hasMessageNotContaining("idp_internal").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://keycloak:0", "http://keycloak:65536", "http://keycloak:99999",
            "https://auth.example.test:70000"})
    @Tag("negative")
    @DisplayName("A port outside 1-65535 is refused")
    void badPortIsRefused(String base) {
        assertThatThrownBy(() -> resolve(base)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port").hasMessageNotContaining(base).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://keycloak:1", "http://keycloak:65535", "https://auth.example.test:65535"})
    @DisplayName("The port bounds 1 and 65535 are accepted")
    void portBoundsAreAccepted(String base) {
        assertThat(resolve(base)).isEqualTo(base);
    }

    @Test
    @DisplayName("https to the issuer host is accepted (any case, any port, any path)")
    void httpsToTheIssuerHostIsAccepted() {
        assertThat(resolve("https://auth.example.test")).isEqualTo("https://auth.example.test");
        assertThat(resolve("https://AUTH.Example.Test:8443/auth/")).isEqualTo("https://AUTH.Example.Test:8443/auth");
    }

    @Test
    @Tag("negative")
    @DisplayName("https to a host other than the issuer host is refused without the opt-in, and accepted with it")
    void httpsToAnotherHostNeedsTheOptIn() {
        String other = "https://idp.other.test";
        assertThatThrownBy(() -> AdminBaseUri.resolve(other, ISSUER, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform.tenancy.identity-admin.allow-external")
                .hasMessageContaining("KEYCLOAK_ADMIN_ALLOW_EXTERNAL")
                .hasMessageNotContaining("other.test").hasNoCause();
        assertThat(AdminBaseUri.resolve(other, ISSUER, true)).isEqualTo(other);
    }

    @Test
    @Tag("negative")
    @DisplayName("The opt-in widens only the https host check: plain http to a public host stays refused")
    void theOptInDoesNotAllowPlainHttpToAPublicHost() {
        assertThatThrownBy(() -> AdminBaseUri.resolve("http://idp.example.com", ISSUER, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain http");
    }

    @Test
    @Tag("negative")
    @DisplayName("The opt-in does not rescue userinfo, query, fragment, bad scheme, underscore or bad port")
    void theOptInDoesNotRescueMalformedValues() {
        for (String bad : new String[] {"https://u:p@idp.other.test", "https://idp.other.test?x=1",
                "https://idp.other.test#f", "ftp://idp.other.test", "https://idp_x.other.test",
                "https://idp.other.test:0"}) {
            assertThatThrownBy(() -> AdminBaseUri.resolve(bad, ISSUER, true)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("An internal plain-http admin base differing from the issuer host needs no opt-in (the hosted default)")
    void theHostedDefaultNeedsNoOptIn() {
        assertThat(AdminBaseUri.resolve("http://keycloak:8080", ISSUER, false)).isEqualTo("http://keycloak:8080");
    }
}
