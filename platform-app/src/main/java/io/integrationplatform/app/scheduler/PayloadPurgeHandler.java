package io.integrationplatform.app.scheduler;

import io.integrationplatform.app.retention.RetentionPolicy;
import io.integrationplatform.domain.scheduler.JobHandler;
import io.integrationplatform.tenancy.port.PartitionPurge;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * E14 T-142 / T-189 (ADR-012 section 1, ADR-014 Decision 3): the body of PAYLOAD_PURGE. It drops whole day partitions
 * - message, payload and delivery of one day together - older than the agreed payload retention (30 days), and keeps
 * any day that still holds a dead letter or a non-final delivery; the drop and the guard run in the database as the
 * maintenance role ({@link PartitionPurge}). The cutoff comes from the clock and {@link RetentionPolicy} only: the job
 * data is ignored, so it can never name a tenant (T-144), and the purge reads no tenant at all.
 *
 * <p>Ships {@linkplain PayloadPurgeMode#OFF OFF}: it deletes data, so a deployment opts in. DRY_RUN and ENFORCE need the
 * maintenance connection (IPASS_MAINTENANCE_DB_URL, _USERNAME, _PASSWORD); without it the run fails by name, which
 * alerts - retention silently not running is an incident (ADR-016). The numbers go to the run row through
 * {@link JobRunCounts}.
 */
@Component
public class PayloadPurgeHandler implements JobHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PayloadPurgeHandler.class);

    private final PartitionPurge purge;
    private final RetentionPolicy retention;
    private final Clock clock;
    private final JobRunCounts counts;
    private final PayloadPurgeMode mode;

    @Autowired
    public PayloadPurgeHandler(ObjectProvider<PartitionPurge> purge, RetentionPolicy retention, Clock clock,
            JobRunCounts counts, @Value("${ipaas.retention.payload-purge.mode:OFF}") String mode) {
        this(purge.getIfAvailable(), retention, clock, counts, PayloadPurgeMode.parse(mode));
    }

    /** {@code purge} may be null: only OFF runs without it. */
    PayloadPurgeHandler(PartitionPurge purge, RetentionPolicy retention, Clock clock, JobRunCounts counts,
            PayloadPurgeMode mode) {
        this.purge = purge;
        this.retention = Objects.requireNonNull(retention, "retention");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.counts = Objects.requireNonNull(counts, "counts");
        this.mode = Objects.requireNonNull(mode, "mode");
        LOG.info("event=payload_purge.mode mode={}", mode);
    }

    @Override
    public String type() {
        return SchedulerJobTasks.PAYLOAD_PURGE;
    }

    @Override
    public void run(String jobData) {
        if (mode == PayloadPurgeMode.OFF) {
            counts.put("disabled", 1);
            return;
        }
        if (purge == null) {
            throw new IllegalStateException("PAYLOAD_PURGE mode " + mode + " needs the maintenance connection"
                    + " (IPASS_MAINTENANCE_DB_URL, IPASS_MAINTENANCE_DB_USERNAME, IPASS_MAINTENANCE_DB_PASSWORD);"
                    + " nothing was purged");
        }
        boolean dryRun = mode == PayloadPurgeMode.DRY_RUN;
        LocalDate cutoff = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minus(retention.payload());
        PartitionPurge.Outcome outcome = purge.purgeBefore(cutoff, dryRun);
        if (dryRun) {
            counts.put("dryRun", 1);
            counts.put("wouldDrop", outcome.dropped().size());
        } else {
            counts.put("dropped", outcome.dropped().size());
        }
        counts.put("kept", outcome.kept().size());
        LOG.atInfo().addKeyValue("event", "payload_purge.run").addKeyValue("cutoff", cutoff)
                .addKeyValue("dry_run", dryRun).addKeyValue("dropped", outcome.dropped().size())
                .addKeyValue("kept", outcome.kept().size())
                .log("event=payload_purge.run cutoff={} dry_run={} dropped={} kept={} keptDays={}", cutoff, dryRun,
                        outcome.dropped().size(), outcome.kept().size(), outcome.kept());
    }
}
