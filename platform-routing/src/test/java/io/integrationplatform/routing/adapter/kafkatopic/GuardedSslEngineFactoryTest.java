package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** E16 Karan R1: the TLS engine factory is the in-process veto on every node the client dials. */
class GuardedSslEngineFactoryTest {

    private static final DestinationGuard GUARD = new DestinationGuard(AllowList.of(List.of("*.partner.example")),
            host -> List.of(InetAddress.getByName("93.184.216.34")));

    private static GuardedSslEngineFactory configured(AtomicReference<Exception> veto) {
        Properties props = KafkaClientsSender.producerProperties("kafka.partner.example:9092", Duration.ofSeconds(5));
        props.put("security.protocol", "SSL");
        props.put("ssl.endpoint.identification.algorithm", "https");
        GuardedSslEngineFactory.install(props, GUARD, veto);
        Map<String, Object> configs = new HashMap<>(new ProducerConfig(props).values());
        configs.put(GuardedSslEngineFactory.GUARD_KEY, GUARD);
        configs.put(GuardedSslEngineFactory.VETO_KEY, veto);
        GuardedSslEngineFactory factory = new GuardedSslEngineFactory();
        factory.configure(configs);
        return factory;
    }

    @Test
    void anApprovedPeerGetsARealClientEngineWithHostnameVerification() {
        AtomicReference<Exception> veto = new AtomicReference<>();
        SSLEngine engine = configured(veto).createClientSslEngine("b1.partner.example", 9093, "https");

        assertThat(engine.getUseClientMode()).isTrue();
        assertThat(engine.getSSLParameters().getEndpointIdentificationAlgorithm()).isEqualTo("https");
        assertThat(veto.get()).isNull();
    }

    @Test
    @Tag("security")
    void aRefusedPeerGetsNoEngineAndTheRefusalIsParkedForTheSender() {
        AtomicReference<Exception> veto = new AtomicReference<>();
        GuardedSslEngineFactory factory = configured(veto);

        for (String peer : List.of("10.0.0.5", "169.254.169.254", "127.0.0.1", "[::1]", "broker.attacker.example")) {
            assertThatThrownBy(() -> factory.createClientSslEngine(peer, 9093, "https"))
                    .isInstanceOf(KafkaException.class).hasMessageNotContaining(peer.replace("[", "").replace("]", ""));
            assertThat(veto.getAndSet(null)).as(peer).isInstanceOf(DestinationRefusedException.class);
        }
    }

    @Test
    @Tag("security")
    void withoutTheGuardInstalledTheFactoryRefusesToStart() {
        assertThatThrownBy(() -> new GuardedSslEngineFactory().configure(Map.of()))
                .isInstanceOf(KafkaException.class);
    }
}
