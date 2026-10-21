package io.integrationplatform.e10.archfixture.planted;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/** T-184 planted violation: an adapter that resolves and dials on its own, past the one destination guard. */
public final class PlantedDialOutsideGuard {

    public Socket dialOnItsOwn(String host, int port) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        InetAddress one = InetAddress.getByName(host);
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 1_000);
        return all.length > 0 && one != null ? socket : new Socket(host, port);
    }
}
