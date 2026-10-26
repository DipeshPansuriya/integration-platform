package io.integrationplatform.routing.compose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;

/**
 * E18 regression (T-177 follow-up): Spring's test context cache pauses an idle cached context and restarts it when a
 * later class reuses it ({@code pause()} / {@code restart()}); restart starts only beans whose
 * {@code isAutoStartup()} is true. The stage consumer loop answered false forever, so a resumed context had no stage
 * consumers and stage 1 / stage 2 never ran. It must come back after a restart, but must still not start before the
 * application is ready (after the Camel context started).
 */
@DisplayName("the stage consumer loop survives a context pause and restart")
class StageConsumerLoopRestartTest {

    private static StageConsumerLoop loop() {
        // port 1 is never reached: the consumer is constructed lazily and only the lifecycle state is observed
        return new StageConsumerLoop("localhost:1", "restart-test", "restart.topic", mock(io.integrationplatform.routing.stage.StageConsumer.ProtectedRows.class),
                (record, tenant) -> { }, (key, value, reason) -> { }, new SimpleMeterRegistry(),
                ObservationRegistry.NOOP, () -> true);
    }

    @Test
    @DisplayName("not auto-started before the application is ready; auto-started on restart after it ran once")
    void restartsAfterPause() {
        StageConsumerLoop loop = loop();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("loop", StageConsumerLoop.class, () -> loop);
            context.refresh();
            assertThat(loop.isAutoStartup()).as("not started by the context refresh: Camel starts first").isFalse();
            assertThat(loop.isRunning()).isFalse();

            loop.onApplicationEvent(null); // the ready event starts it
            assertThat(loop.isRunning()).isTrue();

            context.pause();
            assertThat(loop.isRunning()).as("pause stops the consumer").isFalse();

            context.restart();
            assertThat(loop.isRunning()).as("restart brings the consumer back").isTrue();
        } finally {
            loop.stop();
        }
    }
}
