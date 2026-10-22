package io.integrationplatform.routing.adapter.kafkatopic;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.apache.kafka.common.message.ApiMessageType;
import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.message.MetadataResponseData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.protocol.ByteBufferAccessor;
import org.apache.kafka.common.protocol.ObjectSerializationCache;
import org.apache.kafka.common.requests.ApiVersionsResponse;
import org.apache.kafka.common.requests.RequestHeader;

/**
 * An in-process fake Kafka broker (a plain socket listener, no Docker): it answers ApiVersions and Metadata with the
 * library's own message classes and RECORDS everything it sees - connections, bytes, request kinds - so a test can
 * say exactly what the real kafka-clients producer did. The metadata answer is a function of the call number, so a
 * test can make the first answer advertise another broker (E16 R1).
 */
final class FakeKafkaBroker implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptor;
    final AtomicInteger connections = new AtomicInteger();
    final AtomicInteger bytes = new AtomicInteger();
    /** The request kinds received, in order ("API_VERSIONS", "METADATA", "PRODUCE", ...). */
    final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger metadataCalls = new AtomicInteger();
    private final IntFunction<MetadataResponseData> metadata;

    /** @param metadata the Metadata answer for the n-th (1-based) Metadata request this broker receives */
    FakeKafkaBroker(IntFunction<MetadataResponseData> metadata) throws IOException {
        this.metadata = metadata;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.acceptor = Thread.ofVirtual().start(this::accept);
    }

    int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                connections.incrementAndGet();
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            while (true) {
                int size;
                try {
                    size = in.readInt();
                } catch (EOFException eof) {
                    return;
                }
                bytes.addAndGet(4);
                byte[] frame = new byte[size];
                in.readFully(frame);
                bytes.addAndGet(size);
                RequestHeader header = RequestHeader.parse(ByteBuffer.wrap(frame));
                ApiKeys api = header.apiKey();
                requests.add(api.name());
                ApiMessage answer = switch (api) {
                    case API_VERSIONS -> new ApiVersionsResponseData().setErrorCode((short) 0).setApiKeys(
                            ApiVersionsResponse.brokerApiVersions(ApiMessageType.ListenerType.BROKER, false, false));
                    case METADATA -> metadata.apply(metadataCalls.incrementAndGet());
                    default -> null; // PRODUCE and the rest: recorded above, never answered
                };
                if (answer == null) {
                    return;
                }
                out.write(frame(answer, api, header.apiVersion(), header.correlationId()));
                out.flush();
            }
        } catch (IOException | RuntimeException closed) {
            // the client hung up or sent something this fake does not speak: the recorded facts are the result
        }
    }

    private static byte[] frame(ApiMessage message, ApiKeys api, short version, int correlationId) {
        ResponseHeaderData header = new ResponseHeaderData().setCorrelationId(correlationId);
        short headerVersion = api.responseHeaderVersion(version);
        ObjectSerializationCache cache = new ObjectSerializationCache();
        int size = header.size(cache, headerVersion) + message.size(cache, version);
        ByteBuffer out = ByteBuffer.allocate(4 + size);
        out.putInt(size);
        ByteBufferAccessor writer = new ByteBufferAccessor(out);
        header.write(writer, cache, headerVersion);
        message.write(writer, cache, version);
        return out.array();
    }

    /** A Metadata answer: the given brokers and the topic either unavailable (no leader yet) or led by {@code leaderId}. */
    static MetadataResponseData metadata(String topic, Integer leaderId,
            MetadataResponseData.MetadataResponseBroker... brokers) {
        MetadataResponseData data = new MetadataResponseData().setClusterId("fake").setControllerId(-1);
        for (MetadataResponseData.MetadataResponseBroker broker : brokers) {
            data.brokers().add(broker);
        }
        MetadataResponseData.MetadataResponseTopic entry = new MetadataResponseData.MetadataResponseTopic()
                .setName(topic).setTopicId(org.apache.kafka.common.Uuid.randomUuid());
        if (leaderId == null) {
            entry.setErrorCode(org.apache.kafka.common.protocol.Errors.LEADER_NOT_AVAILABLE.code());
        } else {
            entry.partitions().add(new MetadataResponseData.MetadataResponsePartition().setPartitionIndex(0)
                    .setLeaderId(leaderId).setReplicaNodes(List.of(leaderId)).setIsrNodes(List.of(leaderId)));
        }
        data.topics().add(entry);
        return data;
    }

    static MetadataResponseData.MetadataResponseBroker broker(int id, String host, int port) {
        return new MetadataResponseData.MetadataResponseBroker().setNodeId(id).setHost(host).setPort(port);
    }

    @Override
    public void close() throws IOException {
        server.close();
        acceptor.interrupt();
    }
}
