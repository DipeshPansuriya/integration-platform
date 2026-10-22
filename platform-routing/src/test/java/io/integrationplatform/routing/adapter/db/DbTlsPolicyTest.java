package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** E16 Karan R3/R8: the operator's DB TLS mode is validated at start; verify-ca/verify-full get an operator CA file. */
class DbTlsPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"require", "verify-ca", "verify-full"})
    void theThreeEncryptedModesAreAccepted(String mode) {
        assertThat(DbTlsPolicy.requireSslmode(mode, false)).isEqualTo(mode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"disable", "allow", "prefer", "requre", "", " ", "Require", "verify_full"})
    @Tag("security")
    @Tag("negative")
    void aTypoOrAnUnencryptedModeCannotFailOpenAtStart(String mode) {
        assertThatThrownBy(() -> DbTlsPolicy.requireSslmode(mode, false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disableIsAcceptedOnlyForATestDatabase() {
        assertThat(DbTlsPolicy.requireSslmode("disable", true)).isEqualTo("disable");
        assertThatThrownBy(() -> DbTlsPolicy.requireSslmode("prefer", true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noRootCertConfiguredIsNull(@TempDir Path dir) {
        assertThat(DbTlsPolicy.rootCert("", "")).isNull();
        assertThat(DbTlsPolicy.rootCert(dir.toString(), "")).isNull();
    }

    @Test
    void aRootCertIsAFileNameUnderTheOperatorDirectory(@TempDir Path dir) throws Exception {
        Path ca = Files.writeString(dir.resolve("partner-ca.pem"), "x");
        assertThat(DbTlsPolicy.rootCert(dir.toString(), "partner-ca.pem")).isEqualTo(ca.toAbsolutePath().normalize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../ca.pem", "/etc/ca.pem", "C:\\ca.pem", "sub/ca.pem", "..", ".", "a b.pem", "missing.pem"})
    @Tag("security")
    @Tag("negative")
    void aPathOrAMissingFileIsRefusedAtStart(String name, @TempDir Path dir) {
        assertThatThrownBy(() -> DbTlsPolicy.rootCert(dir.toString(), name)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Tag("negative")
    void aRootCertNeedsItsDirectory() {
        assertThatThrownBy(() -> DbTlsPolicy.rootCert("", "ca.pem")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theDriverGetsTheRootCertOnlyForAVerifyingMode(@TempDir Path dir) throws Exception {
        Path ca = Files.writeString(dir.resolve("ca.pem"), "x");
        JdbcDbSender sender = new JdbcDbSender(Duration.ofSeconds(5), "verify-full", ca);
        InetAddress any = InetAddress.getLoopbackAddress();

        assertThat(sender.properties(message(null), any).getProperty("sslrootcert")).isEqualTo(ca.toString());
        assertThat(sender.properties(message("verify-ca"), any).getProperty("sslrootcert")).isEqualTo(ca.toString());
        assertThat(sender.properties(message("require"), any).getProperty("sslrootcert")).isNull();
        assertThat(new JdbcDbSender(Duration.ofSeconds(5)).properties(message("verify-full"), any)
                .getProperty("sslrootcert")).as("no operator CA configured: the driver default applies").isNull();
    }

    private static DbMessage message(String sslmode) {
        return JdbcDbSenderUrlTest.message(JdbcDbSenderUrlTest.settings("db.partner.example", "orders", sslmode));
    }
}
