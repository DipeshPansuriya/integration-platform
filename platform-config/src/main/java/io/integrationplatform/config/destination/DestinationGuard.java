package io.integrationplatform.config.destination;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The ONE outbound destination check (ADR-010 section 8, ADR-011 Decision 1), free of any registry type so every
 * target type reuses it (T-124). In order, before anything is dialled:
 *
 * <ol>
 *   <li>only {@code http} and {@code https}; no user information; a host;</li>
 *   <li>an IP literal is checked against the refused ranges at once - a refused literal is refused even when the
 *       operator allow-lists it;</li>
 *   <li>a name must be on the allow-list - checked BEFORE any DNS query, so a refused name never costs a lookup;</li>
 *   <li>the name is resolved ONCE and EVERY address is checked; the first is pinned in the
 *       {@link CheckedDestination}, so the caller connects to the checked address with no second resolution.</li>
 * </ol>
 *
 * A redirect is a new destination: the caller runs the guard again on every hop.
 *
 * <p>T138 (showcase only, wired under the {@code showcase} profile by the systems configuration): a set of EXACT
 * allow-listed host names may resolve to a {@code loopback} or {@code private} address, so a showcase reaches a local
 * test destination. It never widens the allow-list, never lifts an IP literal and never lifts any other refused range
 * (link-local / cloud metadata, carrier-grade NAT, multicast, ...). Empty, it is today's guard. B21 (T137 F1,
 * CWE-918): the exception is also pinned to EXACT ports - a range is lifted only when the host AND the port are
 * listed, so a name that resolves to loopback does not reach every port on the host (database, identity, broker).
 * The ports are global to the exception, not per host.
 */
public final class DestinationGuard {

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /** F2: dot-separated DNS labels only (letters, digits, inner hyphens); used for the part in front of a wildcard suffix. */
    public static boolean isDnsLabels(String text) {
        return text != null && text.length() <= 253 && HOST_NAME.matcher(text).matches();
    }

    /**
     * F2 (CWE-74): true when {@code host} is an exact DNS host name (letters, digits, inner hyphens, dot-separated
     * labels; the last label is never all digits or 0x-hex, which would be an IPv4 encoding). Nothing else - no
     * URL syntax, no port, no whitespace - passes. Pure text, never a lookup.
     */
    public static boolean isHostName(String host) {
        if (host == null || host.length() > 253) {
            return false;
        }
        String name = AllowList.normalise(host);
        String lastLabel = name.substring(name.lastIndexOf('.') + 1);
        return HOST_NAME.matcher(name).matches() && !lastLabel.matches("[0-9]+|0x[0-9a-f]*");
    }

    /** F2: a plain host name, a dotted-quad IPv4 literal, or a bracketed IPv6 literal - the only shapes a DB host may take. */
    public static boolean isHostNameOrIpLiteral(String host) {
        if (host == null || host.length() > 253 || !host.equals(host.strip())) {
            return false;
        }
        return isHostName(host)
                || IPV4_LITERAL.matcher(host).matches()
                        && java.util.Arrays.stream(host.split("\\.")).allMatch(octet -> Integer.parseInt(octet) <= 255)
                || host.matches("\\[[0-9A-Fa-f:.]{2,45}\\]");
    }

    /** An exact host name: dot-separated labels of letters, digits and inner hyphens - no wildcard, port or IP. */
    private static final Pattern HOST_NAME =
            Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");

    /** The only ranges the showcase exception lifts: a local test destination is on loopback or a private network. */
    private static final Set<String> LOCAL_RANGES = Set.of("loopback", "private");

    private volatile AllowList allowList;
    private final HostResolver resolver;
    private final Set<String> showcaseLocalHosts;
    private final Set<Integer> showcaseLocalPorts;

    /** The detail of a refusal for a platform host (T-184): the platform's own data stores, broker and identity. */
    public static final String PLATFORM_HOST = "platform-host";

    private final Set<String> platformNames;
    private final Set<InetAddress> platformAddresses;

    public DestinationGuard(AllowList allowList, HostResolver resolver) {
        this(allowList, resolver, List.of(), List.of());
    }

