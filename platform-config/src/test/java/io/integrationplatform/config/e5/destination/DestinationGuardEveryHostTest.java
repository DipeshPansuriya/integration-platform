package io.integrationplatform.config.e5.destination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.config.destination.Refusal;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Row 327 T97 (T-184, ADR-010 section 8): the ONE guard serves every target type's host field - not only an http
 * address. {@link DestinationGuard#checkHost(String)} is the save-time check of a bare host (API baseUrl host, MQ host,
 * DB host): refused ranges on an IP literal, the platform's own hosts and the operator allow-list, all BEFORE any DNS
 * query (DNS is the send and test-connection's business, where the address is pinned). The platform's own hosts (a
 * configured list) are refused at save, test and send, can never be allow-listed and are never lifted by the
 * showcase-only local-host exception (B21 stays as it is). Not tagged container (R03).
 */
class DestinationGuardEveryHostTest {

    private static final class CountingResolver implements HostResolver {

        private final Map<String, List<InetAddress>> names = new HashMap<>();
        private final List<String> lookups = new ArrayList<>();

        CountingResolver map(String host, String literal) throws UnknownHostException {
            names.put(host, List.of(InetAddress.getByName(literal)));
            return this;
        }

        @Override
        public List<InetAddress> resolve(String host) throws UnknownHostException {
            lookups.add(host);
            List<InetAddress> found = names.get(host);
            if (found == null) {
                throw new UnknownHostException(host);
            }
            return found;
        }
    }

    private static final List<String> PLATFORM_HOSTS = List.of("db.platform.example", "203.0.113.50");

    private static DestinationGuard guard(CountingResolver resolver, String... allowed) {
        return new DestinationGuard(AllowList.of(List.of(allowed)), resolver, List.of(), List.of(), PLATFORM_HOSTS);
    }

    @ParameterizedTest(name = "{0} is refused at save as {1}")
    @CsvSource({
        "127.0.0.1,         loopback",
        "10.0.0.5,          private",
        "169.254.169.254,   link-local",
        "fd00::1,           unique-local",
        "[fd00::1],         unique-local",
        "[::ffff:10.0.0.5], private",
        "100.64.0.1,        carrier-grade-nat"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("Every refused range is refused on a bare host at save time - no lookup, even when allow-listed")
    void aRefusedLiteralHostIsRefusedAtSave(String host, String range) {
        CountingResolver resolver = new CountingResolver();
        DestinationRefusedException refused = catchThrowableOfType(DestinationRefusedException.class,
                () -> guard(resolver, host).checkHost(host));

        assertThat(refused).as("refused: " + host).isNotNull();
        assertThat(refused.refusal()).isEqualTo(Refusal.REFUSED_ADDRESS);
        assertThat(refused.detail()).isEqualTo(range);
        assertThat(resolver.lookups).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A host off the per-tenant allow-list is refused at save time, before any lookup")
    void aHostOffTheAllowListIsRefusedAtSave() {
        CountingResolver resolver = new CountingResolver();
        DestinationRefusedException refused = catchThrowableOfType(DestinationRefusedException.class,
                () -> guard(resolver, "*.partner.example").checkHost("not-allowed.example"));

        assertThat(refused).isNotNull();
        assertThat(refused.refusal()).isEqualTo(Refusal.NOT_ON_ALLOW_LIST);
        assertThat(resolver.lookups).isEmpty();
    }

    @Test
    @DisplayName("An allow-listed host saves with no lookup - DNS is decided at send, on the pinned address")
    void anAllowListedHostPassesAtSaveWithoutLookup() {
        CountingResolver resolver = new CountingResolver();
        assertThatCode(() -> guard(resolver, "*.partner.example").checkHost("MQ.Partner.Example."))
                .doesNotThrowAnyException();
        assertThat(resolver.lookups).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A platform host can never be allow-listed: refused at save and at send/test, before any lookup")
    void aPlatformHostIsRefusedEvenWhenAllowListed() throws Exception {
        CountingResolver resolver = new CountingResolver().map("db.platform.example", "203.0.113.9");
        DestinationGuard guard = guard(resolver, "db.platform.example", "*.platform.example");

        DestinationRefusedException atSave = catchThrowableOfType(DestinationRefusedException.class,
                () -> guard.checkHost("DB.platform.example"));
        assertThat(atSave).isNotNull();
        assertThat(atSave.refusal()).isEqualTo(Refusal.REFUSED_ADDRESS);
        assertThat(atSave.detail()).isEqualTo("platform-host");

        DestinationRefusedException atSend = catchThrowableOfType(DestinationRefusedException.class,
                () -> guard.check(URI.create("https://db.platform.example:5432/")));
        assertThat(atSend).isNotNull();
        assertThat(atSend.detail()).isEqualTo("platform-host");
        assertThat(resolver.lookups).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A name that resolves to a platform host's address is refused after DNS")
    void aNameResolvingToAPlatformAddressIsRefused() throws Exception {
        CountingResolver resolver = new CountingResolver().map("alias.partner.example", "203.0.113.50");

        DestinationRefusedException refused = catchThrowableOfType(DestinationRefusedException.class,
                () -> guard(resolver, "*.partner.example").check(URI.create("https://alias.partner.example/")));

        assertThat(refused).isNotNull();
        assertThat(refused.detail()).isEqualTo("platform-host");
        assertThat(resolver.lookups).containsExactly("alias.partner.example");
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("The showcase-only local-host exception never lifts a platform host")
    void theShowcaseExceptionNeverLiftsAPlatformHost() throws Exception {
        CountingResolver resolver = new CountingResolver().map("db.platform.example", "127.0.0.1");
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("db.platform.example")), resolver,
                List.of("db.platform.example"), List.of(5432), PLATFORM_HOSTS);

        DestinationRefusedException refused = catchThrowableOfType(DestinationRefusedException.class,
                () -> guard.check(URI.create("http://db.platform.example:5432/")));

        assertThat(refused).isNotNull();
        assertThat(refused.detail()).isEqualTo("platform-host");
    }
}
