package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import io.integrationplatform.config.scheduler.JobDefinitionRepository;
import io.integrationplatform.config.scheduler.JobDefinitionRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * E14 T-056 (ADR-012 Decision 2): the job_definition row IS the schedule - db-scheduler's schedule objects are
 * built from it, and a row change takes effect at the next schedule sync point (the next computation, which the
 * sync triggers via db-scheduler's own startup reconcile). The row is read fresh on every computation, so a
 * changed row is honored immediately, and a paused or disabled row disables the task (never scheduled).
 *
 * <pre>
 * Scenario: A job_definition row change takes effect at the next schedule sync
 *   Given a job row whose schedule (delay or pattern) is changed
 *   When the next schedule computation or the schedule sync runs
 *   Then the task's next execution follows the new row, and a paused row disables the task
 * </pre>
 */
class JobDefinitionScheduleTest {

    static final JobDefinitionRow DELIVERY_RETRY =
            new JobDefinitionRow("DELIVERY_RETRY", "FIXED_DELAY", "30", true, false, 300, 60, null);

    static final JobDefinitionRow DAILY_REPORT =
            new JobDefinitionRow("DAILY_REPORT", "CRON", "0 5 0 * * *", true, false, 1800, 600, null);

    /** A repository whose current row the test replaces to prove the row (not a cached schedule) drives timing. */
    static final class RowRepository implements JobDefinitionRepository {

        JobDefinitionRow row;

        @Override
        public Optional<JobDefinitionRow> find(String jobType) {
            return Optional.ofNullable(row);
        }

        @Override
        public List<JobDefinitionRow> all() {
            return row == null ? List.of() : List.of(row);
        }
    }

    static final Instant RUN_DONE = Instant.parse("2026-10-13T01:00:00Z");

    private static ExecutionComplete completedAt(Instant done) {
        Execution execution = new Execution(done, new TaskInstance("T", "recurring"), false, "instance-a", done, null, 0, null, 1);
        return ExecutionComplete.success(execution, done.minusSeconds(1), done);
    }

    @Test
    void fixedDelayRowComputesNextFireFromTheRow() {
        RowRepository repo = new RowRepository();
        repo.row = DELIVERY_RETRY;

        JobDefinitionSchedule schedule = new JobDefinitionSchedule("DELIVERY_RETRY", repo);

        assertThat(schedule.getNextExecutionTime(completedAt(RUN_DONE)))
                .isEqualTo(RUN_DONE.plusSeconds(30));
    }

    @Test
    void aRowChangeTakesEffectAtTheNextComputation() {
        RowRepository repo = new RowRepository();
        repo.row = new JobDefinitionRow("DELIVERY_RETRY", "FIXED_DELAY", "60", true, false, 300, 60, null);
        JobDefinitionSchedule schedule = new JobDefinitionSchedule("DELIVERY_RETRY", repo);

        Instant before = schedule.getNextExecutionTime(completedAt(RUN_DONE));

        // the row is updated (the schedule sync happens next); the next computation reads the new row
        repo.row = new JobDefinitionRow("DELIVERY_RETRY", "FIXED_DELAY", "30", true, false, 300, 60, null);
        Instant after = schedule.getNextExecutionTime(completedAt(RUN_DONE));

        assertThat(before).isEqualTo(RUN_DONE.plusSeconds(60));
        assertThat(after).isEqualTo(RUN_DONE.plusSeconds(30));
    }

    @Test
    void cronRowComputesTheNextFireAfterTheCompletedTime() {
        RowRepository repo = new RowRepository();
        repo.row = DAILY_REPORT;

        JobDefinitionSchedule schedule = new JobDefinitionSchedule("DAILY_REPORT", repo);

        // 01:00 UTC is past the day's 00:05 slot - the next fire is tomorrow's 00:05
        assertThat(schedule.getNextExecutionTime(completedAt(RUN_DONE)))
                .isEqualTo(Instant.parse("2026-10-14T00:05:00Z"));
    }

    @Test
    void aChangedCronPatternTakesEffect() {
        RowRepository repo = new RowRepository();
        repo.row = DAILY_REPORT;
        JobDefinitionSchedule schedule = new JobDefinitionSchedule("DAILY_REPORT", repo);

        repo.row = new JobDefinitionRow("DAILY_REPORT", "CRON", "0 6 0 * * *", true, false, 1800, 600, null);
        assertThat(schedule.getNextExecutionTime(completedAt(RUN_DONE)))
                .isEqualTo(Instant.parse("2026-10-14T00:06:00Z"));
    }

    @Test
    void aPausedOrDisabledOrMissingRowDisablesTheTask() {
        RowRepository repo = new RowRepository();
        JobDefinitionSchedule schedule = new JobDefinitionSchedule("DELIVERY_RETRY", repo);

        // missing row
        assertThat(schedule.isDisabled()).isTrue();
        assertThat(schedule.getNextExecutionTime(completedAt(RUN_DONE))).isEqualTo(Schedule.NEVER);

        // paused row
        repo.row = new JobDefinitionRow("DELIVERY_RETRY", "FIXED_DELAY", "30", true, true, 300, 60, null);
        assertThat(schedule.isDisabled()).isTrue();
        assertThat(schedule.getNextExecutionTime(completedAt(RUN_DONE))).isEqualTo(Schedule.NEVER);

        // enabled=false row
        repo.row = new JobDefinitionRow("DELIVERY_RETRY", "FIXED_DELAY", "30", false, false, 300, 60, null);
        assertThat(schedule.isDisabled()).isTrue();
    }

    @Test
    void anEnabledRowIsNeverDisabled() {
        RowRepository repo = new RowRepository();
        repo.row = DELIVERY_RETRY;
        JobDefinitionSchedule schedule = new JobDefinitionSchedule("DELIVERY_RETRY", repo);

        assertThat(schedule.isDisabled()).isFalse();
        assertThat(schedule.getNextExecutionTime(completedAt(RUN_DONE))).isNotEqualTo(Schedule.NEVER);
    }

    @Test
    void initialExecutionFollowsTheRow() {
        RowRepository repo = new RowRepository();
        repo.row = new JobDefinitionRow("DELIVERY_RETRY", "FIXED_DELAY", "5", true, false, 300, 60, null);
        JobDefinitionSchedule retry = new JobDefinitionSchedule("DELIVERY_RETRY", repo);

        Instant now = Instant.parse("2026-10-13T12:00:00Z");
        assertThat(retry.getInitialExecutionTime(now)).isEqualTo(now);

        repo.row = DAILY_REPORT;
        assertThat(new JobDefinitionSchedule("DAILY_REPORT", repo).getInitialExecutionTime(now))
                .isEqualTo(Instant.parse("2026-10-14T00:05:00Z"));
    }

    @Test
    void determinismFollowsTheScheduleKind() {
        RowRepository repo = new RowRepository();
        repo.row = DAILY_REPORT;

        assertThat(new JobDefinitionSchedule("DAILY_REPORT", repo).isDeterministic()).isTrue();
        repo.row = DELIVERY_RETRY;
        assertThat(new JobDefinitionSchedule("DELIVERY_RETRY", repo).isDeterministic()).isFalse();
    }
}