package io.integrationplatform.delivery.report;

import io.integrationplatform.delivery.port.DeliveryDailyAggregates;
import io.integrationplatform.delivery.port.DeliveryDailyAggregates.DailyCounts;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Row327 T-139 (E13-C3/C4): the tenant delivery report - one day of the daily aggregate per interface and target.
 * The tenant never travels in the path (ADR-011 Decision 4): the store stamps the bound tenant in a read-only
 * transaction and FORCE RLS backs it; with no tenant bound the store fails closed. Roles: DeliveryReportEndpointRoles.
 * T185 (Release 1 SC-08): the nightly DAILY_REPORT job stores only the finished previous UTC day, so a day with no
 * stored row (today, or a day the job has not yet rolled up) is counted from the delivery rows on request - same
 * counts, same tenant-stamped read-only transaction, nothing is written. A stored roll-up wins when present.
 * T187 (review of T185): that count scans a whole UTC-day delivery partition, so it is allowed only for today and
 * yesterday - the two days the nightly job may not have stored yet - and the requested day is bounded: a future day,
 * or a day older than {@code platform.delivery.report.max-age-days} (default 31), is a 400.
 */
@RestController
@RequestMapping(DeliveryReportEndpointRoles.REPORT)
public class DeliveryReportController {

    /** Default window of the report: a day older than this many days before today (UTC) is refused. */
    static final int DEFAULT_MAX_AGE_DAYS = 31;

    private final DeliveryDailyAggregates aggregates;
    private final TransactionTemplate readOnly;
    private final Clock clock;
    private final int maxAgeDays;

    public DeliveryReportController(DeliveryDailyAggregates aggregates, PlatformTransactionManager transactions) {
        this(aggregates, transactions, Clock.systemUTC(), DEFAULT_MAX_AGE_DAYS);
    }

    @Autowired
    public DeliveryReportController(DeliveryDailyAggregates aggregates, PlatformTransactionManager transactions,
            ObjectProvider<Clock> clock,
            @Value("${platform.delivery.report.max-age-days:" + DEFAULT_MAX_AGE_DAYS + "}") int maxAgeDays) {
        this(aggregates, transactions, clock.getIfAvailable(Clock::systemUTC), maxAgeDays);
    }

    DeliveryReportController(DeliveryDailyAggregates aggregates, PlatformTransactionManager transactions, Clock clock,
            int maxAgeDays) {
        this.aggregates = Objects.requireNonNull(aggregates, "aggregates");
        this.readOnly = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        this.readOnly.setReadOnly(true);
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxAgeDays < 1) {
            throw new IllegalArgumentException("platform.delivery.report.max-age-days must be at least 1");
        }
        this.maxAgeDays = maxAgeDays;
    }

    @GetMapping
    public ResponseEntity<List<DailyCounts>> report(
            @RequestParam("day") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (day.isAfter(today) || day.isBefore(today.minusDays(maxAgeDays))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "day must be between " + today.minusDays(maxAgeDays) + " and " + today + " (UTC)");
        }
        boolean mayBeUnrolled = !day.isBefore(today.minusDays(1));
        return ResponseEntity.ok(readOnly.execute(status -> {
            List<DailyCounts> stored = aggregates.aggregated(day);
            return stored.isEmpty() && mayBeUnrolled ? aggregates.direct(day) : stored;
        }));
    }
}
