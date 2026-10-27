package io.integrationplatform.config.e5.destination;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Test-JVM hook (registered in {@code META-INF/services/java.net.spi.InetAddressResolverProvider}, test resources only):
 * records every name the JDK hands to its resolver and then delegates to the built-in resolver, so nothing else
 * changes. It lets a test prove that a text was parsed and never looked up (row 484, L-2).
 */
public final class RecordingResolverProvider extends InetAddressResolverProvider {

    static final Set<String> LOOKED_UP = ConcurrentHashMap.newKeySet();

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                    throws UnknownHostException {
                LOOKED_UP.add(host);
                return builtin.lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "recording-test-resolver";
    }
}
