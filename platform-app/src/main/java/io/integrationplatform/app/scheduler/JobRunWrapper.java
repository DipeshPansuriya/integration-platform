package io.integrationplatform.app.scheduler;

import io.integrationplatform.domain.scheduler.JobHandler;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * E14 T-056 (ADR-012 section 2): every registered job type runs through here - db-scheduler's execution handler
 * delegates to the {@link JobHandler} bean of that type. A job type with no handler yet (its body arrives in a
 * later wave) is a recorded no-op: db-scheduler completes the execution, the listener records the run, and this
 * class only logs - the register keeps running.
 */
@Component
public class JobRunWrapper {

    private static final Logger LOG = LoggerFactory.getLogger(JobRunWrapper.class);

    private final Map<String, JobHandler> handlersByType;

    private final JobRunCounts counts;

    public JobRunWrapper(List<JobHandler> handlerBeans) {
        this(handlerBeans, new JobRunCounts());
    }

    @Autowired
    public JobRunWrapper(List<JobHandler> handlerBeans, JobRunCounts counts) {
        this.counts = counts;
        this.handlersByType = handlerBeans.stream()
                .collect(Collectors.toMap(JobHandler::type, Function.identity()));
    }

    /** Runs the body of the job type; the execution completes normally even when no body exists yet. */
    public void run(String jobType) {
        JobHandler handler = handlersByType.get(jobType);
        if (handler == null) {
            LOG.warn("job type {} has no JobHandler yet (its wave adds one); run skipped", jobType);
            return;
        }
        // an earlier run on this pooled thread must not leak its counts into this run's row (T-059)
        counts.clear();
        // job data stays empty until a body needs the row's parameters (T-060 / T-141 wire them); never a tenant id
        handler.run("");
    }
}