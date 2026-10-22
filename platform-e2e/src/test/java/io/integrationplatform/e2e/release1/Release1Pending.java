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
 * base suite's {@code ui/tests/base-suite/lib/pending-names.mjs} (lane t130). Re-measured against main b6106af2, 22-Oct (ui/tests/release1/pending-probes.mjs; pending-gate.test.mjs keeps this map equal to pending.mjs).
 */
public final class Release1Pending {

    /** Immutable: a run cannot switch a gate on. */
    public static final Map<String, Boolean> ON_MAIN;

    static {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put("T95", true); // tenant + systems registry
        flags.put("T96", true); // contract version pinning on replay - DeliveryOperatorController NOT_PINNED
        flags.put("T98", true); // MQ (CSV) target adapter - MqCsvOutbound
        flags.put("T99", true); // retry + circuit breaker per target - TargetHalfOpenCap, RetryPolicy
        flags.put("T104", true); // outbox
        flags.put("T105", true); // stage timings - efa7d1d
        flags.put("T106", true); // composition - OutboxRelayRunnerConfiguration
        flags.put("T107", true); // correlation + JSON logs - efa7d1d
        flags.put("T109", true); // audit writer - JdbcAuditEventStore
        flags.put("T110", true); // tracking reads - MessageTrackingController
        flags.put("T111", true); // replay - POST /deliveries/{id}/replay
        flags.put("T112", true); // detail + failures screens - 80d2804
        flags.put("T113", true); // dashboard
        flags.put("T115", true); // bulk intake, idempotency, same-key ordering - messages:bulk, 96762bfd
        flags.put("T120", true); // daily report - DailyReportPlaceholderHandler (DeliveryDailyAggregates)
        flags.put("T121", true); // report screen - DeliveryReport.tsx
        flags.put("T143", true); // platform-e2e compose profile - compose.yaml profiles e2e
        flags.put("T144", true); // AdjustableClock (e2e profile) - 3f1c4ef1
        flags.put("T157", true); // tenant B seed - be2ef407
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