    public DestinationGuard(AllowList allowList, HostResolver resolver, Collection<String> showcaseLocalHosts,
            Collection<Integer> showcaseLocalPorts) {
        this(allowList, resolver, showcaseLocalHosts, showcaseLocalPorts, List.of());
    }

    /**
     * @param showcaseLocalHosts exact host names (normalised like the allow-list) that may resolve to a loopback or
     *                           private address; only the showcase wiring passes a non-empty set
     * @param showcaseLocalPorts the exact ports of the local test destination; required (non-empty) when
     *                           {@code showcaseLocalHosts} is not empty
     * @throws IllegalArgumentException a host entry is empty, a wildcard, an IP literal or anything but a host name;
     *                                  a port is null or outside 1-65535; hosts are listed with no port
     * @param platformHosts      T-184: the platform's own hosts (exact names or IP literals) - never a destination,
     *                           whatever the allow-list or the showcase exception says; a name that resolves to a
     *                           listed literal is refused too (detail {@value #PLATFORM_HOST})
     */
    public DestinationGuard(AllowList allowList, HostResolver resolver, Collection<String> showcaseLocalHosts,
            Collection<Integer> showcaseLocalPorts, Collection<String> platformHosts) {
        this.allowList = Objects.requireNonNull(allowList, "allowList");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.showcaseLocalHosts = exactHostNames(Objects.requireNonNull(showcaseLocalHosts, "showcaseLocalHosts"));
        this.showcaseLocalPorts = exactPorts(Objects.requireNonNull(showcaseLocalPorts, "showcaseLocalPorts"));
        if (!this.showcaseLocalHosts.isEmpty() && this.showcaseLocalPorts.isEmpty()) {
            throw new IllegalArgumentException("showcase local hosts need at least one exact showcase local port");
        }
        Set<String> names = new LinkedHashSet<>();
        Set<InetAddress> addresses = new LinkedHashSet<>();
        for (String each : Objects.requireNonNull(platformHosts, "platformHosts")) {
            String host = each == null ? "" : AllowList.normalise(each);
            Optional<InetAddress> literal = literal(host);
            if (literal.isPresent()) {
                addresses.add(literal.get());
            } else if (HOST_NAME.matcher(host).matches()) {
                names.add(host);
            } else {
                throw new IllegalArgumentException(
                        "a platform host must be an exact host name or an IP literal (no wildcard or port): '"
                                + each + "'");
            }
        }
        this.platformNames = Set.copyOf(names);
        this.platformAddresses = Set.copyOf(addresses);
    }

    /**
     * T-124 save time: the checks of {@link #check(URI)} that need no DNS, on a bare host (an MQ or DB {@code host}
     * setting, or the host of an API base address). An IP literal is range-checked; a platform host is refused; a
     * name must be on the allow-list. Never resolves: DNS is decided at send, on the pinned address.
     */
    public void checkHost(String rawHost) throws DestinationRefusedException {
        if (rawHost == null || rawHost.isBlank()) {
            throw new DestinationRefusedException(Refusal.NO_HOST, null);
        }
        String host = AllowList.normalise(rawHost);
        Optional<InetAddress> literal = literal(host);
        refuseIfPlatform(host, literal);
        if (literal.isPresent()) {
            refuseIfInRange(literal.get(), false);
        }
        if (!allowList.allows(host)) {
            throw new DestinationRefusedException(Refusal.NOT_ON_ALLOW_LIST, null);
        }
    }

