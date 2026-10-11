package io.integrationplatform.config.destination;

import java.net.InetAddress;
import java.net.URI;
import java.util.Objects;

/**
 * A destination the guard accepted: the original address and name (kept for the Host header, TLS SNI and the
 * certificate check) and the ONE resolved address it was checked on - the address to connect to, with no second
 * lookup.
 */
public record CheckedDestination(URI uri, String host, int port, boolean tls, InetAddress pinnedAddress) {

    public CheckedDestination {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(pinnedAddress, "pinnedAddress");
    }

    /** {@code scheme://host:port} - the key of the destination's breaker and retry. */
    public String key() {
        return (tls ? "https" : "http") + "://" + host + ":" + port;
    }
}
