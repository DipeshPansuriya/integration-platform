package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * E14 T-059 / T-142: the counts a job body reports travel to its {@code job_run} row. Aggregate numbers only - a count
 * name never carries a tenant id or a payload byte (ADR-012 section 4). Bound to the executing thread, so two jobs
 * running at once never see each other's numbers.
 */
class JobRunCountsTest {

    @Test
    void nothingRecordedGivesNoCounts() {
        assertThat(new JobRunCounts().take()).isNull();
    }

    @Test
    void recordedCountsComeBackOnceAsJsonThenAreGone() {
        JobRunCounts counts = new JobRunCounts();
        counts.put("dropped", 2);
        counts.put("kept", 1);

        assertThat(counts.take()).isEqualTo("{\"dropped\":2,\"kept\":1}");
        assertThat(counts.take()).as("taken once").isNull();
    }

    @Test
    void clearForgetsWhatAnEarlierRunLeftOnThisThread() {
        JobRunCounts counts = new JobRunCounts();
        counts.put("dropped", 9);

        counts.clear();

        assertThat(counts.take()).isNull();
    }

    @Test
    void anotherThreadNeverSeesTheseCounts() throws InterruptedException {
        JobRunCounts counts = new JobRunCounts();
        counts.put("dropped", 3);
        AtomicReference<String> other = new AtomicReference<>("untouched");

        Thread thread = new Thread(() -> other.set(counts.take()));
        thread.start();
        thread.join();

        assertThat(other.get()).isNull();
        assertThat(counts.take()).isEqualTo("{\"dropped\":3}");
    }
}
