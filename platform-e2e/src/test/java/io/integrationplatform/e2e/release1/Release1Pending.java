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
 * base suite's {@code ui/tests/base-suite/lib/pending-names.mjs} (lane t130). Measured against main efa7d1d, 18-Oct.
 */
public final class Release1Pending {

    /** Immutable: a run cannot switch a gate on. */
    public static final Map<String, Boolean> ON_MAIN;

    static {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put("T95", true); // tenant + systems registry
        flags.put("T96", false); // contract version pinning on replay - not on main
        flags.put("T98", false); // MQ (CSV) target adapter - only ApiTargetCall on main
        flags.put("T99", false); // retry + circuit breaker per target - not on main
        flags.put("T104", true); // outbox
        flags.put("T105", true); // stage timings - efa7d1d
        flags.put("T106", true); // composition - OutboxRelayRunnerConfiguration
        flags.put("T107", true); // correlation + JSON logs - efa7d1d
        flags.put("T109", true); // audit writer - JdbcAuditEventStore
        flags.put("T110", true); // tracking reads - MessageTrackingController
        flags.put("T111", false); // replay - seam-row327-t112 e69d14e
        flags.put("T112", false); // detail + failures screens - seam-row327-t112 80d2804
        flags.put("T113", true); // dashboard
        flags.put("T115", false); // bulk intake, idempotency, same-key ordering - not on main
        flags.put("T120", false); // daily report - DailyReportPlaceholderHandler only
        flags.put("T121", false); // report screen - not on main
        flags.put("T143", false); // platform-e2e compose profile - seam ed9d5ec only
        flags.put("T144", false); // AdjustableClock (e2e profile) - seam only
        flags.put("T157", false); // tenant B seed - be2ef40, approved, not merged
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
