package io.integrationplatform.config.e5.destination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.PinnedSocketFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** F1: the driver's socket always lands on the pinned address, whatever name it was asked to dial. */
class PinnedSocketFactoryTest {

    private static Properties pinned(String literal) {
        Properties p = new Properties();
        p.setProperty(PinnedSocketFactory.PINNED_PROPERTY, literal);
        return p;
    }

    @Test
    void anUnresolvedNameIsDialledOnThePinnedAddress() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                Socket socket = new PinnedSocketFactory(pinned("127.0.0.1")).createSocket()) {
            socket.connect(InetSocketAddress.createUnresolved("rebinding.invalid", server.getLocalPort()), 2000);
            try (Socket accepted = server.accept()) {
                assertThat(accepted.getInetAddress().getHostAddress()).isEqualTo("127.0.0.1");
            }
        }
    }

    @Test
    void aResolvedOtherAddressIsReplacedByThePinnedOne() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                Socket socket = new PinnedSocketFactory(pinned("127.0.0.1")).createSocket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, (byte) 255, (byte) 255, 1}),
                    server.getLocalPort()), 2000);
            assertThat(socket.getInetAddress().getHostAddress()).isEqualTo("127.0.0.1");
        }
    }

    @Test
    @Tag("negative")
    @Tag("security")
    void aHostNameOrMissingPinIsRefused() {
        assertThatThrownBy(() -> new PinnedSocketFactory(pinned("db.partner.example")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PinnedSocketFactory(new Properties()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    void everyOtherFactoryMethodFailsClosed() {
        PinnedSocketFactory factory = new PinnedSocketFactory(pinned("127.0.0.1"));
        assertThatThrownBy(() -> factory.createSocket("evil.example", 5432)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> factory.createSocket(InetAddress.getLoopbackAddress(), 5432))
                .isInstanceOf(java.io.IOException.class);
    }
}
