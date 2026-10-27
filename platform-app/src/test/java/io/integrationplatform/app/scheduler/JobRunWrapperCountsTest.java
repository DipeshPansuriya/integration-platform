package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.domain.scheduler.JobHandler;
import java.util.List;
import org.junit.jupiter.api.Test;

/** E14 T-059: the run wrapper starts every run with an empty count slate, so a pooled thread never leaks counts. */
class JobRunWrapperCountsTest {

    @Test
    void aRunStartsFromEmptyCountsEvenWhenThePreviousRunOnTheThreadLeftSome() {
        JobRunCounts counts = new JobRunCounts();
        counts.put("leftover", 7);
        JobHandler handler = new JobHandler() {
            @Override
            public String type() {
                return SchedulerJobTasks.DAILY_REPORT;
            }

            @Override
            public void run(String jobData) {
                counts.put("refreshed", 1);
            }
        };

        new JobRunWrapper(List.of(handler), counts).run(SchedulerJobTasks.DAILY_REPORT);

        assertThat(counts.take()).isEqualTo("{\"refreshed\":1}");
    }
}
