package io.integrationplatform.config.destination;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Properties;
import java.util.regex.Pattern;
import javax.net.SocketFactory;

/**
 * F1 (CWE-918): a socket factory that dials ONLY the address the destination guard checked and pinned, whatever
 * host the driver asks for. A JDBC driver cannot take a pre-resolved address, so the driver is handed this class
 * through its {@code socketFactory} connection property (the {@link Properties} object, never the URL) together
 * with the pinned IP literal under {@link #PINNED_PROPERTY}. The driver keeps the original host name for TLS
 * server-name and certificate checks; only the TCP connect is redirected, so the host is never re-resolved at
 * connect time (DNS rebinding).
 *
 * <p>Lives in the destination package because only that package may dial a socket (T-184 arch rule). Every other
 * factory method fails closed: a driver that asks for a connected socket by host name is refused.
 */
public final class PinnedSocketFactory extends SocketFactory {

    /** The connection property that carries the pinned address as an IP literal (never a host name). */
    public static final String PINNED_PROPERTY = "ipass.pinnedAddress";

    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}(%[0-9A-Za-z]{1,16})?");

    private final InetAddress pinned;

    /** Called reflectively by the driver with the connection properties. */
    public PinnedSocketFactory(Properties connectionProperties) {
        String literal = connectionProperties == null ? null : connectionProperties.getProperty(PINNED_PROPERTY);
        if (literal == null || !IP_LITERAL.matcher(literal).matches()) {
            throw new IllegalArgumentException("the pinned address must be given as an IP literal");
        }
        try {
            this.pinned = InetAddress.getByName(literal); // a literal: parsed, never looked up
        } catch (UnknownHostException malformed) {
            throw new IllegalArgumentException("the pinned address is not an IP literal", malformed);
        }
    }

    @Override
    public Socket createSocket() {
        return new Socket() {
            @Override
            public void connect(SocketAddress requested) throws IOException {
                connect(requested, 0);
            }

            @Override
            public void connect(SocketAddress requested, int timeoutMillis) throws IOException {
                if (!(requested instanceof InetSocketAddress asked)) {
                    throw new IOException("only an inet address can be dialled");
                }
                super.connect(new InetSocketAddress(pinned, asked.getPort()), timeoutMillis);
            }
        };
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        throw new IOException("refused: dial the pinned address through the unconnected socket only");
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        throw new IOException("refused: dial the pinned address through the unconnected socket only");
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        throw new IOException("refused: dial the pinned address through the unconnected socket only");
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        throw new IOException("refused: dial the pinned address through the unconnected socket only");
    }
}
