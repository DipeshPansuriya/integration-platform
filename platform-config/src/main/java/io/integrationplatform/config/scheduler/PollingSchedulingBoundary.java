package io.integrationplatform.config.scheduler;

import io.integrationplatform.domain.scheduler.PollingJobSurface;
import io.integrationplatform.domain.scheduler.PollingJobSurface.JobSummary;
import io.integrationplatform.domain.scheduler.SchedulingBoundary;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * E14 consumer: the one {@link SchedulingBoundary} bean. At startup (once every singleton exists) it attaches the
 * {@link JobPollingSurface} bean and from then on serves its summaries to readers such as the job admin read path.
 * Summaries are data only - no payload, no tenant id (ADR-012 sections 4 and 5); scheduling decisions stay inside
 * the scheduler.
 */
@Component
public class PollingSchedulingBoundary implements SchedulingBoundary, SmartInitializingSingleton {

    private final ObjectProvider<PollingJobSurface> surfaceProvider;
    private volatile PollingJobSurface surface;

    @Autowired
    public PollingSchedulingBoundary(ObjectProvider<PollingJobSurface> surfaceProvider) {
        this.surfaceProvider = surfaceProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        PollingJobSurface bean = surfaceProvider == null ? null : surfaceProvider.getIfAvailable();
        if (bean != null) {
            attachPollingSurface(bean);
        }
    }

    @Override
    public void attachPollingSurface(PollingJobSurface surface) {
        this.surface = surface;
    }

    /** The current job summaries; empty (never null) while no surface is attached. */
    public List<JobSummary> summaries() {
        PollingJobSurface attached = surface;
        return attached == null ? List.of() : attached.poll();
    }

    /** The summaries that changed since {@code since} (null = all); empty (never null) while no surface is attached. */
    public List<JobSummary> summariesSince(Instant since) {
        PollingJobSurface attached = surface;
        return attached == null ? List.of() : attached.pollSince(since);
    }
}
