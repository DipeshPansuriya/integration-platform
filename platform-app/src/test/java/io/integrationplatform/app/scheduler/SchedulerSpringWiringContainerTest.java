package io.integrationplatform.app.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.api.callback.Callback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E14 T-056 + T-062 inside the real application boot (container group, R03): db-scheduler runs the closed register
 * from the job_definition rows - at start the four rows are synced into {@code scheduler.scheduled_tasks} - and the
 * run/alert wrapper records every execution into {@code scheduler.job_run}; no {@code @Scheduled} anywhere (the
 * ArchUnit rule enforces that build-wide). The metrics registry carries the run counters and the overdue gauge the
 * alert rules' PromQL reads.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulerSpringWiringContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    private static final AtomicInteger GRANTED = new AtomicInteger();
    private static PlatformTestWiring.RestrictedLogin login;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        login = PlatformTestWiring.register(registry, POSTGRES);
    }

    /** The migrations have run by instance phase; the restricted login joins ipaas_app exactly once. */
    @BeforeEach
    void grantApplicationRoleOnce() throws SQLException {
        if (GRANTED.incrementAndGet() == 1) {
            PlatformTestWiring.grantApplicationRole(POSTGRES, login);
        }
    }

    @Test
    void theClosedRegisterIsSyncedIntoScheduledTasksAtStart() {
        // the register has exactly its four job types (the seed migration) ...
        assertThat(eventually(() -> scalarInt("select count(*) from scheduler.job_definition"), c -> c == 4))
                .isEqualTo(4);
        // ... and the startup sync gave each of them a pending execution, scheduled from its own row
        assertThat(eventually(() -> scalarString(
                        "select string_agg(task_name, ',' order by task_name) from scheduler.scheduled_tasks"),
                names -> "CREDENTIAL_CLEANUP,DAILY_REPORT,DELIVERY_RETRY,PAYLOAD_PURGE".equals(names)))
                .isEqualTo("CREDENTIAL_CLEANUP,DAILY_REPORT,DELIVERY_RETRY,PAYLOAD_PURGE");
    }

    @Test
    void aScheduledJobRunIsRecordedByTheRunAlertWrapper() {
        // DELIVERY_RETRY fires on its row cadence and the wrapper records the execution - SUCCEEDED, on time
        assertThat(eventually(() -> scalarInt("select count(*) from scheduler.job_run"
                        + " where job_type = 'DELIVERY_RETRY' and outcome = 'SUCCEEDED' and is_late = false"),
                count -> count >= 1)).isGreaterThanOrEqualTo(1);

        // the run counters and the overdue gauge exist for the alert rules' PromQL
        assertThat(meterRegistry.find("platform.scheduler.job.runs").counters()).isNotEmpty();
        assertThat(meterRegistry.get("platform.scheduler.job.oldest.overdue.seconds").gauge().value())
                .isGreaterThanOrEqualTo(0.0);
    }

    private Integer scalarInt(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private String scalarString(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    /** Polls until {@code check} accepts the value (90 s budget; on budget-out the last value is returned). */
    private static <T> T eventually(java.util.function.Supplier<T> probe, java.util.function.Predicate<T> check) {
        long deadline = System.nanoTime() + 90_000_000_000L;
        T last = null;
        RuntimeException lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                last = probe.get();
                if (check.test(last)) {
                    return last;
                }
            } catch (RuntimeException e) {
                lastError = e;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        return last;
    }

    /**
     * Row 278: the grant must land between Flyway and the db-scheduler startup reconcile, so the login joins
     * {@code ipaas_app} in a Flyway AFTER_MIGRATE callback (Spring Boot's flyway autoconfiguration applies
     * every {@link Callback} bean of the context) - only this test's context gets it, other suites stay
     * byte-identical. {@code @DynamicPropertySource} has already started the container and created the login
     * when this bean is built (before the flyway bean runs). The {@code @BeforeEach} grant stays as an
     * idempotent safety net.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class GrantAppRoleWiring {

        @Bean
        Callback grantAppRoleFlywayCallback() {
            return new GrantAppRoleFlywayCallback(POSTGRES, login);
        }
    }
}