package io.integrationplatform.config.destination;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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
 */
public final class DestinationGuard {

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final AllowList allowList;
    private final HostResolver resolver;

    public DestinationGuard(AllowList allowList, HostResolver resolver) {
        this.allowList = Objects.requireNonNull(allowList, "allowList");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** T138 RED stub: the showcase-only local-host exception (compile-only). */
    public DestinationGuard(AllowList allowList, HostResolver resolver, Collection<String> showcaseLocalHosts) {
        throw new UnsupportedOperationException("T138 RED");
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
        if (literal.isPresent()) {
            refuseIfInRange(literal.get());
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
        for (InetAddress each : resolved) {
            refuseIfInRange(each);
        }
        return new CheckedDestination(address, host, port, tls, resolved.get(0));
    }

    private static void refuseIfInRange(InetAddress address) throws DestinationRefusedException {
        Optional<String> range = RefusedRanges.rangeOf(address);
        if (range.isPresent()) {
            throw new DestinationRefusedException(Refusal.REFUSED_ADDRESS, range.get());
        }
    }

    /** An IPv4 dotted quad or a bracketed/plain IPv6 literal, parsed without any DNS query. */
    private static Optional<InetAddress> literal(String host) {
        String text = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!IPV4_LITERAL.matcher(text).matches() && !text.contains(":")) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(text)); // a literal: parsed, never looked up
        } catch (UnknownHostException malformed) {
            return Optional.empty();
        }
    }
}
