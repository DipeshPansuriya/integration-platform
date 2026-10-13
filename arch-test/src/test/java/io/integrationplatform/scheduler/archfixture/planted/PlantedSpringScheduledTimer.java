package io.integrationplatform.scheduler.archfixture.planted;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Planted violation for E14 T-056's arch test (ADR-012 section 3): a Spring {@code @Scheduled} timer driven by
 * {@code @EnableScheduling} plus an in-memory {@code ScheduledExecutorService} - exactly what the build must refuse.
 * Test class only - never in the production scan (SchedulerTimingRulesTest imports production with
 * DO_NOT_INCLUDE_TESTS, this module included).
 */
@Configuration
@EnableScheduling
public class PlantedSpringScheduledTimer {

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        timer.toString();
    }
}