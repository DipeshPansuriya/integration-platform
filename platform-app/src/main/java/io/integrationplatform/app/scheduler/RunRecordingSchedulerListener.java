package io.integrationplatform.app.scheduler;

import com.github.kagkarlsson.scheduler.event.SchedulerListener;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import io.integrationplatform.config.scheduler.JobDefinitionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * E14 T-056 + T-062 (ADR-012 sections 3 and 6): the run/alert wrapper's recording side. Every completed execution
 * of a registered task becomes one {@code scheduler.job_run} row:
 *
 * <ul>
 *   <li>a thrown body is run FAILED with its error class, plus exactly one FAILED alert (a run over the row's
 *       {@code max_run_seconds} is FAILED with {@code MaxRunSecondsExceeded}, whatever the body did);</li>
 *   <li>a run firing after a restart window (later than its row's {@code late_threshold_seconds} after
 *       {@code scheduled_for}) is flagged late - db-scheduler has no missed concept of its own, so the flag rides
 *       on the run row; an execution never claimed at all is the MISSED case of {@link JobOverdueDetector};</li>
 *   <li>no payload, no tenant id, no per-tenant counts anywhere in the row (ADR-012 section 4).</li>
 * </ul>
 *
 * <p>Recording failing must never take a run down - every write is caught and logged.</p>
 */
@Component
public class RunRecordingSchedulerListener implements SchedulerListener {

    static final String OVER_MAX_RUN_ERROR_CLASS = "MaxRunSecondsExceeded";

    private static final Logger LOG = LoggerFactory.getLogger(RunRecordingSchedulerListener.class);

    private final JobIncidentSink sink;
    private final JobDefinitionRepository repository;
    private final MeterRegistry meters;

    public RunRecordingSchedulerListener(JobIncidentSink sink, JobDefinitionRepository repository,
            MeterRegistry meters) {
        this.sink = sink;
        this.repository = repository;
        this.meters = meters;
    }

    @Override
    public void onExecutionComplete(ExecutionComplete complete) {
        try {
            Execution execution = complete.getExecution();
            String jobType = execution.getTaskName();
            Instant scheduledFor = execution.getExecutionTime();
            // db-scheduler carries the started time only as done - duration, so it is recovered here
            Instant started = complete.getTimeDone().minus(complete.getDuration());
            Instant finished = complete.getTimeDone();

            io.integrationplatform.config.scheduler.JobDefinitionRow row = repository.find(jobType).orElse(null);
            int maxRunSeconds = row == null ? 3600 : row.maxRunSeconds();
            int lateThresholdSeconds = row == null ? 300 : row.lateThresholdSeconds();

            boolean overMaxRun = complete.getDuration().getSeconds() > maxRunSeconds;
            boolean failed = overMaxRun || complete.getResult() == ExecutionComplete.Result.FAILED;
            String errorClass = !failed ? null
                    : overMaxRun ? OVER_MAX_RUN_ERROR_CLASS
                    : complete.getCause().map(cause -> cause.getClass().getName()).orElse("Error");
            boolean late = started.isAfter(scheduledFor.plusSeconds(lateThresholdSeconds));

            sink.recordRun(new JobRunRow(jobType, scheduledFor, started, finished, execution.pickedBy,
                    failed ? "FAILED" : "SUCCEEDED", late, errorClass, null));
            if (failed) {
                sink.raiseAlert(jobType, scheduledFor, "FAILED", errorClass,
                        overMaxRun ? "run over its max_run_seconds" : "run failed");
            }
            meters.counter("platform.scheduler.job.runs", "outcome", failed ? "FAILED" : "SUCCEEDED").increment();
        } catch (RuntimeException e) {
            LOG.error("recording the completed run failed; the run itself is unaffected", e);
        }
    }

    // the other scheduler callbacks carry no recording - the wrapper records completed runs only
    @Override
    public void onExecutionScheduled(com.github.kagkarlsson.scheduler.task.TaskInstanceId taskInstanceId,
            Instant executionTime) {
        // not recorded: a scheduled execution is not a run
    }

    @Override
    public void onExecutionStart(com.github.kagkarlsson.scheduler.CurrentlyExecuting currentlyExecuting) {
        // not recorded
    }

    @Override
    public void onExecutionDead(Execution execution) {
        // not recorded: a dead execution is revived or rescheduled, then completes like any run
    }

    @Override
    public void onExecutionFailedHeartbeat(com.github.kagkarlsson.scheduler.CurrentlyExecuting currentlyExecuting) {
        // not recorded: heartbeat loss ends in onExecutionDead or a completed run
    }

    @Override
    public void onSchedulerEvent(com.github.kagkarlsson.scheduler.event.SchedulerListener.SchedulerEventType type) {
        // internal scheduler event
    }

    @Override
    public void onCandidateEvent(com.github.kagkarlsson.scheduler.event.SchedulerListener.CandidateEventType type) {
        // internal scheduler event
    }
}