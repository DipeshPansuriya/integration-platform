package io.integrationplatform.app.audit;

import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * row278 D2/M3: runs {@link AuditOutboxRelay} for the life of the application on its own thread (the
 * {@code OutboxRelayLoop} pattern - the arch rules forbid schedulers), resting {@code idle} when a cycle moved
 * nothing; a failed cycle is logged and the next runs after the rest.
 */
final class AuditOutboxRelayLoop implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AuditOutboxRelayLoop.class);

    private final AuditOutboxRelay relay;
    private final Duration idle;
    private volatile boolean running;
    private volatile Thread thread;

    AuditOutboxRelayLoop(AuditOutboxRelay relay, Duration idle) {
        this.relay = Objects.requireNonNull(relay, "relay");
        this.idle = Objects.requireNonNull(idle, "idle");
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().daemon().name("audit-outbox-relay").start(this::cycleUntilStopped);
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
            int moved;
            try {
                moved = relay.runOnce();
            } catch (RuntimeException e) {
                LOG.warn("audit relay cycle failed - the next cycle runs after {} ms: {}", idle.toMillis(),
                        e.toString());
                moved = 0;
            }
            if (moved == 0) {
                try {
                    Thread.sleep(idle);
                } catch (InterruptedException e) {
                    return; // stop() interrupts the rest
                }
            }
        }
    }
}
