package io.integrationplatform.e10.archfixture.compliant;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/** T-184 compliant fixture: builds an address from bytes (no DNS) - what a pinned destination looks like. */
public final class PinnedAddressUser {

    public InetSocketAddress pinned(byte[] address, int port) throws UnknownHostException {
        return new InetSocketAddress(InetAddress.getByAddress(address), port);
    }
}
