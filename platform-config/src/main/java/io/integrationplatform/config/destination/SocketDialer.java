package io.integrationplatform.config.destination;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The connect port of the guarded HTTP check: opens a TCP connection to the address the guard checked and pinned - an
 * {@link InetSocketAddress} that is already resolved, so no second lookup can happen (DNS rebinding). A test injects
 * its own to stand in for "the network behind the pinned address".
 */
@FunctionalInterface
public interface SocketDialer {

    Socket dial(InetSocketAddress pinned, int connectTimeoutMillis) throws IOException;

    /** A plain TCP connect to the pinned address. */
    static SocketDialer plain() {
        return (pinned, connectTimeoutMillis) -> {
            if (pinned.isUnresolved()) {
                throw new IOException("refusing to dial an unresolved address");
            }
            Socket socket = new Socket();
            try {
                socket.connect(pinned, connectTimeoutMillis);
                return socket;
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        };
    }
}
