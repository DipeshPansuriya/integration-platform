package io.integrationplatform.app.scheduler;

import io.integrationplatform.delivery.port.DeliveryDailyAggregates;
import io.integrationplatform.domain.scheduler.JobHandler;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import io.integrationplatform.tenancy.port.ActiveTenantDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DAILY_REPORT (ADR-012 section 1; T-138, E13-F1-S2): rebuilds yesterday's (UTC) delivery pre-aggregate once per
 * active tenant, each in its own transaction with that tenant bound. The job data is ignored - tenants come only from
 * the active-tenant directory (T-144). One tenant failing does not stop the others; the run then fails so the
 * run/alert wrapper records it, and the next run rebuilds the day again.
 */
@Component
public class DailyReportPlaceholderHandler implements JobHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DailyReportPlaceholderHandler.class);

    private final ActiveTenantDirectory activeTenants;
    private final WorkerTenantBinding binding;
    private final TransactionOperations tx;
    private final DeliveryDailyAggregates aggregates;
    private final Clock clock;

    @Autowired
    public DailyReportPlaceholderHandler(ActiveTenantDirectory activeTenants,
            ObjectProvider<WorkerTenantBinding> binding, PlatformTransactionManager transactionManager,
            DeliveryDailyAggregates aggregates, Clock clock) {
        this(activeTenants, binding.getIfAvailable(), new TransactionTemplate(transactionManager), aggregates, clock);
    }

    DailyReportPlaceholderHandler(ActiveTenantDirectory activeTenants, WorkerTenantBinding binding,
            TransactionOperations tx, DeliveryDailyAggregates aggregates, Clock clock) {
        this.activeTenants = Objects.requireNonNull(activeTenants, "activeTenants");
        this.binding = binding;
        this.tx = Objects.requireNonNull(tx, "tx");
        this.aggregates = Objects.requireNonNull(aggregates, "aggregates");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String type() {
        return SchedulerJobTasks.DAILY_REPORT;
    }

    @Override
    public void run(String jobData) {
        if (binding == null) {
            throw new IllegalStateException("daily report needs a WorkerTenantBinding bean");
        }
        if (binding.currentTenantId().isPresent()) {
            throw new IllegalStateException("daily report must start with no tenant bound");
        }
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1);
        List<UUID> tenants = activeTenants.activeTenantIds();
        int failed = 0;
        for (UUID tenant : tenants) {
            try {
                Integer rows = binding.callAs(tenant, () -> tx.execute(status -> aggregates.refresh(day)));
                LOG.atInfo().addKeyValue("event", "daily_report.refresh").addKeyValue("tenant_id", tenant)
                        .addKeyValue("day", day).addKeyValue("rows", rows).log("daily report refreshed");
            } catch (RuntimeException e) {
                failed++;
                LOG.atWarn().addKeyValue("event", "daily_report.refresh_failed").addKeyValue("tenant_id", tenant)
                        .addKeyValue("day", day).setCause(e).log("daily report refresh failed");
            }
        }
        if (failed > 0) {
            throw new IllegalStateException("daily report refresh failed for " + failed + " of " + tenants.size()
                    + " tenants; they are retried at the next run");
        }
    }
}
