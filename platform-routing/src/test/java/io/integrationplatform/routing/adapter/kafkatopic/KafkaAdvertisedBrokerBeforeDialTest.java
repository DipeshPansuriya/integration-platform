package io.integrationplatform.routing.adapter.kafkatopic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.config.destination.DestinationRefusedException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E16 Karan R1 (High, CWE-918 / CWE-367), no Docker: a REAL kafka-clients producer against in-process fake brokers.
 *
 * <p>The tenant's own, allow-listed broker answers the FIRST Metadata request with LEADER_NOT_AVAILABLE and a broker
 * list that names an internal node. kafka-clients 4.3.1 offers no config-level way to veto a connect before it
 * happens (see the lane report), so these tests pin down, honestly, two things:
 * <ul>
 *   <li>clear text (operator opt-in only): the client DOES dial the advertised internal node and sends it ApiVersions
 *       and Metadata in clear - the probe that only an egress allow-list closes. The late guard check still refuses
 *       the record: no Produce request is ever sent anywhere. These assertions fail if the late check is bypassed.</li>
 *   <li>TLS (the only tenant choice by default): the SslEngineFactory veto refuses a node BEFORE the ClientHello, so
 *       the refused listener sees a TCP connect and not one byte.</li>
 * </ul>
 */
class KafkaAdvertisedBrokerBeforeDialTest {

    private static final Map<String, String> DNS = Map.of("kafka.partner.example", "93.184.216.34");

    private static final DestinationGuard GUARD = new DestinationGuard(AllowList.of(List.of("*.partner.example")),
            host -> {
                String address = DNS.get(host);
                if (address == null) {
                    throw new UnknownHostException(host);
                }
                return List.of(InetAddress.getByName(address));
            });

    private static KafkaTopicSettings settings(String protocol) {
        return KafkaTopicSettings.fromJson("{\"bootstrapServers\":\"kafka.partner.example:9092\",\"topic\":\"orders\","
                + "\"securityProtocol\":\"" + protocol + "\"}");
    }

    /** The real producer, dialling the fake bootstrap listener instead of the (fake-DNS) public address. */
    private static KafkaClientsSender sender(int bootstrapPort) {
        return new KafkaClientsSender(GUARD, null, true, props -> {
            props.put("bootstrap.servers", "127.0.0.1:" + bootstrapPort);
            return new KafkaProducer<>(props);
        });
    }

    @Test
    @Tag("security")
    void clearTextClientDialsTheAdvertisedInternalNodeButTheLateGuardStopsEveryRecord() throws Exception {
        AtomicInteger internalPort = new AtomicInteger();
        try (FakeKafkaBroker internal = new FakeKafkaBroker(call -> FakeKafkaBroker.metadata("orders", 7,
                FakeKafkaBroker.broker(7, "127.0.0.1", internalPort.get())));
                FakeKafkaBroker bootstrap = new FakeKafkaBroker(call -> FakeKafkaBroker.metadata("orders", null,
                        FakeKafkaBroker.broker(7, "127.0.0.1", internalPort.get())))) {
            internalPort.set(internal.port());

            assertThatThrownBy(() -> sender(bootstrap.port()).send(settings("PLAINTEXT"), null, UUID.randomUUID(),
                    new byte[] {1}, Duration.ofSeconds(5))).isInstanceOf(DestinationRefusedException.class);

            // the residual (what only egress control closes): the client followed the advertised list and spoke to it
            assertThat(internal.connections.get()).as("the client dialled the advertised internal node").isPositive();
            assertThat(internal.requests).as("...and sent it Kafka requests in clear").contains("API_VERSIONS");
            // what the guard guarantees: the record itself never left, to either broker
            assertThat(internal.requests).doesNotContain("PRODUCE");
            assertThat(bootstrap.requests).doesNotContain("PRODUCE");
        }
    }

    @Test
    @Tag("security")
    void tlsClientIsVetoedBeforeTheClientHelloSoTheRefusedNodeSeesNoBytes() throws Exception {
        try (FakeKafkaBroker refused = new FakeKafkaBroker(call -> FakeKafkaBroker.metadata("orders", 7,
                FakeKafkaBroker.broker(7, "127.0.0.1", 0)))) {
            // 127.0.0.1 is a refused range: the TLS client may connect but must not even say hello to it
            assertThatThrownBy(() -> sender(refused.port()).send(settings("SSL"), null, UUID.randomUUID(),
                    new byte[] {1}, Duration.ofSeconds(3))).isInstanceOf(DestinationRefusedException.class);

            assertThat(refused.bytes.get()).as("no ClientHello reached the refused node").isZero();
            assertThat(refused.requests).isEmpty();
        }
    }
}
