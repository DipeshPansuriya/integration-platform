package io.integrationplatform.config.e5.destination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import io.integrationplatform.config.destination.HostResolver;
import io.integrationplatform.config.destination.RefusedRanges;
import io.integrationplatform.config.destination.Refusal;
import java.net.Inet6Address;
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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T-104 (E5-F1-S2): the one outbound destination guard (ADR-010 section 8, ADR-011 Decision 1) without any network -
 * the resolver is the injected port, so a name can be made to resolve to any range. The guard is free of system
 * registry types: T-124 reuses it for every target type. Not tagged container (R03).
 */
class DestinationGuardTest {

    private static final InetAddress PUBLIC = address("203.0.113.10");

    /** A resolver the test controls; counts its lookups so "no second resolution" is observable. */
    private static final class FakeResolver implements HostResolver {

        private final Map<String, List<InetAddress>> names = new HashMap<>();
        private final List<String> lookups = new ArrayList<>();

        FakeResolver map(String host, InetAddress... addresses) {
            names.put(host, List.of(addresses));
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

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal); // a literal: never a DNS query
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DestinationGuard guard(FakeResolver resolver, String... allowed) {
        return new DestinationGuard(AllowList.of(List.of(allowed)), resolver);
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
    @DisplayName("An allow-listed host resolving to a public address passes, pinned to the address it was checked on")
    void anAllowListedPublicHostPassesPinned() throws Exception {
        FakeResolver resolver = new FakeResolver().map("api.partner.example", PUBLIC);

        CheckedDestination checked = guard(resolver, "api.partner.example")
                .check(URI.create("https://api.partner.example/orders?x=1"));

        assertThat(checked.host()).isEqualTo("api.partner.example");
        assertThat(checked.port()).isEqualTo(443);
        assertThat(checked.tls()).isTrue();
        assertThat(checked.pinnedAddress()).isEqualTo(PUBLIC);
        assertThat(resolver.lookups).as("resolved exactly once").containsExactly("api.partner.example");
    }

    @Test
    @DisplayName("http defaults to port 80 and an explicit port is kept")
    void portsFollowTheScheme() throws Exception {
        FakeResolver resolver = new FakeResolver().map("a.example", PUBLIC);
        assertThat(guard(resolver, "a.example").check(URI.create("http://a.example/")).port()).isEqualTo(80);
        assertThat(guard(resolver, "a.example").check(URI.create("http://a.example:8080/")).port()).isEqualTo(8080);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A host not on the allow-list is refused before any lookup")
    void aHostOffTheAllowListIsRefusedBeforeLookup() {
        FakeResolver resolver = new FakeResolver().map("evil.example", PUBLIC);

        assertThat(refusalOf(guard(resolver, "api.partner.example"), "https://evil.example/"))
                .isEqualTo(Refusal.NOT_ON_ALLOW_LIST);
        assertThat(resolver.lookups).as("no DNS query for a refused name").isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("An empty allow-list refuses every destination (fail closed)")
    void anEmptyAllowListRefusesEverything() {
        FakeResolver resolver = new FakeResolver().map("api.partner.example", PUBLIC);
        assertThat(refusalOf(guard(resolver), "https://api.partner.example/")).isEqualTo(Refusal.NOT_ON_ALLOW_LIST);
    }

    @Test
    @DisplayName("A *.domain pattern allows its sub-domains only, never a look-alike suffix or the bare domain")
    void wildcardPatternsMatchSubDomainsOnly() {
        AllowList list = AllowList.of(List.of("*.partner.example", "Exact.Example"));
        assertThat(list.allows("api.partner.example")).isTrue();
        assertThat(list.allows("a.b.partner.example")).isTrue();
        assertThat(list.allows("API.PARTNER.EXAMPLE")).isTrue();
        assertThat(list.allows("exact.example")).isTrue();
        assertThat(list.allows("partner.example")).isFalse();
        assertThat(list.allows("evilpartner.example")).isFalse();
        assertThat(list.allows("api.partner.example.evil.test")).isFalse();
        assertThat(list.allows("sub.exact.example")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://api.partner.example/", "file:///etc/passwd", "gopher://api.partner.example/",
        "jar:http://api.partner.example/x.jar!/", "ldap://api.partner.example/"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("Only http and https are allowed")
    void nonHttpSchemesAreRefused(String address) {
        FakeResolver resolver = new FakeResolver().map("api.partner.example", PUBLIC);
        assertThat(refusalOf(guard(resolver, "api.partner.example"), address)).isEqualTo(Refusal.SCHEME_NOT_ALLOWED);
        assertThat(resolver.lookups).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Credentials in the address are refused (they would be sent and never belong in settings)")
    void userInfoIsRefused() {
        FakeResolver resolver = new FakeResolver().map("api.partner.example", PUBLIC);
        assertThat(refusalOf(guard(resolver, "api.partner.example"), "https://user:pw@api.partner.example/"))
                .isEqualTo(Refusal.CREDENTIALS_IN_ADDRESS);
    }

    @ParameterizedTest(name = "{0} is refused after resolution")
    @ValueSource(strings = {"127.0.0.1", "127.255.0.9", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254",
        "192.168.0.1", "192.168.255.1", "169.254.169.254", "0.0.0.0", "0.1.2.3", "100.64.0.1", "100.127.255.254",
        "224.0.0.1", "239.255.255.250", "255.255.255.255", "::1", "::", "fc00::1", "fd12:3456::1", "fe80::1",
        "ff02::1"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("Loopback, private, link-local, unspecified, CGNAT, multicast and broadcast resolutions are refused")
    void refusedRangesAfterResolution(String literal) {
        FakeResolver resolver = new FakeResolver().map("inside.partner.example", address(literal));

        assertThat(refusalOf(guard(resolver, "*.partner.example"), "http://inside.partner.example/"))
                .isEqualTo(Refusal.REFUSED_ADDRESS);
        assertThat(resolver.lookups).as("refused AFTER resolution").containsExactly("inside.partner.example");
    }

    @ParameterizedTest(name = "::ffff:{0} (IPv4-mapped IPv6) is checked as the embedded IPv4 address")
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.9.9", "192.168.7.7", "169.254.169.254"})
    @Tag("negative")
    @Tag("security")
    @DisplayName("An IPv4-mapped IPv6 address is checked as its IPv4 address")
    void ipv4MappedAddressesAreCheckedAsIpv4(String ipv4) throws Exception {
        byte[] v4 = address(ipv4).getAddress();
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        System.arraycopy(v4, 0, mapped, 12, 4);
        // Inet6Address.getByAddress keeps the 16-byte form (InetAddress.getByAddress would fold it to IPv4)
        InetAddress v6 = Inet6Address.getByAddress(null, mapped, -1);
        assertThat(v6).isInstanceOf(Inet6Address.class);

        assertThat(RefusedRanges.rangeOf(v6)).as("the mapped form of " + ipv4).isPresent();
        FakeResolver resolver = new FakeResolver().map("mapped.partner.example", v6);
        assertThat(refusalOf(guard(resolver, "*.partner.example"), "http://mapped.partner.example/"))
                .isEqualTo(Refusal.REFUSED_ADDRESS);
    }

    @ParameterizedTest(name = "{0} is a public address")
    @ValueSource(strings = {"203.0.113.10", "8.8.8.8", "172.15.255.255", "172.32.0.1", "11.0.0.1", "192.169.0.1",
        "100.63.255.255", "100.128.0.1", "2001:db8::1", "2606:4700::1111"})
    @DisplayName("Addresses just outside the refused ranges pass")
    void boundariesOutsideTheRangesPass(String literal) {
        assertThat(RefusedRanges.rangeOf(address(literal))).isEmpty();
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Every resolved address is checked: one private address among public ones refuses the destination")
    void everyResolvedAddressIsChecked() {
        FakeResolver resolver = new FakeResolver().map("multi.partner.example", PUBLIC, address("10.9.9.9"));
        assertThat(refusalOf(guard(resolver, "*.partner.example"), "https://multi.partner.example/"))
                .isEqualTo(Refusal.REFUSED_ADDRESS);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("A literal private address is refused even when the operator allow-lists it")
    void aLiteralPrivateAddressIsRefusedEvenWhenAllowListed() {
        FakeResolver resolver = new FakeResolver().map("127.0.0.1", address("127.0.0.1"));
        assertThat(refusalOf(guard(resolver, "127.0.0.1"), "http://127.0.0.1:8080/"))
                .isEqualTo(Refusal.REFUSED_ADDRESS);
    }

    @Test
    @DisplayName("An unresolvable host is not a refusal: the lookup failure surfaces as such")
    void anUnresolvableHostSurfaces() {
        FakeResolver resolver = new FakeResolver();
        assertThatThrownBy(() -> guard(resolver, "*.partner.example").check(URI.create("https://nx.partner.example/")))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    @Tag("negative")
    @DisplayName("An address without a host is refused")
    void anAddressWithoutAHostIsRefused() {
        assertThat(refusalOf(guard(new FakeResolver(), "*.partner.example"), "http:///nohost"))
                .isEqualTo(Refusal.NO_HOST);
    }
}
