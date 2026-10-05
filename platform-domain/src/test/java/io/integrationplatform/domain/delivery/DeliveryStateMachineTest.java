package io.integrationplatform.domain.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T-011 (lane C, E2-F2-S1): the guarded Delivery state machine, exactly the register of ADR-010 section 6. The
 * illegal transitions are rejected in code - nothing reaches the database - and every legal move is recorded with
 * its time (the repository writes that history to {@code delivery_event}, T-012).
 */
class DeliveryStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-10-05T09:00:00Z");

    private static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    /** A new delivery: inserted by routing stage 1, PENDING, no transitions yet. */
    private static Delivery fresh() {
        return Delivery.start(T0);
    }

    @Test
    void aNewDeliveryStartsPending() {
        Delivery delivery = fresh();
        assertThat(delivery.state()).isEqualTo(DeliveryState.PENDING);
        assertThat(delivery.transitions()).isEmpty();
    }

    @Test
    void theFullWalkStoresEveryTransitionWithItsTime() {
        Delivery delivery = fresh()
                .moveTo(DeliveryState.SENDING, at(1))
                .moveTo(DeliveryState.RETRY_WAIT, at(2))
                .moveTo(DeliveryState.DUE, at(3))
                .moveTo(DeliveryState.SENDING, at(4))
                .moveTo(DeliveryState.DELIVERED, at(5));

        assertThat(delivery.state()).isEqualTo(DeliveryState.DELIVERED);
        assertThat(delivery.transitions()).containsExactly(
                new DeliveryTransition(DeliveryState.PENDING, DeliveryState.SENDING, at(1)),
                new DeliveryTransition(DeliveryState.SENDING, DeliveryState.RETRY_WAIT, at(2)),
                new DeliveryTransition(DeliveryState.RETRY_WAIT, DeliveryState.DUE, at(3)),
                new DeliveryTransition(DeliveryState.DUE, DeliveryState.SENDING, at(4)),
                new DeliveryTransition(DeliveryState.SENDING, DeliveryState.DELIVERED, at(5)));
    }

    /**
     * The register of ADR-010 section 6, row for row: HELD and DEAD_LETTERED are reachable exactly where the
     * table lists them.
     */
    @ParameterizedTest
    @CsvSource({
        "PENDING,  HELD",
        "DUE,      HELD",
        "HELD,     DUE",
        "PENDING,  SENDING",
        "DUE,      SENDING",
        "PENDING,  RETRY_WAIT",
        "DUE,      RETRY_WAIT",
        "SENDING,  DELIVERED",
        "SENDING,  RETRY_WAIT",
        "SENDING,  DEAD_LETTERED",
        "RETRY_WAIT, DUE",
        "DEAD_LETTERED, DUE",
    })
    void everyTransitionOfAdr010Section6IsLegal(DeliveryState from, DeliveryState to) {
        assertThat(DeliveryTransitions.isLegal(from, to)).isTrue();
        assertThat(deliveryIn(from).moveTo(to, at(1)).state()).isEqualTo(to);
    }

    /**
     * The exhaustive matrix: for every (from, to) pair the entity must move only where ADR-010 section 6 lists a
     * trigger, and a rejected move must leave the delivery exactly where it was. The three {@code @negative}
     * examples of E2-F2-S1 (DELIVERED to PENDING, DELIVERED to SENDING, PENDING to DELIVERED) are inside it.
     */
    @Test
    void everyPairOutsideTheRegisterIsRejectedAndTheStateIsUnchanged() {
        for (DeliveryState from : DeliveryState.values()) {
            for (DeliveryState to : DeliveryState.values()) {
                boolean legal = DeliveryTransitions.isLegal(from, to);
                Delivery delivery = deliveryIn(from);
                Throwable failure = catchThrowable(() -> delivery.moveTo(to, at(1)));
                if (legal) {
                    assertThat(failure).as("%s -> %s is legal (ADR-010 section 6)".formatted(from, to)).isNull();
                } else {
                    assertThat(failure).as("%s -> %s must be rejected".formatted(from, to)).isInstanceOf(
                            IllegalDeliveryTransitionException.class);
                    assertThat(delivery.state()).as("%s keeps its state after the rejected move".formatted(from))
                            .isEqualTo(from);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryState.class)
    void deliveredIsTerminal(DeliveryState to) {
        Delivery delivered = deliveryIn(DeliveryState.DELIVERED);
        assertThatThrownBy(() -> delivered.moveTo(to, at(1)))
                .isInstanceOf(IllegalDeliveryTransitionException.class);
        assertThat(delivered.state()).isEqualTo(DeliveryState.DELIVERED);
        assertThat(DeliveryTransitions.legalTargets(DeliveryState.DELIVERED)).isEmpty();
    }

    @Test
    void aRejectedMoveLeavesTheDeliveryExactlyWhereItWas() {
        Delivery pending = fresh();
        assertThatThrownBy(() -> pending.moveTo(DeliveryState.DELIVERED, at(1)))
                .isInstanceOf(IllegalDeliveryTransitionException.class);
        assertThat(pending.state()).isEqualTo(DeliveryState.PENDING);
        assertThat(pending.transitions()).isEmpty();

        Delivery moved = pending.moveTo(DeliveryState.SENDING, at(2));
        assertThat(moved.transitions()).hasSize(1);
        assertThat(pending.transitions()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("registerEdges")
    void legalTargetsMatchTheRegisterFromEachState(DeliveryState from, List<DeliveryState> expected) {
        assertThat(DeliveryTransitions.legalTargets(from)).containsExactlyInAnyOrderElementsOf(expected);
    }

    static Stream<Arguments> registerEdges() {
        return Stream.of(
                Arguments.of(DeliveryState.PENDING, List.of(DeliveryState.HELD, DeliveryState.SENDING,
                        DeliveryState.RETRY_WAIT)),
                Arguments.of(DeliveryState.HELD, List.of(DeliveryState.DUE)),
                Arguments.of(DeliveryState.SENDING, List.of(DeliveryState.DELIVERED, DeliveryState.RETRY_WAIT,
                        DeliveryState.DEAD_LETTERED)),
                Arguments.of(DeliveryState.RETRY_WAIT, List.of(DeliveryState.DUE)),
                Arguments.of(DeliveryState.DUE, List.of(DeliveryState.HELD, DeliveryState.SENDING,
                        DeliveryState.RETRY_WAIT)),
                Arguments.of(DeliveryState.DEAD_LETTERED, List.of(DeliveryState.DUE)),
                Arguments.of(DeliveryState.DELIVERED, List.<DeliveryState>of()));
    }

    /** A delivery sitting in {@code state}; created earlier so its state is only observable through the register. */
    private static Delivery deliveryIn(DeliveryState state) {
        if (state == DeliveryState.PENDING) {
            return fresh();
        }
        // Reach every other state through a legal path from PENDING.
        Delivery delivery = fresh();
        List<DeliveryState> path = switch (state) {
            case PENDING -> List.of();
            case HELD -> List.of(DeliveryState.HELD);
            case SENDING -> List.of(DeliveryState.SENDING);
            case RETRY_WAIT -> List.of(DeliveryState.SENDING, DeliveryState.RETRY_WAIT);
            case DUE -> List.of(DeliveryState.HELD, DeliveryState.DUE);
            case DELIVERED -> List.of(DeliveryState.SENDING, DeliveryState.DELIVERED);
            case DEAD_LETTERED -> List.of(DeliveryState.SENDING, DeliveryState.DEAD_LETTERED);
        };
        int second = 1;
        for (DeliveryState step : path) {
            delivery = delivery.moveTo(step, at(second++));
        }
        return delivery;
    }
}
