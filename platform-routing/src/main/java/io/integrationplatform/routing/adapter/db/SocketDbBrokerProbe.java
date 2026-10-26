package io.integrationplatform.routing.adapter.db;

import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import io.integrationplatform.config.destination.SocketDialer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;

/**
 * T-149: the DB target probe (SocketKafkaBrokerProbe pattern). The host is resolved once through the destination
 * guard and the socket is dialled to that pinned address, so a DNS rebind between the check and the dial reaches
 * nothing.
 */
// shortcut: TCP reachability only - the protocol-level connect with the restricted account is the send path's
// leg (the container scenario), the probe does not hold its own pool.
class SocketDbBrokerProbe implements DbBrokerProbe {

    private final DestinationGuard guard;
    private final SocketDialer dialer;

    SocketDbBrokerProbe(DestinationGuard guard, SocketDialer dialer) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.dialer = Objects.requireNonNull(dialer, "dialer");
    }

    @Override
    public void probe(String host, int port, Duration timeout) throws IOException {
        CheckedDestination checked;
        try {
            checked = guard.check(URI.create("http://" + host + ":" + port)); // scheme only to reuse the URI guard
        } catch (DestinationRefusedException | UnknownHostException | IllegalArgumentException e) {
            throw new IOException("target database destination refused or unresolved", e);
        }
        try (var ignored = dialer.dial(new InetSocketAddress(checked.pinnedAddress(), port),
                (int) timeout.toMillis())) {
            // connected: reachable
        }
    }
}