    /**
     * @throws DestinationRefusedException the address is refused (nothing may be sent to it)
     * @throws UnknownHostException the allow-listed name does not resolve (a failure, not a refusal)
     */
    public CheckedDestination check(URI address) throws DestinationRefusedException, UnknownHostException {
        Objects.requireNonNull(address, "address");
        String scheme = address.getScheme() == null ? "" : address.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new DestinationRefusedException(Refusal.SCHEME_NOT_ALLOWED, null);
        }
        if (address.getRawUserInfo() != null) {
            throw new DestinationRefusedException(Refusal.CREDENTIALS_IN_ADDRESS, null);
        }
        String rawHost = address.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            throw new DestinationRefusedException(Refusal.NO_HOST, null);
        }
        boolean tls = scheme.equals("https");
        int port = address.getPort() == -1 ? (tls ? 443 : 80) : address.getPort();
        String host = AllowList.normalise(rawHost);

        Optional<InetAddress> literal = literal(host);
        refuseIfPlatform(host, literal);
        if (literal.isPresent()) {
            refuseIfInRange(literal.get(), false);
            if (!allowList.allows(host)) {
                throw new DestinationRefusedException(Refusal.NOT_ON_ALLOW_LIST, null);
            }
            return new CheckedDestination(address, host, port, tls, literal.get());
        }
        if (!allowList.allows(host)) {
            throw new DestinationRefusedException(Refusal.NOT_ON_ALLOW_LIST, null);
        }
        List<InetAddress> resolved = resolver.resolve(host);
        if (resolved == null || resolved.isEmpty()) {
            throw new UnknownHostException(host);
        }
        boolean showcaseLocal = showcaseLocalHosts.contains(host) && showcaseLocalPorts.contains(port);
        for (InetAddress each : resolved) {
            refuseIfPlatform(host, Optional.of(each)); // before the showcase lift: it never lifts a platform host
            refuseIfInRange(each, showcaseLocal);
        }
        return new CheckedDestination(address, host, port, tls, resolved.get(0));
    }

    private void refuseIfPlatform(String host, Optional<InetAddress> address) throws DestinationRefusedException {
        if (platformNames.contains(host) || address.filter(platformAddresses::contains).isPresent()) {
            throw new DestinationRefusedException(Refusal.REFUSED_ADDRESS, PLATFORM_HOST);
        }
    }

    private static void refuseIfInRange(InetAddress address, boolean showcaseLocal)
            throws DestinationRefusedException {
        Optional<String> range = RefusedRanges.rangeOf(address);
        if (range.isPresent() && !(showcaseLocal && LOCAL_RANGES.contains(range.get()))) {
            throw new DestinationRefusedException(Refusal.REFUSED_ADDRESS, range.get());
        }
    }

    private static Set<String> exactHostNames(Collection<String> hosts) {
        Set<String> names = new LinkedHashSet<>();
        for (String each : hosts) {
            String name = each == null ? "" : AllowList.normalise(each);
            String lastLabel = name.substring(name.lastIndexOf('.') + 1);
            // a real top-level label is alphabetic: an all-digit or 0x-hex last label is an IPv4 encoding (T137 F3)
            if (!HOST_NAME.matcher(name).matches() || lastLabel.matches("[0-9]+|0x[0-9a-f]*")) {
                throw new IllegalArgumentException(
                        "a showcase local host must be an exact host name (no wildcard, IP literal or port): '"
                                + each + "'");
            }
            names.add(name);
        }
        return Set.copyOf(names);
    }

    private static Set<Integer> exactPorts(Collection<Integer> ports) {
        Set<Integer> exact = new LinkedHashSet<>();
        for (Integer each : ports) {
            if (each == null || each < 1 || each > 65535) {
                throw new IllegalArgumentException(
                        "a showcase local port must be an exact port 1-65535: '" + each + "'");
            }
            exact.add(each);
        }
        return Set.copyOf(exact);
    }

    /**
     * Replaces the allow-list at runtime (operator settings screen). Thread-safe: the volatile field swap is atomic;
     * in-flight checks see the old or the new list, never a partial update.
     */
    public void updateAllowList(AllowList replacement) {
        this.allowList = Objects.requireNonNull(replacement, "replacement");
    }

    /** An IPv4 dotted quad or a bracketed/plain IPv6 literal, parsed without any DNS query. */
    private static Optional<InetAddress> literal(String host) {
        String text = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!IPV4_LITERAL.matcher(text).matches() && !text.contains(":")) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.ofLiteral(text)); // a literal: parsed, never looked up or resolved
        } catch (IllegalArgumentException notALiteral) {
            return Optional.empty();
        }
    }
}
