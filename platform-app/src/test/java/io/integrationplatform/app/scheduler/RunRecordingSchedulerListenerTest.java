package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import io.integrationplatform.config.scheduler.JobDefinitionRow;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * E14 T-056 + T-062 (ADR-012 sections 3 and 6, JVM red): the run/alert wrapper's outcome and incident rules.
 * One job_run row per executed run - outcome SUCCEEDED/FAILED, a late flag, instance, error class, NO payload and
 * no tenant data anywhere. A failed run or a run over its max_run_seconds writes one FAILED alert; over the
 * maximum run time it is FAILED regardless of the body's result. The sink port stands for the
 * scheduler.job_run/job_alert tables (the JDBC side is proven against PostgreSQL by the container suite).
 */
class RunRecordingSchedulerListenerTest {

    static final Instant STARTED = Instant.parse("2026-10-13T10:00:00Z");
    static final Instant DONE = STARTED.plusSeconds(12);

    /** Captures job_run rows, alert attempts and whether each alert row was actually inserted. */
    static final class RecordingSink implements JobIncidentSink {

        final List<JobRunRow> runs = new ArrayList<>();
        final List<String> alerts = new ArrayList<>();
        boolean alertInserts = true;

        @Override
        public boolean raiseAlert(String jobType, Instant scheduledFor, String kind, String errorClass, String message) {
            alerts.add(jobType + "/" + scheduledFor + "/" + kind);
            return alertInserts;
        }

        @Override
        public void recordRun(JobRunRow row) {
            runs.add(row);
        }
    }

    static final JobDefinitionRow ROW =
            new JobDefinitionRow("DELIVERY_RETRY", "FIXED_DELAY", "5", true, false, 300, 60, null);

    private static final class RowRepository implements io.integrationplatform.config.scheduler.JobDefinitionRepository {

        @Override
        public Optional<JobDefinitionRow> find(String jobType) {
            return Optional.of(ROW);
        }

        @Override
        public List<JobDefinitionRow> all() {
            return List.of(ROW);
        }
    }

    private static ExecutionComplete execution(Instant scheduledFor, Instant started, Instant done, boolean ok, Throwable cause) {
        Execution execution = new Execution(scheduledFor, new TaskInstance("DELIVERY_RETRY", "recurring"), true, "instance-a", null, null, 0, null, 1);
        return ok ? ExecutionComplete.success(execution, started, done) : ExecutionComplete.failure(execution, started, done, cause);
    }

    @Test
    void aSucceededRunIsRecordedWithoutAnAlert() {
        RecordingSink sink = new RecordingSink();
        MeterRegistry meters = new SimpleMeterRegistry();
        RunRecordingSchedulerListener listener = new RunRecordingSchedulerListener(sink, new RowRepository(), meters);

        listener.onExecutionComplete(execution(STARTED.minusSeconds(1), STARTED, DONE, true, null));

        assertThat(sink.runs).hasSize(1);
        JobRunRow row = sink.runs.getFirst();
        assertThat(row.jobType()).isEqualTo("DELIVERY_RETRY");
        assertThat(row.outcome()).isEqualTo("SUCCEEDED");
        assertThat(row.started()).isEqualTo(STARTED);
        assertThat(row.finished()).isEqualTo(DONE);
        assertThat(row.instance()).isEqualTo("instance-a");
        assertThat(row.isLate()).isFalse();
        assertThat(row.errorClass()).isNull();
        assertThat(sink.alerts).isEmpty();
        assertThat(meters.counter("platform.scheduler.job.runs", "outcome", "SUCCEEDED").count()).isEqualTo(1.0);
    }

    @Test
    void aFailedRunIsRecordedWithOneFailedAlert() {
        RecordingSink sink = new RecordingSink();
        MeterRegistry meters = new SimpleMeterRegistry();
        RunRecordingSchedulerListener listener = new RunRecordingSchedulerListener(sink, new RowRepository(), meters);

        listener.onExecutionComplete(execution(STARTED.minusSeconds(1), STARTED, DONE, false, new IllegalStateException("boom")));

        JobRunRow row = sink.runs.getFirst();
        assertThat(row.outcome()).isEqualTo("FAILED");
        assertThat(row.errorClass()).isEqualTo(IllegalStateException.class.getName());
        assertThat(sink.alerts).containsExactly("DELIVERY_RETRY/" + STARTED.minusSeconds(1) + "/FAILED");
        assertThat(meters.counter("platform.scheduler.job.runs", "outcome", "FAILED").count()).isEqualTo(1.0);
    }

