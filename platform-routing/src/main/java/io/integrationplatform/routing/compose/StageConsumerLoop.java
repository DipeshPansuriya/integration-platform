package io.integrationplatform.routing.compose;

import io.integrationplatform.routing.stage.StageAlerts;
import io.integrationplatform.routing.stage.StageConsumer;
import io.integrationplatform.routing.stage.StageSettings;
import io.integrationplatform.routing.stage.WorkRecordParser;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;

/**
 * One stage consumer running for the life of the application (T-049 composition; ADR-010 section 2): its own thread
 * owns its Kafka consumer (not thread-safe), subscribes the stage's one work topic and drains through the E8 stage
 * base - protected path, poison, per-record commit, the database breaker and back-off (T-111/T-113/T-181/T-191).
 * A turn that fails outside the base's own handling is logged and the loop goes on after a pause; stopping wakes the
 * consumer up and closes it on its own thread.
 *
 * <p>It starts when the application is ready - after the Camel context started, so a claimed delivery always finds
 * its routes - and a group that has no committed offset yet starts at the earliest record: both stages are
 * idempotent (stage 1's {@code ACCEPTED -> ROUTED} guard and UNIQUE triple, stage 2's claim), so re-reading retained
 * work is harmless while skipping it would lose it.
 */
final class StageConsumerLoop implements SmartLifecycle, ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StageConsumerLoop.class);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration BACKOFF_CAP = Duration.ofSeconds(30);

    private final String bootstrapServers;
    private final String group;
    private final String topic;
    private final StageConsumer.ProtectedRows rows;
    private final StageConsumer.Handler handler;
    private final StageConsumer.PoisonSink poison;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final java.util.function.BooleanSupplier ready;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile KafkaConsumer<byte[], byte[]> consumer;

    StageConsumerLoop(String bootstrapServers, String group, String topic, StageConsumer.ProtectedRows rows,
            StageConsumer.Handler handler, StageConsumer.PoisonSink poison, MeterRegistry meters,
            ObservationRegistry observations, java.util.function.BooleanSupplier ready) {
        this.bootstrapServers = Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        this.group = Objects.requireNonNull(group, "group");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.rows = Objects.requireNonNull(rows, "rows");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.poison = Objects.requireNonNull(poison, "poison");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.ready = Objects.requireNonNull(ready, "ready");
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent ready) {
        start();
    }

    @Override
    public boolean isAutoStartup() {
        return false; // started by onApplicationEvent(ApplicationReadyEvent); stopped by the lifecycle as usual
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofPlatform().daemon().name("stage-" + group).start(this::drainUntilStopped);
    }

    @Override
    public void stop() {
        running = false;
        KafkaConsumer<byte[], byte[]> polling = consumer;
        if (polling != null) {
            polling.wakeup();
        }
        Thread draining = thread;
        if (draining != null) {
            try {
                draining.join(Duration.ofSeconds(15));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void drainUntilStopped() {
        Map<String, Object> config = new HashMap<>(StageConsumer.consumerConfig(bootstrapServers, group));
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<byte[], byte[]> kafka = new KafkaConsumer<>(config)) {
            consumer = kafka;
            kafka.subscribe(List.of(topic));
            StageConsumer stage = new StageConsumer(kafka, new WorkRecordParser(), rows, handler, poison,
                    StageConsumerLoop::backoff, POLL, Clock.systemUTC(), Thread::sleep,
                    StageSettings.defaults(group), meters, observations, StageAlerts.LOG, ready);
            while (running) {
                try {
                    stage.drainOnce();
                } catch (WakeupException stopping) {
                    return;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    log.warn("stage consumer {}: a turn failed outside the stage base's handling - pausing {} ms: {}",
                            group, POLL.toMillis(), e.toString());
                    try {
                        Thread.sleep(POLL);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } catch (WakeupException stopping) {
            // stop() woke the consumer while it was subscribing or closing
        } finally {
            consumer = null;
        }
    }

    /** The partition pause after a transient error: 500 ms doubling, capped at 30 s (ADR-010 section 9). */
    static Duration backoff(int attempt) {
        long millis = 500L << Math.min(Math.max(attempt - 1, 0), 6);
        return Duration.ofMillis(Math.min(millis, BACKOFF_CAP.toMillis()));
    }
}
