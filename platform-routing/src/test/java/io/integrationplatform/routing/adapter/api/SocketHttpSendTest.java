package io.integrationplatform.routing.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.destination.CheckedDestination;
import io.integrationplatform.config.destination.SocketDialer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The real {@link SocketHttpSend} over the plain dialer, without a container (row 278 T97, lane C): the address
 * the guard PINNED is the address that is dialled - the host name is kept only for the Host header (the guard
 * checked the name, the wire follows the pin). {@code rebind.example} exists nowhere, so a send that dialled by
 * name (a second lookup, the DNS-rebinding opening) could not reach the test server; this leg reaches it only
 * because the pinned literal is used.
 */
class SocketHttpSendTest {

    @Test
    @DisplayName("the pinned address is dialled and the name is never looked up - DNS rebinding gets no second chance")
    void dialsThePinnedAddressNeverTheName() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            AtomicReference<String> requestLine = new AtomicReference<>();
            AtomicReference<String> hostHeader = new AtomicReference<>();
            Thread serving = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(accepted.getInputStream(), StandardCharsets.ISO_8859_1));
                    requestLine.set(in.readLine());
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        if (line.startsWith("Host:")) {
                            hostHeader.set(line.substring("Host:".length()).trim());
                        }
                    }
                    accepted.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    accepted.getOutputStream().flush();
                } catch (Exception e) {
                    // the leg below asserts on the captured lines; a failed accept shows them as null
                }
            });
            serving.start();

            CheckedDestination destination = new CheckedDestination(
                    URI.create("http://rebind.example:" + server.getLocalPort() + "/orders"),
                    "rebind.example", server.getLocalPort(), false, InetAddress.getLoopbackAddress());

            HttpSend.Response answer = new SocketHttpSend(SocketDialer.plain()).send(destination, "POST",
                    Map.of("Content-Type", "application/json"), "{\"x\":1}".getBytes(StandardCharsets.UTF_8), 2000);
            serving.join(5000);

            assertThat(answer.status()).isEqualTo(200);
            assertThat(requestLine.get()).isEqualTo("POST /orders HTTP/1.1");
            assertThat(hostHeader.get()).isEqualTo("rebind.example:" + server.getLocalPort());
        }
    }
}