    @Test
    void aRunOverItsMaxRunSecondsIsFailedWithOneFailedAlert() {
        RecordingSink sink = new RecordingSink();
        RunRecordingSchedulerListener listener =
                new RunRecordingSchedulerListener(sink, new RowRepository(), new SimpleMeterRegistry());

        // the row allows 300 s; this run ran 301 s and its body did not throw
        listener.onExecutionComplete(execution(STARTED.minusSeconds(1), STARTED, STARTED.plusSeconds(301), true, null));

        JobRunRow row = sink.runs.getFirst();
        assertThat(row.outcome()).isEqualTo("FAILED");
        assertThat(row.errorClass()).isEqualTo("MaxRunSecondsExceeded");
        assertThat(sink.alerts).hasSize(1);
        assertThat(sink.alerts.getFirst()).endsWith("/FAILED");
    }

    @Test
    void aRunFiringAfterItsScheduledTimeIsFlaggedLate() {
        RecordingSink sink = new RecordingSink();
        RunRecordingSchedulerListener listener =
                new RunRecordingSchedulerListener(sink, new RowRepository(), new SimpleMeterRegistry());

        // scheduled for 10:00:00 (a restart window), ran at 10:09:00
        listener.onExecutionComplete(execution(STARTED, STARTED.plusSeconds(540), STARTED.plusSeconds(552), true, null));

        JobRunRow row = sink.runs.getFirst();
        assertThat(row.scheduledFor()).isEqualTo(STARTED);
        assertThat(row.isLate()).isTrue();
        assertThat(row.outcome()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aRunWithinItsWindowIsNotLate() {
        RecordingSink sink = new RecordingSink();
        RunRecordingSchedulerListener listener =
                new RunRecordingSchedulerListener(sink, new RowRepository(), new SimpleMeterRegistry());

        // 11s duration: started 1s before the scheduled instant is the normal poll pickup
        listener.onExecutionComplete(execution(STARTED, STARTED.minusSeconds(1), STARTED.plusSeconds(10), true, null));

        assertThat(sink.runs.getFirst().isLate()).isFalse();
    }

    @Test
    void jobRunRowsCarryNoPayloadOrTenantData() {
        RecordingSink sink = new RecordingSink();
        RunRecordingSchedulerListener listener =
                new RunRecordingSchedulerListener(sink, new RowRepository(), new SimpleMeterRegistry());

        listener.onExecutionComplete(execution(STARTED, STARTED, DONE, true, null));

        JobRunRow row = sink.runs.getFirst();
        assertThat(JobRunRow.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("payload", "tenantId", "tenant_id", "data");
        assertThat(row.counts()).isNull();
    }

    @Test
    void countsTheBodyReportedAreRecordedOnTheRowOnce() {
        RecordingSink sink = new RecordingSink();
        JobRunCounts counts = new JobRunCounts();
        RunRecordingSchedulerListener listener =
                new RunRecordingSchedulerListener(sink, new RowRepository(), new SimpleMeterRegistry(), counts);

        counts.put("dropped", 2);
        listener.onExecutionComplete(execution(STARTED, STARTED, DONE, true, null));
        listener.onExecutionComplete(execution(STARTED, STARTED, DONE, true, null));

        assertThat(sink.runs.get(0).counts()).isEqualTo("{\"dropped\":2}");
        assertThat(sink.runs.get(1).counts()).as("the next run does not inherit them").isNull();
    }

    @Test
    void aFailedRunKeepsTheCountsItReportedBeforeFailing() {
        RecordingSink sink = new RecordingSink();
        JobRunCounts counts = new JobRunCounts();
        RunRecordingSchedulerListener listener =
                new RunRecordingSchedulerListener(sink, new RowRepository(), new SimpleMeterRegistry(), counts);

        counts.put("failedTenants", 1);
        listener.onExecutionComplete(execution(STARTED, STARTED, DONE, false, new IllegalStateException("x")));

        assertThat(sink.runs.getFirst().counts()).isEqualTo("{\"failedTenants\":1}");
    }
}