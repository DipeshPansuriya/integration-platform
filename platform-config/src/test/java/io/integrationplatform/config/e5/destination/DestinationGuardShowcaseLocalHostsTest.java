package io.integrationplatform.config.e5.destination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.config.destination.Refusal;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T138 (row 278, BS-17 legs 17a/17b/17e): the showcase-only local-host exception at the guard itself, without any
 * network. The exception names EXACT allow-listed host names that may resolve to a loopback or private address; it
 * never widens the allow-list, never lifts an IP literal, and never lifts link-local (cloud metadata), CGNAT,
 * multicast or any other refused range. Without it the guard refuses exactly as today (DestinationGuard :62, :76,
 * :81-86). The profile gate, the startup refusal and the WARN live in the Spring wiring
 * (SystemPersistenceShowcaseWiringTest, ShowcaseLocalHostsHostContainerTest). Not tagged container (R03).
 */
class DestinationGuardShowcaseLocalHostsTest {

    private static final String TARGET = "wiremock.showcase.test";

    private static final Map<String, List<InetAddress>> NAMES = Map.of(
            TARGET, List.of(address("127.0.0.1"), address("::1")),
            "docker.showcase.test", List.of(address("172.18.0.5")),
            "metadata.showcase.test", List.of(address("169.254.169.254")),
            "mixed.showcase.test", List.of(address("172.18.0.5"), address("169.254.169.254")),
            "other.showcase.test", List.of(address("127.0.0.1")),
            "a.wiremock.showcase.test", List.of(address("127.0.0.1")),
            "unlisted.example", List.of(address("127.0.0.1")));

    private static final HostResolver RESOLVER = host -> {
        List<InetAddress> found = NAMES.get(host);
        if (found == null) {
            throw new UnknownHostException(host);
        }
        return found;
    };

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal); // a literal: never a DNS query
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DestinationGuard today(String... allowed) {
        return new DestinationGuard(AllowList.of(List.of(allowed)), RESOLVER);
    }

    private static DestinationGuard showcase(List<String> localHosts, String... allowed) {
        return new DestinationGuard(AllowList.of(List.of(allowed)), RESOLVER, localHosts);
    }

    private static Refusal refusalOf(DestinationGuard guard, String address) {
        try {
            guard.check(URI.create(address));
        } catch (DestinationRefusedException refused) {
            return refused.refusal();
        } catch (UnknownHostException e) {
            throw new AssertionError("expected a refusal, got an unresolved host", e);
        }
        throw new AssertionError("expected a refusal for " + address);
    }

    @Test
    @DisplayName("17e: without the exception an allow-listed name on loopback or private is refused, as today")
    void withoutTheExceptionTodaysRefusalStands() {
        DestinationGuard guard = today(TARGET, "docker.showcase.test");

        assertThat(refusalOf(guard, "http://" + TARGET + ":8089/tenant-a/orders")).isEqualTo(Refusal.REFUSED_ADDRESS);
        assertThat(refusalOf(guard, "http://docker.showcase.test/")).isEqualTo(Refusal.REFUSED_ADDRESS);
        assertThat(refusalOf(showcase(List.of(), TARGET), "http://" + TARGET + "/"))
                .as("an empty exception list is today's guard").isEqualTo(Refusal.REFUSED_ADDRESS);
    }

    @Test
    @DisplayName("17a: a named exception host may resolve to loopback or private and is pinned to the checked address")
    void theNamedHostMayResolveToLoopbackOrPrivate() throws Exception {
        DestinationGuard guard = showcase(List.of(TARGET, "docker.showcase.test"), TARGET, "docker.showcase.test");

        CheckedDestination loopback = guard.check(URI.create("http://" + TARGET + ":8089/tenant-a/orders"));
        assertThat(loopback.host()).isEqualTo(TARGET);
        assertThat(loopback.port()).isEqualTo(8089);
        assertThat(loopback.pinnedAddress()).isEqualTo(address("127.0.0.1"));

        CheckedDestination docker = guard.check(URI.create("https://docker.showcase.test/"));
        assertThat(docker.pinnedAddress()).isEqualTo(address("172.18.0.5"));
    }

    @Test
    @DisplayName("Only loopback and private are lifted: link-local (cloud metadata) stays refused even for a named host")
    void onlyLoopbackAndPrivateAreLifted() {
        DestinationGuard guard = showcase(List.of("metadata.showcase.test", "mixed.showcase.test"),
                "metadata.showcase.test", "mixed.showcase.test");

        assertThat(refusalOf(guard, "http://metadata.showcase.test/latest/meta-data/"))
                .isEqualTo(Refusal.REFUSED_ADDRESS);
        assertThat(refusalOf(guard, "http://mixed.showcase.test/"))
                .as("every resolved address is still checked").isEqualTo(Refusal.REFUSED_ADDRESS);
    }

    @Test
    @DisplayName("17b: exact names only - a sibling or a sub-domain of the named host stays refused")
    void exactNamesOnly() {
        DestinationGuard guard = showcase(List.of(TARGET), "*.showcase.test", "*.wiremock.showcase.test");

        assertThat(refusalOf(guard, "http://other.showcase.test/")).isEqualTo(Refusal.REFUSED_ADDRESS);
        assertThat(refusalOf(guard, "http://a.wiremock.showcase.test/")).isEqualTo(Refusal.REFUSED_ADDRESS);
    }

    @Test
    @DisplayName("An IP literal is never lifted: http://127.0.0.1 stays refused even with the exception on")
    void anIpLiteralStaysRefused() {
        DestinationGuard guard = showcase(List.of(TARGET), TARGET, "127.0.0.1");

        assertThat(refusalOf(guard, "http://127.0.0.1:8089/tenant-a/orders")).isEqualTo(Refusal.REFUSED_ADDRESS);
        assertThat(refusalOf(guard, "http://[::1]:8089/")).isEqualTo(Refusal.REFUSED_ADDRESS);
    }

    @Test
    @DisplayName("The exception never widens the allow-list: a named host that is not allow-listed is refused")
    void theExceptionNeverWidensTheAllowList() {
        DestinationGuard guard = showcase(List.of("unlisted.example"), TARGET);

        assertThat(refusalOf(guard, "http://unlisted.example/")).isEqualTo(Refusal.NOT_ON_ALLOW_LIST);
    }

    @Test
    @DisplayName("Names are matched case-insensitively and without the trailing dot, like the allow-list")
    void namesAreNormalised() throws Exception {
        DestinationGuard guard = showcase(List.of(" WireMock.Showcase.Test. "), TARGET);

        assertThat(guard.check(URI.create("http://" + TARGET + "/")).pinnedAddress())
                .isEqualTo(address("127.0.0.1"));
    }

    @ParameterizedTest(name = "17b: \"{0}\" is not an exact host name")
    @ValueSource(strings = {"*.showcase.test", "*", "wire*mock.showcase.test", "", "  ", "127.0.0.1", "2130706433",
            "::1", "[::1]", "wiremock.showcase.test:8089", "http://wiremock.showcase.test", "wire mock",
            "wiremock..test", ".showcase.test"})
    void anythingButAnExactHostNameIsRejected(String entry) {
        assertThatThrownBy(() -> showcase(List.of(TARGET, entry), TARGET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact host name");
    }

    @Test
    void aNullEntryIsRejected() {
        assertThatThrownBy(() -> showcase(Arrays.asList(TARGET, null), TARGET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact host name");
    }
}
