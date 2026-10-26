package io.integrationplatform.routing.adapter.db;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * E16 F3 (design note task 11, Karan change 4): the account's REAL privileges on the exact target table, asked of the
 * target database itself over a pinned, credentialed JDBC connection (the send path's dial: every driver option on
 * the Properties object, the guard-pinned address, TLS as configured). Empty when the target names no table (a probe
 * that cannot name a table reports nothing); refusals are answers, never exceptions that escape the answer shape.
 */
public interface DbPrivilegeProbe {

    /**
     * @return the privilege types the connected account holds on the target table (Karan change 4)
     * @throws IOException the target database did not answer
     * @throws ProbeRefused the probe was refused or missing an input - {@code reason} is the answer's reason key
     */
    List<String> probe(UUID systemId, DbSettings settings, InetAddress pinnedAddress, Duration timeout)
            throws IOException, ProbeRefused;

    /** A refusal as an answer, not an exception of the transport kind: the reason key the response carries. */
    class ProbeRefused extends Exception {

        public final String reason;

        public ProbeRefused(String reason, String message) {
            super(message);
            this.reason = reason;
        }
    }
}