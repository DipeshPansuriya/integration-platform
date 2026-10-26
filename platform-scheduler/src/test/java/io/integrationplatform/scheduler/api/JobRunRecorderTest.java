package io.integrationplatform.scheduler.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobRunRecorderTest {

    @Test
    void aRecorderAcceptsARunEntry() {
        List<JobRunRecorder.JobRun> captured = new ArrayList<>();
        JobRunRecorder recorder = captured::add;
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        JobRunRecorder.JobRun run = new JobRunRecorder.JobRun(
                "DAILY_REPORT", now, now, now.plusSeconds(1), "instance-1", "SUCCEEDED", false, null, "1");
        recorder.recordRun(run);
        assertThat(captured).containsExactly(run);
    }
}
