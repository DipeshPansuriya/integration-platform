package io.integrationplatform.config.e5.destination;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A one-thread HTTP/1.1 server on the loopback interface for the bare destination tests: every connection gets the
 * same canned answer, and the request lines it received are recorded. Only the test dialer ever connects to it - the
 * guard under test refuses loopback, so the dialer stands in for "the network behind the pinned address".
 */
public final class CannedHttpServer implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptor;
    private final AtomicInteger connections = new AtomicInteger();
    private final List<String> requestLines = new CopyOnWriteArrayList<>();
    private volatile String answer;

    public CannedHttpServer(String answer) throws IOException {
        this.answer = answer;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.acceptor = new Thread(this::serve, "canned-http");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** {@code HTTP/1.1 <status>} with the given extra header lines and an empty body. */
    public static String answer(int status, String... headerLines) {
        StringBuilder text = new StringBuilder("HTTP/1.1 ").append(status).append(" X\r\n");
        for (String line : headerLines) {
            text.append(line).append("\r\n");
        }
        return text.append("Content-Length: 0\r\nConnection: close\r\n\r\n").toString();
    }

    public void answerWith(String newAnswer) {
        this.answer = newAnswer;
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
    }

    public int connections() {
        return connections.get();
    }

    public List<String> requestLines() {
        return List.copyOf(requestLines);
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                connections.incrementAndGet();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                String line = reader.readLine();
                if (line != null) {
                    requestLines.add(line);
                }
                while (line != null && !line.isEmpty()) {
                    line = reader.readLine();
                }
                OutputStream out = socket.getOutputStream();
                out.write(answer.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } catch (IOException closedOrBroken) {
                // the server was closed, or a client went away - keep serving until closed
            }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
