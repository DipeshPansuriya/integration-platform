package io.integrationplatform.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.integrationplatform.delivery.internal.retry.OutcomeRecorder;
import io.integrationplatform.delivery.internal.retry.RetryPolicy;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.ingest.port.StageTimings.Stage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * E18-facing check (lane deliver-deadletter): a message whose delivery DEAD_LETTERS has the four-stage timeline
 * INTAKE, ROUTE, TRANSFORM, DELIVER - the DELIVER stage is no longer missing for any dead-letter path. The timing
 * log here keeps the writer's contract (one row per message and stage, a repeat writes nothing, a stage never
 * finishes before it starts); the real table and its tenant are proven by the container test.
 */
class DeadLetterFourStageTimelineTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000e1800");
    private static final UUID MESSAGE = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000e1800");
    private static final UUID DELIVERY = UUID.fromString("dddddddd-0000-0000-0000-0000000e1800");
    private static final UUID TOKEN = UUID.fromString("cccccccc-0000-0000-0000-0000000e1800");
    private static final Instant T0 = Instant.parse("2026-10-01T09:00:00Z");

    private final JdbcDeliveryRepository repository = mock(JdbcDeliveryRepository.class);
    private final MessageTimeline timeline = new MessageTimeline();
    private final JdbcDeliveryRetryStore store =
            new JdbcDeliveryRetryStore(repository, mock(JdbcClient.class), () -> Optional.of(TENANT), timeline);

    private void upstreamStagesRan() {
        timeline.record(MESSAGE, Stage.INTAKE, T0, T0.plusMillis(40));
        timeline.record(MESSAGE, Stage.ROUTE, T0.plusMillis(50), T0.plusMillis(60));
        timeline.record(MESSAGE, Stage.TRANSFORM, T0.plusMillis(70), T0.plusMillis(120));
    }

    private void assertFourStageTimeline() {
        assertThat(timeline.stages()).containsExactly(Stage.INTAKE, Stage.ROUTE, Stage.TRANSFORM, Stage.DELIVER);
        assertThat(timeline.rows).allSatisfy(row -> assertThat(row.finishedAt()).isAfterOrEqualTo(row.startedAt()));
        assertThat(timeline.rows.get(3).finishedAt()).as("DELIVER is finished at the dead letter itself")
                .isAfterOrEqualTo(T0.plusMillis(120));
    }

    @Test
    @DisplayName("the credential dead letter completes the four-stage timeline")
    void theCredentialDeadLetterCompletesTheTimeline() {
        when(repository.deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.PERMANENT), any(String.class), any()))
                .thenReturn(true);
        upstreamStagesRan();

        store.deadLetter(DELIVERY, TOKEN, OutcomeClass.PERMANENT, "target credential unreadable");

        assertFourStageTimeline();
    }

    @Test
    @DisplayName("the reconciler AMBIGUOUS dead letter completes the four-stage timeline")
    void theAmbiguousDeadLetterCompletesTheTimeline() {
        when(repository.deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.AMBIGUOUS), any())).thenReturn(true);
        upstreamStagesRan();

        store.deadLetter(DELIVERY, TOKEN, OutcomeClass.AMBIGUOUS);

        assertFourStageTimeline();
    }

    @Test
    @DisplayName("the recorder dead letter (send outcome) completes the four-stage timeline, once")
    void theRecorderDeadLetterCompletesTheTimelineOnce() {
        // the recorder owns its own stage write over a store built without timings (JdbcSendOutcomes' wiring)
        JdbcDeliveryRetryStore plainStore = new JdbcDeliveryRetryStore(repository, mock(JdbcClient.class),
                () -> Optional.of(TENANT), null);
        OutcomeRecorder recorder = new OutcomeRecorder(plainStore, RetryPolicy.defaults(), () -> 1.0, timeline);
        when(repository.deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.PERMANENT), any())).thenReturn(true);
        upstreamStagesRan();

        recorder.record(DELIVERY, TOKEN, 1, OutcomeClass.PERMANENT, T0.plusMillis(150), null, false);

        assertFourStageTimeline();
    }

    @Test
    @DisplayName("a repeated dead letter leaves the timeline at four rows")
    void aRepeatedDeadLetterLeavesFourRows() {
        when(repository.deadLetter(eq(DELIVERY), eq(TOKEN), eq(OutcomeClass.AMBIGUOUS), any())).thenReturn(true,
                false);
        upstreamStagesRan();

        store.deadLetter(DELIVERY, TOKEN, OutcomeClass.AMBIGUOUS);
        store.deadLetter(DELIVERY, TOKEN, OutcomeClass.AMBIGUOUS);

        assertFourStageTimeline();
    }

    /** The stage timing log of one message, with the real writer's idempotence (ON CONFLICT DO NOTHING). */
    private static final class MessageTimeline implements StageTimings {

        record Row(Stage stage, Instant startedAt, Instant finishedAt) {
        }

        final List<Row> rows = new ArrayList<>();

        @Override
        public void record(UUID messageId, Stage stage, Instant startedAt, Instant finishedAt) {
            add(new Row(stage, startedAt, finishedAt));
        }

        @Override
        public void recordDeliverFinished(UUID deliveryId, Instant finishedAt) {
            Instant startedAt = rows.get(rows.size() - 1).finishedAt();
            add(new Row(Stage.DELIVER, startedAt.isAfter(finishedAt) ? finishedAt : startedAt, finishedAt));
        }

        private void add(Row row) {
            if (rows.stream().noneMatch(existing -> existing.stage() == row.stage())) {
                rows.add(row);
            }
        }

        List<Stage> stages() {
            return rows.stream().map(Row::stage).toList();
        }
    }
}
