package io.integrationplatform.config.destination;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * The destination guard's DNS port (ADR-010 section 8): a host name to every address it resolves to. Production uses
 * {@link #system()}; a test injects its own so a name can resolve to any range without real DNS.
 */
@FunctionalInterface
public interface HostResolver {

    /** Every address of {@code host}; never empty - an unknown name throws. */
    List<InetAddress> resolve(String host) throws UnknownHostException;

    /** The JVM's resolver ({@link InetAddress#getAllByName}). */
    static HostResolver system() {
        return host -> List.of(InetAddress.getAllByName(host));
    }
}
