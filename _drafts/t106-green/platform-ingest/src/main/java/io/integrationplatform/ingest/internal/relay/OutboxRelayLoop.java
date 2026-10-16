package io.integrationplatform.ingest.internal.relay;

import io.integrationplatform.ingest.port.OutboxRelay;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the outbox relay for the life of the application (T-049 composition, ADR-005): one cycle after another on its
 * own thread, resting {@code idle} when a cycle published nothing. The relay's own controls (T-191: the Kafka breaker,
 * the per-bucket back-off, the age alert) decide about the broker; this loop only keeps cycling - a failed cycle is
 * logged and the next one runs after the rest, so the relay never stops on one bad cycle and never spins hot.
 */
final class OutboxRelayLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayLoop.class);

    private final OutboxRelay relay;
    private final Duration idle;
    private volatile boolean running;
    private volatile Thread thread;

    OutboxRelayLoop(OutboxRelay relay, Duration idle) {
        this.relay = Objects.requireNonNull(relay, "relay");
        this.idle = Objects.requireNonNull(idle, "idle");
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().daemon().name("outbox-relay").start(this::cycleUntilStopped);
    }

    @Override
    public void stop() {
        running = false;
        Thread cycling = thread;
        if (cycling != null) {
            cycling.interrupt();
            try {
                cycling.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void cycleUntilStopped() {
        while (running) {
            int published;
            try {
                published = relay.runOnce();
            } catch (RuntimeException e) {
                log.warn("outbox relay cycle failed - the next cycle runs after {} ms: {}", idle.toMillis(),
                        e.toString());
                published = 0;
            }
            if (published == 0) {
                try {
                    Thread.sleep(idle);
                } catch (InterruptedException e) {
                    return; // stop() interrupts the rest
                }
            }
        }
    }
}
