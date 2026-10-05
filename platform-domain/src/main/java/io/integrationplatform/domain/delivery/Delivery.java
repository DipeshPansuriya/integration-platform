package io.integrationplatform.domain.delivery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One Delivery (T-011, ADR-010 section 6): its state and the transitions it has walked, immutable and guarded.
 *
 * <p>{@link #start} models the stage-1 insert (the row becomes {@code PENDING}); {@link #moveTo} is the only way
 * the state changes, and it refuses every pair outside {@link DeliveryTransitions} with
 * {@link IllegalDeliveryTransitionException}, leaving this instance untouched. The transition list is the domain
 * ledger the repository mirrors into {@code delivery_event} (T-012); nothing here lives only in memory: the
 * repository reconstitutes the state from the database after every restart (E2-F2-S1).
 */
public record Delivery(DeliveryState state, List<DeliveryTransition> transitions) {

    public Delivery {
        transitions = List.copyOf(transitions);
    }

    /** A new delivery waiting for its first claim (ADR-010 section 6, row "(new) -> PENDING"). */
    public static Delivery start(Instant at) {
        return new Delivery(DeliveryState.PENDING, List.of());
    }

    /**
     * Moves to {@code to} at {@code at}, recording the transition.
     *
     * @throws IllegalDeliveryTransitionException when ADR-010 section 6 lists no trigger for {@code state -> to};
     *     this delivery is then unchanged (the rejected move returns no instance)
     */
    public Delivery moveTo(DeliveryState to, Instant at) {
        if (!DeliveryTransitions.isLegal(state, to)) {
            throw new IllegalDeliveryTransitionException(state, to);
        }
        List<DeliveryTransition> walked = new ArrayList<>(transitions);
        walked.add(new DeliveryTransition(state, to, at));
        return new Delivery(to, walked);
    }
}
