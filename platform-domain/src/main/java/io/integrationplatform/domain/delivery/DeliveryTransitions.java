package io.integrationplatform.domain.delivery;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The guarded transition register of the Delivery state machine (T-011): exactly the rows of ADR-010 section 6,
 * and nothing else. Illegal transitions are rejected in code, so they can never reach the database.
 *
 * <p>Terminal: {@code DELIVERED}. {@code DEAD_LETTERED} is terminal until an operator replays it
 * ({@code DEAD_LETTERED -> DUE}, audited - the audit belongs to the replay, not to this register).
 */
public final class DeliveryTransitions {

    private static final Map<DeliveryState, Set<DeliveryState>> LEGAL = build();

    private DeliveryTransitions() {
    }

    /**
     * The legal targets of {@code from}: HELD, SENDING and RETRY_WAIT deferral from {@code PENDING}/{@code DUE},
     * the classifier outcomes from {@code SENDING}, the re-drive to {@code DUE}, the release from {@code HELD}
     * and the operator replay from {@code DEAD_LETTERED} (ADR-010 section 6).
     */
    public static Set<DeliveryState> legalTargets(DeliveryState from) {
        return LEGAL.get(from);
    }

    /** Whether ADR-010 section 6 lists this transition. */
    public static boolean isLegal(DeliveryState from, DeliveryState to) {
        return legalTargets(from).contains(to);
    }

    private static Map<DeliveryState, Set<DeliveryState>> build() {
        Map<DeliveryState, Set<DeliveryState>> map = new EnumMap<>(DeliveryState.class);
        map.put(DeliveryState.PENDING, EnumSet.of(DeliveryState.HELD, DeliveryState.SENDING,
                DeliveryState.RETRY_WAIT));
        map.put(DeliveryState.HELD, EnumSet.of(DeliveryState.DUE));
        map.put(DeliveryState.SENDING, EnumSet.of(DeliveryState.DELIVERED, DeliveryState.RETRY_WAIT,
                DeliveryState.DEAD_LETTERED));
        map.put(DeliveryState.RETRY_WAIT, EnumSet.of(DeliveryState.DUE));
        map.put(DeliveryState.DUE, EnumSet.of(DeliveryState.HELD, DeliveryState.SENDING, DeliveryState.RETRY_WAIT));
        map.put(DeliveryState.DEAD_LETTERED, EnumSet.of(DeliveryState.DUE));
        map.put(DeliveryState.DELIVERED, EnumSet.noneOf(DeliveryState.class));
        Map<DeliveryState, Set<DeliveryState>> frozen = new EnumMap<>(map);
        frozen.replaceAll((from, targets) -> Collections.unmodifiableSet(targets));
        return Collections.unmodifiableMap(frozen);
    }
}
