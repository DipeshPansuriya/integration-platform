package io.integrationplatform.scheduler;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

/**
 * E14 (T-057, T-058; ADR-012) the proof handlers for the Testcontainers suites of the scheduler seam. The tasks are
 * test beans only - names all start PROOF_ and live outside the ADR-012 closed register (DELIVERY_RETRY, PAYLOAD_PURGE,
 * DAILY_REPORT, CREDENTIAL_CLEANUP); no production JobHandler bean is implemented or touched here.
 *
 * <p>Registered through db-scheduler's Spring Boot starter Java DSL ({@link Tasks#oneTime(String)}): the starter
 * collects every {@code Task<?>} bean ({@code DbSchedulerAutoConfiguration}) and adds them to the Scheduler. The whole
 * configuration only activates with {@code e14.scheduler-proof=true}, so no other test context or deployment of
 * platform-app ever gains these beans.
 *
 * <p>Runs are recorded in a static map because a suite's application instances are two Spring contexts in this same
 * JVM (db-scheduler's claiming is guaranteed in the database; the proof reads the record from either context). The
 * {@code scheduler.job_run} row is written by the production recording listener only (T-056's
 * {@code RunRecordingSchedulerListener} records every registered task's completion, so a proof handler that wrote
 * its own row would duplicate it - measured on the restart suite's first green attempt).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(value = "e14.scheduler-proof", havingValue = "true")
public class SchedulerProofTasks {

    /** Task names: PROOF_* only, so the closed register stays untouched. */
    public static final String PROOF_ON_TIME = "PROOF_ON_TIME";
    public static final String PROOF_RESTART = "PROOF_RESTART";
    public static final String PROOF_CLAIM = "PROOF_CLAIM";

    /** Fires per task-instance id across every context of this JVM; one entry per execution. */
    static final Map<String, List<Instant>> FIRED = new ConcurrentHashMap<>();

    @Bean
    Task<Void> proofOnTimeTask() {
        return Tasks.oneTime(PROOF_ON_TIME).execute((taskInstance, executionContext) ->
                fired(PROOF_ON_TIME, taskInstance.getId(), Instant.now()));
    }

    @Bean
    Task<Void> proofRestartTask() {
        // fired() only: the run row (scheduled_for = the missed due, instance = the claiming scheduler-name,
        // is_late against the seeded threshold) is written by the production listener over the restricted login
        return Tasks.oneTime(PROOF_RESTART).execute((taskInstance, executionContext) ->
                fired(PROOF_RESTART, taskInstance.getId(), Instant.now()));
    }

    @Bean
    Task<Void> proofClaimTask() {
        return Tasks.oneTime(PROOF_CLAIM).execute((taskInstance, executionContext) -> {
            fired(PROOF_CLAIM, taskInstance.getId(), Instant.now());
            try {
                Thread.sleep(3000); // keeps executions in flight so the claimed rows are observable in scheduled_tasks
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    static void fired(String taskName, String instanceId, Instant when) {
        FIRED.computeIfAbsent(taskName + "/" + instanceId,
                key -> Collections.synchronizedList(new ArrayList<>())).add(when);
    }

    /** How many times this task-instance has fired in this JVM. */
    public static long runsOf(String taskName, String instanceId) {
        List<Instant> runs = FIRED.get(taskName + "/" + instanceId);
        return runs == null ? 0 : runs.size();
    }

    static long totalRunsOf(String taskName) {
        return FIRED.keySet().stream().filter(key -> key.startsWith(taskName + "/")).count();
    }

    public static Instant firstFireOf(String taskName, String instanceId) {
        List<Instant> runs = FIRED.get(taskName + "/" + instanceId);
        return runs == null || runs.isEmpty() ? null : runs.get(0);
    }
}