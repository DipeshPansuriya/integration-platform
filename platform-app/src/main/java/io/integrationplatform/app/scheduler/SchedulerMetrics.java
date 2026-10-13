package io.integrationplatform.app.scheduler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * E14 T-062 (ADR-012 section 6): the alert rules' platform metrics. Run counters increment lazily per run
 * ({@code platform.scheduler.job.runs} with the outcome tag, naming db-scheduler's own metrics leave untouched);
 * the oldest-overdue gauge is computed at scrape time (every Prometheus read runs the overdue scan), so an
 * absent metric - the alert rule for "no instance alive" - is the only case where nothing is reported.
 */
@Component
public class SchedulerMetrics {

    public static final String OLDEST_OVERDUE_GAUGE = "platform.scheduler.job.oldest.overdue.seconds";

    public SchedulerMetrics(MeterRegistry meters, JobOverdueDetector detector) {
        Gauge.builder(OLDEST_OVERDUE_GAUGE, detector, d -> d.oldestOverdueSeconds())
                .description("Seconds the oldest unclaimed overdue execution has been waiting (0 when none)")
                .register(meters);
    }
}