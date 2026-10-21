package io.integrationplatform.delivery.report;

import io.integrationplatform.delivery.port.DeliveryDailyAggregates;
import io.integrationplatform.delivery.port.DeliveryDailyAggregates.DailyCounts;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Row327 T-139 (E13-C3/C4): the tenant delivery report - one day of the daily aggregate per interface and target.
 * The tenant never travels in the path (ADR-011 Decision 4): the store stamps the bound tenant in a read-only
 * transaction and FORCE RLS backs it; with no tenant bound the store fails closed. Roles: DeliveryReportEndpointRoles.
 */
@RestController
@RequestMapping(DeliveryReportEndpointRoles.REPORT)
public class DeliveryReportController {

    private final DeliveryDailyAggregates aggregates;
    private final TransactionTemplate readOnly;

    public DeliveryReportController(DeliveryDailyAggregates aggregates, PlatformTransactionManager transactions) {
        this.aggregates = Objects.requireNonNull(aggregates, "aggregates");
        this.readOnly = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        this.readOnly.setReadOnly(true);
    }

    @GetMapping
    public ResponseEntity<List<DailyCounts>> report(
            @RequestParam("day") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return ResponseEntity.ok(readOnly.execute(status -> aggregates.aggregated(day)));
    }
}
