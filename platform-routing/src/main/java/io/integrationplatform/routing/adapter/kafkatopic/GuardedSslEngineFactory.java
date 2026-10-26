package io.integrationplatform.routing.adapter.kafkatopic;

import io.integrationplatform.config.destination.DestinationGuard;
import java.net.URI;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.security.ssl.DefaultSslEngineFactory;

/**
 * E16 Karan R1: the only in-process veto kafka-clients 4.3.1 offers on a node the client dials. The producer asks its
 * {@code ssl.engine.factory.class} for an engine for EVERY connection - bootstrap nodes and the nodes the cluster
 * advertised alike - after the TCP connect is started and BEFORE the TLS ClientHello. This factory runs the peer
 * through the one {@link DestinationGuard} first; a refused peer gets no engine, so the channel fails and not one
 * byte (no ClientHello) reaches it. The refusal is parked in a holder the sender reads, so the call reports a
 * destination refusal (PERMANENT) rather than the client's timeout.
 *
 * <p>What it cannot do: the TCP connect itself (kafka-clients has no config-level hook for it - its HostResolver is
 * only reachable through package-private constructors) and clear-text protocols (no engine is built). Both are closed
 * by the operator: clear text is off by default ({@code kafka-allow-plaintext}) and the worker's egress is limited to
 * the approved broker addresses.
 *
 * <p>Public with a public constructor only because kafka-clients instantiates it by class.
 */
public final class GuardedSslEngineFactory extends DefaultSslEngineFactory {

    static final String GUARD_KEY = "ipass.destination.guard";
    static final String VETO_KEY = "ipass.destination.veto";

    private DestinationGuard guard;
    private AtomicReference<Exception> veto;

    /** Points the producer at this factory and hands it the guard and the refusal holder (objects ride the config map). */
    static void install(Properties props, DestinationGuard guard, AtomicReference<Exception> veto) {
        props.put(SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG, GuardedSslEngineFactory.class);
        props.put(GUARD_KEY, guard);
        props.put(VETO_KEY, veto);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void configure(Map<String, ?> configs) {
        Object g = configs.get(GUARD_KEY);
        Object v = configs.get(VETO_KEY);
        if (!(g instanceof DestinationGuard) || !(v instanceof AtomicReference)) {
            throw new KafkaException("the destination guard is not installed in the TLS engine factory");
        }
        this.guard = (DestinationGuard) g;
        this.veto = (AtomicReference<Exception>) v;
        super.configure(configs);
    }

    @Override
    public SSLEngine createClientSslEngine(String peerHost, int peerPort, String endpointIdentification) {
        try {
            String authority = peerHost.contains(":") && !peerHost.startsWith("[") ? "[" + peerHost + "]" : peerHost;
            guard.check(new URI("http://" + authority + ":" + peerPort));
        } catch (Exception refused) {
            veto.compareAndSet(null, refused);
            // no engine, no ClientHello; the text names no address
            throw new KafkaException("a broker address was refused by the destination guard");
        }
        return super.createClientSslEngine(peerHost, peerPort, endpointIdentification);
    }
}
