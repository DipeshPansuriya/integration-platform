package io.integrationplatform.routing.adapter.kafkatopic;

import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import io.integrationplatform.config.destination.Refusal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;

/**
 * E16 Karan F7 (CWE-918): the brokers a cluster ADVERTISES for the topic - the leaders the producer will dial and
 * their replicas - go through the one destination guard (allow-list, refused ranges, platform hosts, every resolved
 * address) before a record is handed to the producer. Without it a broker could answer the metadata request with an
 * internal address and the producer would connect there on the platform's behalf.
 */
final class AdvertisedBrokers {

    private AdvertisedBrokers() {
    }

    static void require(List<PartitionInfo> partitions, DestinationGuard guard)
            throws DestinationRefusedException, UnknownHostException {
        Set<String> checked = new LinkedHashSet<>();
        for (PartitionInfo partition : partitions) {
            check(partition.leader(), guard, checked);
            if (partition.replicas() != null) {
                for (Node replica : partition.replicas()) {
                    check(replica, guard, checked);
                }
            }
        }
    }

    private static void check(Node node, DestinationGuard guard, Set<String> checked)
            throws DestinationRefusedException, UnknownHostException {
        if (node == null || node.isEmpty()) {
            return;
        }
        String host = node.host() == null ? "" : node.host().strip();
        if (!checked.add(host + ":" + node.port())) {
            return;
        }
        String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        URI address;
        try {
            address = new URI("http://" + authority + ":" + node.port());
        } catch (URISyntaxException malformed) {
            throw new DestinationRefusedException(Refusal.NO_HOST, null);
        }
        guard.check(address);
    }
}
