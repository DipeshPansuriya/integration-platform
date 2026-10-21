package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.PinnedSocketFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * E16 security F1/F2/F5 at the driver boundary, no containers: the URL has no query and is built from validated
 * parts only, every driver option rides in the Properties object, TLS is requested by default, and the real driver
 * dials the pinned address for a host name that does not resolve at all.
 */
class JdbcDbSenderUrlTest {

    static DbMessage message(DbSettings settings) {
        return new DbMessage(settings, "restricted", "pw".toCharArray(), "orders_in", List.of("awb"),
                List.of(List.of("A1")));
    }

    static DbSettings settings(String host, String database, String sslmode) {
        return new DbSettings(host, 5432, database, "restricted", "orders_in", sslmode);
    }

    @Test
    void theUrlIsBuiltFromTheValidatedPartsOnlyAndHasNoQuery() {
        assertThat(JdbcDbSender.url(settings("db.partner.example", "orders_1.v-2", null)))
                .isEqualTo("jdbc:postgresql://db.partner.example:5432/orders_1.v-2").doesNotContain("?");
    }

    @ParameterizedTest
    @ValueSource(strings = {"x?socketFactory=evil", "x?sslmode=disable", "x&y", "x;y", "x/y", "x#y", "x,y"})
    @Tag("negative")
    @Tag("security")
    void aDatabaseWithUrlSyntaxCannotBeBuiltSoItNeverReachesTheDriver(String database) {
        assertThatThrownBy(() -> settings("db.partner.example", database, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DbSettings.fromJson("{\"host\":\"db.partner.example\",\"port\":5432,\"database\":\""
                + database + "\"}")).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"h?x=1", "h&x", "h;x", "h/x", "h#x", "h,x", "h%41", "u@h", "h`x", "h x"})
    @Tag("negative")
    @Tag("security")
    void aHostWithUrlSyntaxCannotBeBuilt(String host) {
        assertThatThrownBy(() -> settings(host, "orders", null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    void anAccountWithUrlOrSqlSyntaxCannotBeBuilt() {
        assertThatThrownBy(() -> new DbSettings("db.partner.example", 5432, "orders", "a\"b", "orders_in", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyDriverOptionRidesInThePropertiesAndTheDriverDialsThePinnedAddress() throws Exception {
        Properties p = new JdbcDbSender(Duration.ofSeconds(5)).properties(
                message(settings("db.partner.example", "orders", null)), InetAddress.getByName("93.184.216.34"));

        assertThat(p.getProperty("socketFactory")).isEqualTo(PinnedSocketFactory.class.getName());
        assertThat(p.getProperty(PinnedSocketFactory.PINNED_PROPERTY)).isEqualTo("93.184.216.34");
        assertThat(p.getProperty("sslmode")).as("TLS is required by default").isEqualTo("require");
        assertThat(p.getProperty("user")).isEqualTo("restricted");
    }

    @Test
    void aTargetMayAskForAStrongerModeAndTheOperatorMayRelaxTheDefaultForATestDatabase() throws Exception {
        InetAddress any = InetAddress.getLoopbackAddress();
        assertThat(new JdbcDbSender(Duration.ofSeconds(5)).properties(
                message(settings("db.partner.example", "orders", "verify-full")), any).getProperty("sslmode"))
                .isEqualTo("verify-full");
        assertThat(new JdbcDbSender(Duration.ofSeconds(5), "disable").properties(
                message(settings("db.partner.example", "orders", null)), any).getProperty("sslmode"))
                .isEqualTo("disable");
        assertThatThrownBy(() -> settings("db.partner.example", "orders", "disable"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** The real pgjdbc driver, a host name that cannot resolve, and a local listener: the connect lands on the pin. */
    @Test
    @Tag("security")
    void theRealDriverConnectsToThePinnedAddressNotToTheNameInTheUrl() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread accepting = Thread.ofVirtual().start(() -> {
                try (var connection = listener.accept()) {
                    accepted.countDown();
                } catch (java.io.IOException ignored) {
                    // the test closes the listener on exit
                }
            });
            DbSettings settings = new DbSettings("db.rebinding.invalid", listener.getLocalPort(), "orders",
                    "restricted", "orders_in", null);

            assertThatThrownBy(() -> new JdbcDbSender(Duration.ofSeconds(3), "disable")
                    .insert(message(settings), UUID.randomUUID(), InetAddress.getLoopbackAddress()))
                    .isInstanceOf(SQLException.class);

            assertThat(accepted.await(3, TimeUnit.SECONDS))
                    .as("the driver dialled the pinned loopback listener, not the unresolvable name").isTrue();
            accepting.join(2000);
        }
    }
}
