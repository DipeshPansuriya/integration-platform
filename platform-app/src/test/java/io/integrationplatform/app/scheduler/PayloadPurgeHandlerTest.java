package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.integrationplatform.app.retention.RetentionPolicy;
import io.integrationplatform.tenancy.port.PartitionPurge;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E14 T-142 / T-189 (ADR-012, ADR-014 Decision 3, ADR-016): the body of PAYLOAD_PURGE. It computes the cutoff from the
 * agreed 30-day payload retention and the clock, hands it to the maintenance-role purge, and reports aggregate counts
 * only. Ships OFF: a deployment must opt in to DRY_RUN or ENFORCE, because the purge deletes data.
 */
class PayloadPurgeHandlerTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate CUTOFF = LocalDate.parse("2026-09-19");

    /** Records every call; answers with two dropped and one kept day. */
    private static final class RecordingPurge implements PartitionPurge {
        final List<LocalDate> cutoffs = new ArrayList<>();
        final List<Boolean> dryRuns = new ArrayList<>();

        @Override
        public Outcome purgeBefore(LocalDate cutoff, boolean dryRun) {
            cutoffs.add(cutoff);
            dryRuns.add(dryRun);
            return new Outcome(dryRun, List.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02")),
                    List.of(LocalDate.parse("2026-09-03")));
        }
    }

    private static PayloadPurgeHandler handler(PartitionPurge purge, PayloadPurgeMode mode, JobRunCounts counts) {
        return new PayloadPurgeHandler(purge, RetentionPolicy.AGREED, NOW, counts, mode);
    }

    @Test
    void theRetentionIsThirtyDaysAndTheTypeIsPayloadPurge() {
        assertThat(RetentionPolicy.AGREED.payload()).isEqualTo(Period.ofDays(30));
        assertThat(handler(null, PayloadPurgeMode.OFF, new JobRunCounts()).type())
                .isEqualTo(SchedulerJobTasks.PAYLOAD_PURGE);
    }

    @Test
    void offByDefaultTouchesNothingAndSaysSo() {
        RecordingPurge purge = new RecordingPurge();
        JobRunCounts counts = new JobRunCounts();

        handler(purge, PayloadPurgeMode.OFF, counts).run("");

        assertThat(purge.cutoffs).as("OFF never reaches the database").isEmpty();
        assertThat(counts.take()).isEqualTo("{\"disabled\":1}");
    }

    @Test
    void offNeedsNoMaintenanceConnectionAtAll() {
        handler(null, PayloadPurgeMode.OFF, new JobRunCounts()).run("");
    }

    @Test
    void dryRunAsksForTheThirtyDayCutoffWithoutDroppingAndReportsWouldDrop() {
        RecordingPurge purge = new RecordingPurge();
        JobRunCounts counts = new JobRunCounts();

        handler(purge, PayloadPurgeMode.DRY_RUN, counts).run("");

        assertThat(purge.cutoffs).containsExactly(CUTOFF);
        assertThat(purge.dryRuns).containsExactly(true);
        assertThat(counts.take()).isEqualTo("{\"dryRun\":1,\"wouldDrop\":2,\"kept\":1}");
    }

    @Test
    void enforceDropsAndReportsDroppedAndKept() {
        RecordingPurge purge = new RecordingPurge();
        JobRunCounts counts = new JobRunCounts();

        handler(purge, PayloadPurgeMode.ENFORCE, counts).run("");

        assertThat(purge.cutoffs).containsExactly(CUTOFF);
        assertThat(purge.dryRuns).containsExactly(false);
        assertThat(counts.take()).isEqualTo("{\"dropped\":2,\"kept\":1}");
    }

    @Test
    void aModeThatNeedsTheDatabaseButHasNoMaintenanceConnectionFailsTheRunByName() {
        for (PayloadPurgeMode mode : new PayloadPurgeMode[] {PayloadPurgeMode.DRY_RUN, PayloadPurgeMode.ENFORCE}) {
            assertThatThrownBy(() -> handler(null, mode, new JobRunCounts()).run(""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("IPASS_MAINTENANCE_DB_URL")
                    .hasMessageContaining(mode.name());
        }
    }

    @Test
    void aFailingPurgeFailsTheRunAndLeavesNoCounts() {
        PartitionPurge failing = (cutoff, dryRun) -> {
            throw new IllegalStateException("lock timeout");
        };
        JobRunCounts counts = new JobRunCounts();

        assertThatThrownBy(() -> handler(failing, PayloadPurgeMode.ENFORCE, counts).run(""))
                .hasMessageContaining("lock timeout");
        assertThat(counts.take()).isNull();
    }

    @Test
    void theModeIsParsedFromConfigurationTextAndAnythingUnknownIsOff() {
        assertThat(PayloadPurgeMode.parse("enforce")).isEqualTo(PayloadPurgeMode.ENFORCE);
        assertThat(PayloadPurgeMode.parse(" DRY_RUN ")).isEqualTo(PayloadPurgeMode.DRY_RUN);
        assertThat(PayloadPurgeMode.parse("")).isEqualTo(PayloadPurgeMode.OFF);
        assertThat(PayloadPurgeMode.parse(null)).isEqualTo(PayloadPurgeMode.OFF);
        assertThat(PayloadPurgeMode.parse("yes please")).as("never delete on a typo").isEqualTo(PayloadPurgeMode.OFF);
    }
}
