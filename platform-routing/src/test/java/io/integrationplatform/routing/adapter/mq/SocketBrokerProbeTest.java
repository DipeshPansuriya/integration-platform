package io.integrationplatform.routing.adapter.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T-126: the probe dials the guard's pinned address, never a fresh lookup, and a refused address is never dialled. */
class SocketBrokerProbeTest {

    private final List<InetSocketAddress> dialled = new ArrayList<>();

    private SocketBrokerProbe probe(String resolvesTo) {
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("mq.partner.example")),
                host -> List.of(InetAddress.getByName(resolvesTo)));
        return new SocketBrokerProbe(guard, (pinned, ms) -> {
            dialled.add(pinned);
            return new Socket();
        });
    }

    @Test
    void dialsThePinnedAddressOnTheBrokerPort() throws IOException {
        probe("93.184.216.34").probe("mq.partner.example", 61616, "Q", Duration.ofSeconds(1));

        assertThat(dialled).singleElement().satisfies(a -> {
            assertThat(a.getAddress().getHostAddress()).isEqualTo("93.184.216.34");
            assertThat(a.getPort()).isEqualTo(61616);
        });
    }

    @Test
    void aNameThatResolvesToAPrivateAddressIsNeverDialled() {
        assertThatThrownBy(() -> probe("10.0.0.5").probe("mq.partner.example", 61616, "Q", Duration.ofSeconds(1)))
                .isInstanceOf(IOException.class);
        assertThat(dialled).isEmpty();
    }
}
