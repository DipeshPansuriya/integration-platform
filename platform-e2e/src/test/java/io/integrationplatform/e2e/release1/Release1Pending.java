package io.integrationplatform.e2e.release1;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Row 323 T139/T140: one flag per T-item - is its feature on local main? Flip ONE line when the item lands; every
 * scenario annotated {@code @WaitsOn(item)} then runs. Never flip for a lane or seam branch. Same pattern as the
 * base suite's {@code e2e-ui/tests/base-suite/lib/pending-names.mjs} (lane t130). Measured against main efa7d1d, 18-Oct; T96/T98/T99/T111/T112/T143/T144/T157 re-measured against main ecef544b, 22-Oct (lane t174-sc).
 */
public final class Release1Pending {

    /** Immutable: a run cannot switch a gate on. */
    public static final Map<String, Boolean> ON_MAIN;

    static {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put("T95", true); // tenant + systems registry
        flags.put("T96", true); // contract version pinning on replay - on main 27d217f6/1904b6dd (stage-1 insert pins contract_version_id)
        flags.put("T98", true); // MQ (CSV) target adapter - on main (CompositionConfiguration MQ-CSV renderer; E18 mqCsv journey green on 137ed4f6)
        flags.put("T99", true); // retry + circuit breaker per target - on main (JdbcDeliveryRetryStore, ApiTargetCall)
        flags.put("T104", true); // outbox
        flags.put("T105", true); // stage timings - efa7d1d
        flags.put("T106", true); // composition - OutboxRelayRunnerConfiguration
        flags.put("T107", true); // correlation + JSON logs - efa7d1d
        flags.put("T109", true); // audit writer - JdbcAuditEventStore
        flags.put("T110", true); // tracking reads - MessageTrackingController
        flags.put("T111", true); // replay - on main (DeliveryOperatorController, POST .../deliveries/{deliveryId}/replay)
        flags.put("T112", true); // detail + failures screens - on main (ui/src/pages/failures-view.ts, App.tsx:30)
        flags.put("T113", true); // dashboard
        flags.put("T115", true); // bulk intake, idempotency, same-key ordering - on main 93aa1910 (IntakeController.submitBulk, messages:bulk); measured on b6a958ee, 22-Oct (lane t174c)
        flags.put("T120", true); // daily report READ - on main eded195a (DeliveryReportController GET /api/v1/reports/delivery?day=); the roll-up writer is DailyReportPlaceholderHandler (SC-08 measures what that yields, see RUN 8)
        flags.put("T121", false); // report screen - not on main
        flags.put("T143", true); // platform-e2e compose profile - on main (compose/compose.yaml profile e2e: artemis + wiremock); provisions NO bearer tokens and NO REF-ORDER seed
        flags.put("T144", true); // AdjustableClock (e2e profile) - on main (PlatformClockConfiguration @Profile e2e)
        flags.put("T157", true); // tenant B seed - on main (e2e-ui/tests/base-suite/lib/tenants.mjs TENANT_B_PLAN)
        ON_MAIN = Collections.unmodifiableMap(flags);
    }

    private Release1Pending() {
    }

    /** Empty when every item is on main; else the reason naming each missing item and the flag to flip. */
    public static Optional<String> waitsOn(String... items) {
        return waitsOn(ON_MAIN, items);
    }

    static Optional<String> waitsOn(Map<String, Boolean> flags, String... items) {
        Objects.requireNonNull(flags, "flags");
        if (items == null || items.length == 0) {
            throw new IllegalArgumentException("waitsOn: name at least one item");
        }
        for (String item : items) {
            if (!flags.containsKey(item)) {
                throw new IllegalArgumentException(
                        "waitsOn: unknown item " + item + " - add it to Release1Pending.ON_MAIN");
            }
        }
        List<String> missing = Arrays.stream(items).filter(item -> !flags.get(item)).distinct().toList();
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("waits on " + String.join(", ", missing)
                + " (not on main) - flip Release1Pending.ON_MAIN." + missing.getFirst() + " when it lands");
    }
}
